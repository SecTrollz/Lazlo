package com.evan.lazlo.proxy.net

import com.evan.lazlo.proxy.LeafCertificateFactory
import com.evan.lazlo.proxy.TrafficEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManager
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The actual proxying work for one ESTABLISHED [TcpFlow]: dispatches to
 * a TLS-terminating relay on :443, a plain relay on :80 (both of which
 * log a best-effort request/status line), or a raw byte passthrough for
 * anything else — so the rest of the device's traffic keeps working
 * while the inspector is on, even though only HTTP(S) is inspected.
 *
 * The TLS path deliberately avoids hand-rolling a TLS state machine
 * against [javax.net.ssl.SSLEngine]. Instead it bounces the flow's raw
 * bytes through a real loopback [SSLServerSocket]/[SSLSocket] pair —
 * the standard, well-tested platform implementation does the actual
 * cryptography and SNI-based certificate selection; this class only
 * shuffles bytes between four streams.
 */
internal object ConnectionRelay {

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
        val sniKeyManager = SniKeyManager(leafCertificateFactory, caCertificate, fallbackHost)
        val serverContext = SSLContext.getInstance("TLS")
        serverContext.init(arrayOf<KeyManager>(sniKeyManager), null, null)

        // The loopback bridge: connecting a plain socket to our own
        // SSLServerSocket gets us a real SSLSocket (serverSideTlsSocket)
        // that terminates TLS using whatever leaf SniKeyManager selects,
        // without writing any handshake logic ourselves.
        val sslServerSocket = serverContext.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val bridgeSocket = Socket()
        bridgeSocket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), sslServerSocket.localPort))
        val serverSideTlsSocket = sslServerSocket.accept() as SSLSocket
        runCatching { sslServerSocket.close() } // one connection only; done with it once accepted

        var upstreamSslSocket: SSLSocket? = null
        val requestSniffer = HttpLineSniffer()
        val responseSniffer = HttpLineSniffer()
        var bytesTotal = 0L

        try {
            // App -> our fake TLS server: the flow's raw captured bytes
            // (the app's real ClientHello and onward) are exactly what
            // bridgeSocket needs to write for the SSLServerSocket side to
            // decrypt.
            val toBridge = launch(Dispatchers.IO) {
                try {
                    for (chunk in flow.fromClient) {
                        bridgeSocket.getOutputStream().write(chunk)
                        bridgeSocket.getOutputStream().flush()
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { bridgeSocket.shutdownOutput() }
                }
            }

            // Our fake TLS server -> app: raw encrypted bytes coming out
            // of the bridge are what the real app's TLS client expects to
            // receive "from the server" — send them straight back through
            // the TCP flow.
            val fromBridge = launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = bridgeSocket.getInputStream().read(buffer)
                        if (n < 0) break
                        sendToClient(buffer.copyOf(n))
                    }
                } catch (_: IOException) {
                }
            }

            // Reading the session forces the handshake to complete (or
            // throw) on this thread, so sniKeyManager has already resolved
            // a host by the time we read it back.
            serverSideTlsSocket.session
            val host = sniKeyManager.resolvedHost ?: fallbackHost

            val rawUpstream = Socket()
            protectSocket(rawUpstream)
            withContext(Dispatchers.IO) {
                rawUpstream.connect(InetSocketAddress(InetAddress.getByAddress(destinationAddress), destinationPort), CONNECT_TIMEOUT_MS)
            }
            // System trust store (default, unmodified init(null, null,
            // null)): the real upstream's certificate is validated
            // properly, same as any other TLS client would — a broken
            // real-world cert fails the connection here rather than being
            // silently accepted on the app's behalf.
            val upstreamContext = SSLContext.getInstance("TLS")
            upstreamContext.init(null, null, null)
            val upstream = upstreamContext.socketFactory.createSocket(rawUpstream, host, destinationPort, true) as SSLSocket
            val sniParams = upstream.sslParameters
            sniParams.serverNames = listOf(SNIHostName(host))
            upstream.sslParameters = sniParams
            upstreamSslSocket = upstream

            val appToUpstream = launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = serverSideTlsSocket.getInputStream().read(buffer)
                        if (n < 0) break
                        requestSniffer.feed(buffer, n)
                        upstream.getOutputStream().write(buffer, 0, n)
                        upstream.getOutputStream().flush()
                        bytesTotal += n
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { upstream.shutdownOutput() }
                }
            }
            val upstreamToApp = launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = upstream.getInputStream().read(buffer)
                        if (n < 0) break
                        responseSniffer.feed(buffer, n)
                        serverSideTlsSocket.getOutputStream().write(buffer, 0, n)
                        serverSideTlsSocket.getOutputStream().flush()
                        bytesTotal += n
                    }
                } catch (_: IOException) {
                }
            }

            appToUpstream.join()
            upstreamToApp.join()
            toBridge.cancel()
            fromBridge.join()
        } finally {
            runCatching { upstreamSslSocket?.close() }
            runCatching { serverSideTlsSocket.close() }
            runCatching { bridgeSocket.close() }
            logExchange(onHttpExchange, sniKeyManager.resolvedHost ?: fallbackHost, requestSniffer.requestLine, responseSniffer.statusCode, bytesTotal)
        }
    }

    private suspend fun relayPlain(
        flow: TcpFlow,
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        sendToClient: suspend (ByteArray) -> Unit,
        onHttpExchange: (TrafficEntry) -> Unit,
    ) = coroutineScope {
        val host = destinationAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val upstream = Socket()
        protectSocket(upstream)
        withContext(Dispatchers.IO) {
            upstream.connect(InetSocketAddress(InetAddress.getByAddress(destinationAddress), destinationPort), CONNECT_TIMEOUT_MS)
        }

        val requestSniffer = HttpLineSniffer()
        val responseSniffer = HttpLineSniffer()
        var bytesTotal = 0L
        try {
            val toUpstream = launch(Dispatchers.IO) {
                try {
                    for (chunk in flow.fromClient) {
                        requestSniffer.feed(chunk, chunk.size)
                        upstream.getOutputStream().write(chunk)
                        upstream.getOutputStream().flush()
                        bytesTotal += chunk.size
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { upstream.shutdownOutput() }
                }
            }
            val toClient = launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = upstream.getInputStream().read(buffer)
                        if (n < 0) break
                        responseSniffer.feed(buffer, n)
                        sendToClient(buffer.copyOf(n))
                        bytesTotal += n
                    }
                } catch (_: IOException) {
                }
            }
            toUpstream.join()
            toClient.join()
        } finally {
            runCatching { upstream.close() }
            logExchange(onHttpExchange, host, requestSniffer.requestLine, responseSniffer.statusCode, bytesTotal)
        }
    }

    private suspend fun relayPassthrough(
        flow: TcpFlow,
        destinationAddress: ByteArray,
        destinationPort: Int,
        protectSocket: (Socket) -> Boolean,
        sendToClient: suspend (ByteArray) -> Unit,
    ) = coroutineScope {
        val upstream = Socket()
        protectSocket(upstream)
        withContext(Dispatchers.IO) {
            upstream.connect(InetSocketAddress(InetAddress.getByAddress(destinationAddress), destinationPort), CONNECT_TIMEOUT_MS)
        }
        try {
            val toUpstream = launch(Dispatchers.IO) {
                try {
                    for (chunk in flow.fromClient) {
                        upstream.getOutputStream().write(chunk)
                        upstream.getOutputStream().flush()
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { upstream.shutdownOutput() }
                }
            }
            val toClient = launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = upstream.getInputStream().read(buffer)
                        if (n < 0) break
                        sendToClient(buffer.copyOf(n))
                    }
                } catch (_: IOException) {
                }
            }
            toUpstream.join()
            toClient.join()
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun logExchange(onHttpExchange: (TrafficEntry) -> Unit, host: String, requestLine: String?, statusCode: Int?, bytes: Long) {
        val parts = requestLine?.split(' ')
        val method = parts?.getOrNull(0) ?: "?"
        val path = parts?.getOrNull(1) ?: ""
        onHttpExchange(TrafficEntry(method = method, host = host, path = path, status = statusCode, bytes = bytes))
    }

    /**
     * Selects (and mints, via [leafCertificateFactory]) the leaf
     * certificate to present for whichever hostname the client's
     * ClientHello asks for over SNI, falling back to [fallbackHost] (the
     * real destination IP) for the rare SNI-less client so the handshake
     * can still complete instead of failing outright.
     */
    private class SniKeyManager(
        private val leafCertificateFactory: LeafCertificateFactory,
        private val caCertificate: X509Certificate,
        private val fallbackHost: String,
    ) : X509ExtendedKeyManager() {

        @Volatile var resolvedHost: String? = null
            private set

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
            val session = (socket as? SSLSocket)?.handshakeSession as? ExtendedSSLSession
            val sni = session?.requestedServerNames?.filterIsInstance<SNIHostName>()?.firstOrNull()?.asciiName
            val alias = sni ?: fallbackHost
            resolvedHost = alias
            return alias
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            val leaf = leafCertificateFactory.leafFor(alias ?: return null)
            return arrayOf(leaf.certificate, caCertificate)
        }

        override fun getPrivateKey(alias: String?): PrivateKey? =
            alias?.let { leafCertificateFactory.leafFor(it).privateKey }

        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String? = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private const val BUFFER_SIZE = 16 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
}
