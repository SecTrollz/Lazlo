package com.evan.lazlo.proxy.net

import com.evan.lazlo.proxy.LeafCertificateFactory
import com.evan.lazlo.proxy.TrafficEntry
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.local.LocalAddress
import io.netty.channel.local.LocalChannel
import io.netty.channel.local.LocalServerChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DuplexChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SniHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.Mapping
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import io.netty.channel.Channel as NettyChannel

/**
 * The actual proxying work for one ESTABLISHED [TcpFlow]: a Netty-based
 * embedded proxy that terminates TLS on :443 — re-signing each host's
 * leaf certificate on the fly via [LeafCertificateFactory], the "Netty +
 * a MITM layer" design ARCHITECTURE.md calls for — relays :80 (logging
 * a best-effort request/status line for both), or passes anything else
 * straight through so the rest of the device's traffic keeps working
 * while the inspector is on, even though only HTTP(S) is inspected.
 *
 * The local ("server") side of TLS termination is a real in-process
 * Netty channel pair on [LocalChannel]/[LocalServerChannel] — Netty's
 * own local transport for intra-JVM pipes — rather than an
 * [io.netty.channel.embedded.EmbeddedChannel]. `EmbeddedChannel` is
 * built for driving a pipeline synchronously from a single caller (it's
 * meant for unit-testing handlers); this flow needs bytes fed in from
 * one coroutine and drained from another, and forcing that through
 * `EmbeddedChannel` would mean hand-rolling a mutex around a class
 * that's documented as not safe for that. The local-transport pair runs
 * each side's [SniHandler]/`SslHandler` on a real `EventLoop`, exactly
 * like a real socket connection, so Netty's own per-channel
 * single-threaded execution guarantee does the synchronization instead
 * of anything this class writes itself. Writing plaintext response
 * bytes into the accepted (server) side later triggers real encryption
 * the same way a real TLS server would.
 *
 * The upstream ("client") side to the real destination is a genuine
 * [Bootstrap]-managed [NioSocketChannel] built from a [SocketChannel]
 * that's [protectSocket]-protected *before* Netty connects it, so the
 * interceptor's own upstream traffic isn't recaptured by the VPN's own
 * routes.
 *
 * Every decrypted request/response chunk on :443 and :80 is also run
 * through [RewriteEngine] against the caller-supplied [RewriteRule]s —
 * this is what turns the inspector from a read-only viewer into an
 * active MITM: a matching rule can set/remove a header or find/replace
 * the body before the bytes ever reach their destination (for a
 * request) or the app (for a response). An empty rule list — the
 * default — costs nothing extra; [RewriteEngine] short-circuits before
 * touching a single byte.
 */
internal class ConnectionRelay(private val eventLoopGroup: NioEventLoopGroup) {

    private val sslContextCache = ConcurrentHashMap<String, SslContext>()
    private val localFlowIds = AtomicLong(0)

    suspend fun relay(
        flow: TcpFlow,
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        leafCertificateFactory: LeafCertificateFactory,
        caCertificate: X509Certificate,
        sendToClient: suspend (ByteArray) -> Unit,
        onHttpExchange: (TrafficEntry) -> Unit,
        /** Live snapshot of the user's rewrite rules, resolved once per flow — see [RewriteEngine]. Empty means every byte passes through exactly as before this feature existed. */
        rules: List<RewriteRule> = emptyList(),
    ) {
        when (destinationPort) {
            443 -> relayTls(flow, destinationAddress, destinationPort, protectSocket, leafCertificateFactory, caCertificate, sendToClient, onHttpExchange, rules)
            80 -> relayPlain(flow, destinationAddress, destinationPort, protectSocket, sendToClient, onHttpExchange, rules)
            else -> relayPassthrough(flow, destinationAddress, destinationPort, protectSocket, sendToClient)
        }
    }

    /** Releases the shared Netty event loop backing every relay this instance has driven. */
    fun shutdown() {
        eventLoopGroup.shutdownGracefully()
    }

    // ---- :443 — TLS-terminating MITM ----

    private suspend fun relayTls(
        flow: TcpFlow,
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        leafCertificateFactory: LeafCertificateFactory,
        caCertificate: X509Certificate,
        sendToClient: suspend (ByteArray) -> Unit,
        onHttpExchange: (TrafficEntry) -> Unit,
        rules: List<RewriteRule>,
    ) = coroutineScope {
        val fallbackHost = destinationAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val requestSniffer = HttpLineSniffer()
        val responseSniffer = HttpLineSniffer()
        // Written from a Netty event-loop thread (the decrypted-request
        // handler) and from this flow's own coroutines, then read once more
        // when the exchange is logged — three different threads, so plain
        // captured `var`s would race and under-count (or publish nothing at
        // all) rather than just being slightly off.
        val bytesTotal = AtomicLong(0)
        var loggedHost = fallbackHost
        val firstRequestBytes = AtomicReference<ByteArray?>(null)

        val localAddress = LocalAddress("lazlo-mitm-${localFlowIds.incrementAndGet()}")
        val sniResolved = CompletableDeferred<String>()
        val decryptedFromClient = Channel<ByteArray>(Channel.UNLIMITED)
        val acceptedChannelDeferred = CompletableDeferred<NettyChannel>()

        // Server side of the local pair: SniHandler picks (and, via
        // LeafCertificateFactory, mints) the right leaf cert per host,
        // then SslHandler does the actual TLS handshake/record framing.
        // Whatever reaches the end of this pipeline is decrypted
        // application data.
        val bindFuture = ServerBootstrap()
            .group(eventLoopGroup)
            .channel(LocalServerChannel::class.java)
            .childHandler(object : ChannelInitializer<NettyChannel>() {
                override fun initChannel(ch: NettyChannel) {
                    ch.pipeline().addLast(
                        SniHandler(
                            Mapping<String, SslContext> { host ->
                                val h = host ?: fallbackHost
                                sniResolved.complete(h)
                                sslContextForHost(h, leafCertificateFactory, caCertificate)
                            }
                        )
                    )
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is ByteBuf) {
                                val bytes = ByteArray(msg.readableBytes())
                                msg.readBytes(bytes)
                                msg.release()
                                requestSniffer.feed(bytes, bytes.size)
                                firstRequestBytes.compareAndSet(null, bytes)
                                bytesTotal.addAndGet(bytes.size.toLong())
                                decryptedFromClient.trySend(bytes)
                            } else {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            decryptedFromClient.close()
                            // A TLS session that ends before SniHandler ever
                            // parsed a ClientHello (an aborted handshake, a
                            // client that connects and says nothing) would
                            // otherwise leave the sniResolved.await() below
                            // suspended forever, holding the whole flow — and
                            // its upstream socket and coroutines — open.
                            sniResolved.completeExceptionally(IOException("TLS session ended before SNI was resolved"))
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            sniResolved.completeExceptionally(cause)
                            decryptedFromClient.close(cause as? Exception ?: RuntimeException(cause))
                            ctx.close()
                        }
                    })
                    acceptedChannelDeferred.complete(ch)
                }
            })
            .bind(localAddress)
        bindFuture.awaitCompletion()
        // Bound LocalServerChannels stay registered in Netty's process-wide
        // LocalChannelRegistry until closed. One per TLS flow, never closed,
        // is an unbounded leak for as long as the inspector runs — hence the
        // explicit close in the finally below.
        val serverChannel = bindFuture.channel()

        // Client side of that same pair: bytes written here are exactly
        // what the app's real TLS client would send "over the wire" (its
        // actual captured bytes get forwarded here below); bytes read
        // here are whatever the server side above wrote back out —
        // i.e. real encrypted TLS response bytes headed for the app.
        val encryptedToClient = Channel<ByteArray>(Channel.UNLIMITED)
        val bridgeConnect = Bootstrap()
            .group(eventLoopGroup)
            .channel(LocalChannel::class.java)
            .handler(object : ChannelInitializer<NettyChannel>() {
                override fun initChannel(ch: NettyChannel) {
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is ByteBuf) {
                                val bytes = ByteArray(msg.readableBytes())
                                msg.readBytes(bytes)
                                msg.release()
                                encryptedToClient.trySend(bytes)
                            } else {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            encryptedToClient.close()
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            encryptedToClient.close(cause as? Exception ?: RuntimeException(cause))
                            ctx.close()
                        }
                    })
                }
            })
            .connect(localAddress)
        val bridgeChannel: NettyChannel
        val acceptedChannel: NettyChannel
        try {
            bridgeConnect.awaitCompletion()
            bridgeChannel = bridgeConnect.channel()
            acceptedChannel = acceptedChannelDeferred.await()
        } catch (t: Throwable) {
            // The main try/finally below hasn't started yet, so nothing else
            // would ever close the channel bound just above if the local pair
            // fails to connect.
            runCatching { bridgeConnect.channel()?.close() }
            runCatching { serverChannel.close() }
            throw t
        }

        var upstream: NettyChannel? = null
        try {
            val appToBridge = launch(Dispatchers.Default) {
                try {
                    for (chunk in flow.fromClient) bridgeChannel.writeAndFlush(Unpooled.wrappedBuffer(chunk))
                } finally {
                    // Same hazard as channelInactive above, from the other
                    // side: if the app closed the flow before its ClientHello
                    // ever got through, nothing else would ever complete this.
                    sniResolved.completeExceptionally(IOException("client closed before TLS SNI was resolved"))
                }
            }
            val bridgeToApp = launch(Dispatchers.Default) {
                for (bytes in encryptedToClient) sendToClient(bytes)
            }

            // SNI resolves as soon as SniHandler parses the app's
            // ClientHello (fed in by appToBridge above); only then do we
            // know which real host to dial upstream.
            val resolvedHost = sniResolved.await()
            loggedHost = resolvedHost
            val clientSslContext = SslContextBuilder.forClient().build()
            val upstreamResponses = Channel<ByteArray>(Channel.UNLIMITED)
            val connectedUpstream = connectUpstream(destinationAddress, destinationPort, protectSocket, clientSslContext, resolvedHost, upstreamResponses)
            upstream = connectedUpstream

            val clientToUpstream = launch(Dispatchers.Default) {
                for (bytes in decryptedFromClient) {
                    val rewritten = RewriteEngine.rewriteRequest(bytes, resolvedHost, rules)
                    connectedUpstream.writeAndFlush(Unpooled.wrappedBuffer(rewritten))
                }
            }
            val upstreamToClient = launch(Dispatchers.Default) {
                for (bytes in upstreamResponses) {
                    responseSniffer.feed(bytes, bytes.size)
                    bytesTotal.addAndGet(bytes.size.toLong())
                    val rewritten = RewriteEngine.rewriteResponse(bytes, resolvedHost, rules)
                    // Writing plaintext out through the accepted (server)
                    // channel re-encrypts it via its SslHandler; the
                    // result flows back through the local pair to
                    // bridgeChannel's inbound side above.
                    acceptedChannel.writeAndFlush(Unpooled.wrappedBuffer(rewritten))
                }
            }

            // The flow is over as soon as *either* end is done: the app
            // half-closing (its FIN closes flow.fromClient, ending appToBridge)
            // or the upstream closing (ending upstreamToClient). Joining all
            // four unconditionally deadlocks instead: clientToUpstream only
            // ends when decryptedFromClient closes, which only happens when the
            // accepted channel goes inactive — and nothing in a completed
            // exchange closes it. That left every HTTPS flow parked here
            // forever, so finishFlow() never ran: no FIN to the client, and the
            // flow's coroutines, channels and upstream socket leaked for the
            // lifetime of the VPN session.
            val flowEnded = CompletableDeferred<Unit>()
            appToBridge.invokeOnCompletion { flowEnded.complete(Unit) }
            upstreamToClient.invokeOnCompletion { flowEnded.complete(Unit) }
            flowEnded.await()

            // Closing the accepted (TLS server) side is what unblocks the rest:
            // it flushes whatever was already written toward the app, then
            // tears down the local pair, closing both decryptedFromClient
            // (ends clientToUpstream) and encryptedToClient (ends bridgeToApp).
            runCatching { acceptedChannel.close().awaitCompletion() }
            clientToUpstream.join()
            bridgeToApp.join()
            // The app may simply never send its FIN (an idle keep-alive
            // connection), so this one has to be cancelled rather than awaited.
            appToBridge.cancel()
            appToBridge.join()
            runCatching { connectedUpstream.close().awaitCompletion() }
            upstreamToClient.join()
        } finally {
            runCatching { upstream?.close() }
            runCatching { bridgeChannel.close() }
            runCatching { acceptedChannel.close() }
            runCatching { serverChannel.close() }
            logExchange(
                onHttpExchange,
                "https",
                loggedHost,
                requestSniffer.requestLine,
                responseSniffer.statusCode,
                bytesTotal.get(),
                firstRequestBytes.get(),
            )
        }
    }

    // computeIfAbsent rather than getOrPut for the same reason as
    // LeafCertificateFactory.leafFor: parallel connections to a new host would
    // otherwise each build their own SslContext (and mint their own leaf) for
    // it, on the handshake's critical path.
    private fun sslContextForHost(host: String, leafCertificateFactory: LeafCertificateFactory, caCertificate: X509Certificate): SslContext =
        sslContextCache.computeIfAbsent(host) {
            val leaf = leafCertificateFactory.leafFor(it)
            SslContextBuilder.forServer(leaf.privateKey, leaf.certificate, caCertificate).build()
        }

    // ---- :80 — plain relay ----

    private suspend fun relayPlain(
        flow: TcpFlow,
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        sendToClient: suspend (ByteArray) -> Unit,
        onHttpExchange: (TrafficEntry) -> Unit,
        rules: List<RewriteRule>,
    ) = coroutineScope {
        val host = destinationAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val requestSniffer = HttpLineSniffer()
        val responseSniffer = HttpLineSniffer()
        // Both directions run on their own coroutine, so these counters are
        // genuinely shared mutable state — see relayTls for the same note.
        val bytesTotal = AtomicLong(0)
        val firstRequestBytes = AtomicReference<ByteArray?>(null)

        val fromUpstream = Channel<ByteArray>(Channel.UNLIMITED)
        val upstream = connectUpstream(destinationAddress, destinationPort, protectSocket, null, null, fromUpstream)
        try {
            val toUpstream = launch(Dispatchers.Default) {
                for (chunk in flow.fromClient) {
                    requestSniffer.feed(chunk, chunk.size)
                    firstRequestBytes.compareAndSet(null, chunk)
                    bytesTotal.addAndGet(chunk.size.toLong())
                    val rewritten = RewriteEngine.rewriteRequest(chunk, host, rules)
                    upstream.writeAndFlush(Unpooled.wrappedBuffer(rewritten))
                }
            }
            val toClient = launch(Dispatchers.Default) {
                for (bytes in fromUpstream) {
                    responseSniffer.feed(bytes, bytes.size)
                    bytesTotal.addAndGet(bytes.size.toLong())
                    val rewritten = RewriteEngine.rewriteResponse(bytes, host, rules)
                    sendToClient(rewritten)
                }
            }
            toUpstream.invokeOnCompletion { halfCloseUpstream(upstream) }
            toClient.join()
            // The app may never send a FIN of its own (keep-alive), so once the
            // upstream is done this direction is cancelled rather than awaited.
            toUpstream.cancel()
            toUpstream.join()
        } finally {
            runCatching { upstream.close() }
            logExchange(
                onHttpExchange,
                "http",
                host,
                requestSniffer.requestLine,
                responseSniffer.statusCode,
                bytesTotal.get(),
                firstRequestBytes.get(),
            )
        }
    }

    /**
     * Propagates the app's half-close to the upstream connection instead of
     * just stopping: a server still waiting on the end of the request needs
     * that EOF before it will answer and close — and *its* close is what ends
     * the response-side loop. Closing outright instead would truncate an
     * in-flight response; doing nothing (the previous behavior) left a flow
     * whose client had FIN'd against a keep-alive server relaying forever.
     */
    private fun halfCloseUpstream(upstream: NettyChannel) {
        runCatching { (upstream as? DuplexChannel)?.shutdownOutput() }
    }

    // ---- anything else — raw passthrough, not inspected ----

    private suspend fun relayPassthrough(
        flow: TcpFlow,
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        sendToClient: suspend (ByteArray) -> Unit,
    ) = coroutineScope {
        val fromUpstream = Channel<ByteArray>(Channel.UNLIMITED)
        val upstream = connectUpstream(destinationAddress, destinationPort, protectSocket, null, null, fromUpstream)
        try {
            val toUpstream = launch(Dispatchers.Default) {
                for (chunk in flow.fromClient) upstream.writeAndFlush(Unpooled.wrappedBuffer(chunk))
            }
            val toClient = launch(Dispatchers.Default) {
                for (bytes in fromUpstream) sendToClient(bytes)
            }
            toUpstream.invokeOnCompletion { halfCloseUpstream(upstream) }
            toClient.join()
            toUpstream.cancel()
            toUpstream.join()
        } finally {
            runCatching { upstream.close() }
        }
    }

    // ---- shared upstream connector ----

    /**
     * Dials [destinationAddress]:[destinationPort] as a Netty-managed
     * channel, wrapping a [SocketChannel] that's [protectSocket]-protected
     * before Netty ever touches it. When [clientSslContext] is non-null,
     * a real client-side TLS handshake to [sniHost] runs on this
     * connection first (with hostname verification explicitly enabled —
     * an [SslContext] alone validates the chain against the trust store
     * but doesn't check the hostname matches without this), and
     * everything the caller writes to the returned channel is plaintext
     * that Netty encrypts on the wire. Every inbound byte this channel
     * receives (decrypted already, if TLS is in play) lands on
     * [responseChannel]; that channel is closed when the connection
     * closes and closed-with-exception if it fails.
     */
    private suspend fun connectUpstream(
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        clientSslContext: SslContext?,
        sniHost: String?,
        responseChannel: Channel<ByteArray>,
    ): NettyChannel {
        val socketChannel = SocketChannel.open()
        protectSocket(socketChannel.socket())

        val bootstrap = Bootstrap()
            .group(eventLoopGroup)
            .channelFactory(ChannelFactory<NettyChannel> { NioSocketChannel(socketChannel) })
            .handler(object : ChannelInitializer<NettyChannel>() {
                override fun initChannel(ch: NettyChannel) {
                    if (clientSslContext != null && sniHost != null) {
                        val sslHandler = clientSslContext.newHandler(ch.alloc(), sniHost, destinationPort)
                        val params = sslHandler.engine().sslParameters
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        sslHandler.engine().sslParameters = params
                        ch.pipeline().addLast(sslHandler)
                    }
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is ByteBuf) {
                                val bytes = ByteArray(msg.readableBytes())
                                msg.readBytes(bytes)
                                msg.release()
                                responseChannel.trySend(bytes)
                            } else {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            responseChannel.close()
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            responseChannel.close(cause as? Exception ?: RuntimeException(cause))
                            ctx.close()
                        }
                    })
                }
            })

        val address = InetSocketAddress(InetAddress.getByAddress(destinationAddress), destinationPort)
        val future = bootstrap.connect(address)
        future.awaitCompletion()
        return future.channel()
    }

    private suspend fun ChannelFuture.awaitCompletion() {
        if (isDone) {
            if (!isSuccess) throw cause() ?: IllegalStateException("Netty operation failed")
            return
        }
        val deferred = CompletableDeferred<Unit>()
        addListener { f ->
            if (f.isSuccess) deferred.complete(Unit) else deferred.completeExceptionally(f.cause() ?: IllegalStateException("Netty operation failed"))
        }
        deferred.await()
    }

    private fun logExchange(
        onHttpExchange: (TrafficEntry) -> Unit,
        scheme: String,
        host: String,
        requestLine: String?,
        statusCode: Int?,
        bytes: Long,
        firstRequestBytes: ByteArray?,
    ) {
        val parts = requestLine?.split(' ')
        val method = parts?.getOrNull(0) ?: "?"
        val path = parts?.getOrNull(1) ?: ""
        val replay = firstRequestBytes?.let { RewriteEngine.captureForReplay(it, scheme, host) }
        onHttpExchange(TrafficEntry(method = method, host = host, path = path, status = statusCode, bytes = bytes, replay = replay))
    }
}
