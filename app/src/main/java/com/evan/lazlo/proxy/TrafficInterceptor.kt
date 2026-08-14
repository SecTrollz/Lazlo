package com.evan.lazlo.proxy

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Skeleton for the actual packet pump: reads IP packets from the TUN fd,
 * reassembles TCP streams per (srcPort, dstHost), and for port 443
 * terminates TLS locally with a leaf cert re-signed by the local CA
 * (one leaf per host, cached), then opens a real upstream TLS connection
 * to the original destination and shuttles bytes both ways while logging
 * each HTTP request/response line. Full implementation lives on top of
 * a userspace TCP/IP stack (e.g. a Kotlin port of gVisor's netstack or
 * tun2socks) — omitted here as it's a substantial standalone component.
 */
class TrafficInterceptor(
    private val ca: X509Certificate,
    private val onRequest: (TrafficEntry) -> Unit,
) {
    suspend fun pump(tunFd: ParcelFileDescriptor) {
        // 1. Read raw packets from tunFd.
        // 2. Hand IPv4/TCP streams to a userspace stack (tun2socks-style).
        // 3. For each new TCP stream on :443, do local TLS termination
        //    with a per-host leaf cert signed by `ca`, then dial the real
        //    host over TLS and relay, calling onRequest() per exchange.
        // 4. For :80, parse HTTP directly.
    }

    suspend fun shutdown() {
        // Close all active streams / the userspace stack cleanly.
    }
}
