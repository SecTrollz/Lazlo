package com.evan.lazlo.proxy

import android.os.ParcelFileDescriptor
import com.evan.lazlo.proxy.net.TcpIpStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant

data class TrafficEntry(
    val method: String,
    val host: String,
    val path: String,
    val status: Int?,
    val bytes: Long,
    val timestamp: Instant = Instant.now(),
)

/** Simple in-memory ring buffer; nothing here is written to disk unless the user exports it. */
object TrafficLog {
    private const val MAX = 500
    private val _entries = MutableStateFlow<List<TrafficEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun append(entry: TrafficEntry) {
        _entries.value = (_entries.value + entry).takeLast(MAX)
    }

    fun clear() { _entries.value = emptyList() }
}

/**
 * Thin wrapper tying [TcpIpStack] — the actual packet pump — to the
 * lifecycle [MitmVpnService] drives it with, and to this app's own
 * request-logging ([TrafficLog]) and CA plumbing. See [TcpIpStack]'s own
 * doc for what the pump does and, importantly, what it deliberately
 * doesn't (its "one local, well-behaved TCP peer" scope note).
 */
class TrafficInterceptor(
    private val caCertificate: X509Certificate,
    private val caPrivateKey: PrivateKey,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean,
    private val onRequest: (TrafficEntry) -> Unit,
    private val scope: CoroutineScope,
) {
    private var stack: TcpIpStack? = null

    suspend fun pump(tunFd: ParcelFileDescriptor) {
        val running = TcpIpStack(
            tunFd = tunFd,
            protectSocket = protectSocket,
            protectDatagramSocket = protectDatagramSocket,
            caCertificate = caCertificate,
            caPrivateKey = caPrivateKey,
            onHttpExchange = onRequest,
            scope = scope,
        )
        stack = running
        running.pump() // suspends for as long as the VPN interface is up
    }

    fun shutdown() {
        stack?.shutdown()
        stack = null
    }
}
