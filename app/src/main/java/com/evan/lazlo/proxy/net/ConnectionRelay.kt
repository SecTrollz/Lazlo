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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    ) {
        when (destinationPort) {
            443 -> relayTls(flow, destinationAddress, destinationPort, protectSocket, leafCertificateFactory, caCertificate, sendToClient, onHttpExchange)
            80 -> relayPlain(flow, destinationAddress, destinationPort, protectSocket, sendToClient, onHttpExchange)
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
    ) = coroutineScope {
        val fallbackHost = destinationAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val requestSniffer = HttpLineSniffer()
        val responseSniffer = HttpLineSniffer()
        var bytesTotal = 0L
        var loggedHost = fallbackHost

        val localAddress = LocalAddress("lazlo-mitm-${localFlowIds.incrementAndGet()}")
        val sniResolved = CompletableDeferred<String>()
        val decryptedFromClient = Channel<ByteArray>(Channel.UNLIMITED)
        val acceptedChannelDeferred = CompletableDeferred<NettyChannel>()

        // Server side of the local pair: SniHandler picks (and, via
        // LeafCertificateFactory, mints) the right leaf cert per host,
        // then SslHandler does the actual TLS handshake/record framing.
        // Whatever reaches the end of this pipeline is decrypted
        // application data.
        ServerBootstrap()
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
                                bytesTotal += bytes.size
                                decryptedFromClient.trySend(bytes)
                            } else {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            decryptedFromClient.close()
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            decryptedFromClient.close(cause as? Exception ?: RuntimeException(cause))
                            ctx.close()
                        }
                    })
                    acceptedChannelDeferred.complete(ch)
                }
            })
            .bind(localAddress)
            .awaitCompletion()

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
        bridgeConnect.awaitCompletion()
        val bridgeChannel = bridgeConnect.channel()
        val acceptedChannel = acceptedChannelDeferred.await()

        var upstream: NettyChannel? = null
        try {
            val appToBridge = launch(Dispatchers.Default) {
                for (chunk in flow.fromClient) bridgeChannel.writeAndFlush(Unpooled.wrappedBuffer(chunk))
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
                for (bytes in decryptedFromClient) connectedUpstream.writeAndFlush(Unpooled.wrappedBuffer(bytes))
            }
            val upstreamToClient = launch(Dispatchers.Default) {
                for (bytes in upstreamResponses) {
                    responseSniffer.feed(bytes, bytes.size)
                    bytesTotal += bytes.size
                    // Writing plaintext out through the accepted (server)
                    // channel re-encrypts it via its SslHandler; the
                    // result flows back through the local pair to
                    // bridgeChannel's inbound side above.
                    acceptedChannel.writeAndFlush(Unpooled.wrappedBuffer(bytes))
                }
            }

            appToBridge.join()
            bridgeToApp.join()
            clientToUpstream.join()
            upstreamToClient.join()
        } finally {
            runCatching { upstream?.close() }
            runCatching { bridgeChannel.close() }
            runCatching { acceptedChannel.close() }
            logExchange(onHttpExchange, loggedHost, requestSniffer.requestLine, responseSniffer.statusCode, bytesTotal)
        }
    }

    private fun sslContextForHost(host: String, leafCertificateFactory: LeafCertificateFactory, caCertificate: X509Certificate): SslContext =
        sslContextCache.getOrPut(host) {
            val leaf = leafCertificateFactory.leafFor(host)
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
    ) = coroutineScope {
        val host = destinationAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val requestSniffer = HttpLineSniffer()
        val responseSniffer = HttpLineSniffer()
        var bytesTotal = 0L

        val fromUpstream = Channel<ByteArray>(Channel.UNLIMITED)
        val upstream = connectUpstream(destinationAddress, destinationPort, protectSocket, null, null, fromUpstream)
        try {
            val toUpstream = launch(Dispatchers.Default) {
                for (chunk in flow.fromClient) {
                    requestSniffer.feed(chunk, chunk.size)
                    bytesTotal += chunk.size
                    upstream.writeAndFlush(Unpooled.wrappedBuffer(chunk))
                }
            }
            val toClient = launch(Dispatchers.Default) {
                for (bytes in fromUpstream) {
                    responseSniffer.feed(bytes, bytes.size)
                    bytesTotal += bytes.size
                    sendToClient(bytes)
                }
            }
            toUpstream.join()
            toClient.join()
        } finally {
            runCatching { upstream.close() }
            logExchange(onHttpExchange, host, requestSniffer.requestLine, responseSniffer.statusCode, bytesTotal)
        }
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
            toUpstream.join()
            toClient.join()
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

    private fun logExchange(onHttpExchange: (TrafficEntry) -> Unit, host: String, requestLine: String?, statusCode: Int?, bytes: Long) {
        val parts = requestLine?.split(' ')
        val method = parts?.getOrNull(0) ?: "?"
        val path = parts?.getOrNull(1) ?: ""
        onHttpExchange(TrafficEntry(method = method, host = host, path = path, status = statusCode, bytes = bytes))
    }
}
