package com.evan.lazlo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.evan.lazlo.ai.AiProviderFactory
import com.evan.lazlo.browser.ChromiumEngine
import com.evan.lazlo.browser.EngineKind
import com.evan.lazlo.browser.GeckoEngine
import com.evan.lazlo.core.SecretStore
import com.evan.lazlo.core.Settings
import com.evan.lazlo.proxy.CertificateAuthority
import com.evan.lazlo.proxy.MitmVpnService
import kotlinx.coroutines.launch

/**
 * Single-Activity host wiring the three swappable pieces together:
 * a BrowserEngine tab, an AiProvider-backed chat sheet, and the
 * inspector toggle. This is scaffolding, not the finished UI — see
 * ARCHITECTURE.md's build-out order for what each screen still needs.
 */
class MainActivity : ComponentActivity() {

    private val settings by lazy { Settings(applicationContext) }
    private val secretStore by lazy { SecretStore(applicationContext) }
    private val aiProviderFactory by lazy {
        AiProviderFactory(applicationContext, settings, secretStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LazloRoot() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LazloRoot() {
        val scope = rememberCoroutineScope()
        var engineKind by remember { mutableStateOf(EngineKind.CHROMIUM) }
        var inspectorEnabled by remember { mutableStateOf(false) }

        MaterialTheme {
            Scaffold(
                topBar = { TopAppBar(title = { Text("Lazlo") }) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        AndroidView(factory = { ctx ->
                            val container = android.widget.FrameLayout(ctx)
                            val engine = when (engineKind) {
                                EngineKind.CHROMIUM -> ChromiumEngine(ctx)
                                EngineKind.GECKO -> GeckoEngine(ctx)
                            }
                            engine.attach(container)
                            engine.loadUrl("about:blank")
                            container
                        })
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilterChip(
                            selected = engineKind == EngineKind.CHROMIUM,
                            onClick = {
                                engineKind = EngineKind.CHROMIUM
                                scope.launch { settings.setBrowserEngine(EngineKind.CHROMIUM) }
                            },
                            label = { Text("WebView") },
                        )
                        FilterChip(
                            selected = engineKind == EngineKind.GECKO,
                            onClick = {
                                engineKind = EngineKind.GECKO
                                scope.launch { settings.setBrowserEngine(EngineKind.GECKO) }
                            },
                            label = { Text("GeckoView") },
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = inspectorEnabled,
                            onCheckedChange = { enabled ->
                                inspectorEnabled = enabled
                                scope.launch { settings.setInspectorEnabled(enabled) }
                                if (enabled) startInspector() else stopInspector()
                            },
                        )
                        Text("Inspector", modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }

    /** Prompts the OS VPN consent dialog; the service only ever loops back to localhost. */
    private fun startInspector() {
        CertificateAuthority(applicationContext).ensureCaExists()
        val consent = android.net.VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN_CONSENT)
        } else {
            startService(Intent(this, MitmVpnService::class.java))
        }
    }

    private fun stopInspector() {
        stopService(Intent(this, MitmVpnService::class.java))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_CONSENT && resultCode == RESULT_OK) {
            startService(Intent(this, MitmVpnService::class.java))
        }
    }

    private companion object {
        const val REQUEST_VPN_CONSENT = 100
    }
}
