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
 * it to [TrafficInterceptor] (backed by [com.evan.lazlo.proxy.net.TcpIpStack]),
 * which terminates TLS using the CA from [CertificateAuthority], logs
 * each request, and re-establishes an upstream connection to the real
 * destination.
 *
 * This never routes traffic to a remote relay — everything happens on
 * localhost. Turning it off (or never installing the CA) leaves the app
 * fully passive. Every upstream socket the interceptor opens is passed
 * through [protect] before connecting, which is what stops this
 * service's own outbound connections from being recaptured by its own
 * VPN routes — without it, every proxied connection would loop back
 * into itself.
 */
class MitmVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var interceptor: TrafficInterceptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ca = CertificateAuthority(this)
        val caCertificate = ca.ensureCaExists()
        val caPrivateKey = ca.caPrivateKey()

        val running = TrafficInterceptor(
            caCertificate = caCertificate,
            caPrivateKey = caPrivateKey,
            protectSocket = { socket -> protect(socket) },
            protectDatagramSocket = { socket -> protect(socket) },
            onRequest = { entry -> TrafficLog.append(entry) },
            scope = scope,
        )
        interceptor = running

        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1") // encrypted upstream (DoH) applied inside the interceptor
            .setSession("Lazlo Inspector")

        vpnInterface = builder.establish()
        vpnInterface?.let { fd ->
            scope.launch { running.pump(fd) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        interceptor?.shutdown()
        interceptor = null
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
