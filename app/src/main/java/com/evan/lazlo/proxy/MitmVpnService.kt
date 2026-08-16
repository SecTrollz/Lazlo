package com.evan.lazlo.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
 *
 * Declared in the manifest as a `specialUse` foreground service, which
 * only takes effect once [android.app.Service.startForeground] is
 * actually called here — the persistent "Lazlo is inspecting this
 * device's traffic" notification below is both what that requires and,
 * for a self-interception privacy tool, the honest thing to show
 * whenever it's active rather than running it silently in the background.
 */
class MitmVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var interceptor: TrafficInterceptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        val ca = CertificateAuthority(this)
        val caCertificate = ca.ensureCaExists()
        val caPrivateKey = ca.caPrivateKey()
        val rewriteRuleStore = RewriteRuleStore(this)

        val running = TrafficInterceptor(
            caCertificate = caCertificate,
            caPrivateKey = caPrivateKey,
            protectSocket = { socket -> protect(socket) },
            protectDatagramSocket = { socket -> protect(socket) },
            onRequest = { entry -> TrafficLog.append(entry) },
            // Blocking read on the packet pump's own IO dispatcher, not
            // the main thread — same DataStore this service already
            // reads CA state from, just a different key. See
            // RewriteRuleStore.rulesBlocking's own doc for why a
            // synchronous read is the right shape here.
            rewriteRules = { rewriteRuleStore.rulesBlocking() },
            scope = scope,
        )
        interceptor = running

        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1") // encrypted upstream (DoH) applied inside the interceptor
            .setSession("Lazlo Inspector")

        // Without this, Lazlo's own network calls (chat requests to
        // Anthropic/OpenRouter, the Hugging Face model download) get
        // captured by this same VPN like any other app's traffic — routed
        // through TcpIpStack, TLS-intercepted with the locally-generated
        // CA, and rejected outright unless that CA happens to be installed
        // as a system-trusted certificate. `protect()` above only covers
        // sockets *TcpIpStack itself* opens to relay someone else's
        // traffic upstream; it does nothing for a completely separate
        // OkHttpClient elsewhere in this same app making its own request.
        // The practical effect without this exclusion: turning the
        // Inspector on breaks every one of Lazlo's own network-dependent
        // features at once (every backend that needs the network — BYOK,
        // the local-model download — all fail with a generic connection
        // error simultaneously) for as long as it stays on, which reads
        // exactly like "the model doesn't work" with no obvious cause
        // tying it back to a toggle on a different tab entirely.
        runCatching { builder.addDisallowedApplication(packageName) }

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

    /**
     * Promotes this service to a real foreground service with a visible,
     * ongoing notification. This is required (not just polite) once the
     * manifest declares `foregroundServiceType="specialUse"`: without an
     * actual [android.app.Service.startForeground] call the OS still
     * treats the process as a background service and can kill it under
     * memory pressure or Doze the same as any other backgrounded service,
     * despite the manifest scaffolding suggesting otherwise.
     */
    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Traffic inspector",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows while Lazlo is capturing this device's own traffic." }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Lazlo traffic inspector is on")
            .setContentText("Capturing this device's own traffic only. Tap to review it in Lazlo.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "lazlo_traffic_inspector"
        const val NOTIFICATION_ID = 1
    }
}
