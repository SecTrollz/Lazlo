package com.evan.lazlo.proxy

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Loopback-only VpnService: captures the device's own traffic and hands
 * it to a local Netty-based proxy (TrafficInterceptor) that terminates
 * TLS using the CA from CertificateAuthority, logs each request, and
 * re-establishes an upstream connection to the real destination.
 *
 * This never routes traffic to a remote relay — everything happens on
 * localhost. Turning it off (or never installing the CA) leaves the app
 * fully passive.
 */
class MitmVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var interceptor: TrafficInterceptor

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ca = CertificateAuthority(this).ensureCaExists()
        interceptor = TrafficInterceptor(ca, onRequest = { entry -> TrafficLog.append(entry) })

        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1") // encrypted upstream (DoH) applied inside the interceptor
            .setSession("Lazlo Inspector")

        vpnInterface = builder.establish()
        vpnInterface?.let { fd ->
            scope.launch { interceptor.pump(fd) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch { interceptor.shutdown() }
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
