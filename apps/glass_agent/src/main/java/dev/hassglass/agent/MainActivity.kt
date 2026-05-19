package dev.hassglass.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.hassglass.agent.discovery.HassGlassAdvertiser
import dev.hassglass.agent.discovery.HomeAssistantDiscovery
import dev.hassglass.agent.pairing.AgentIdentity
import dev.hassglass.agent.pairing.OkHttpPairingTransport
import dev.hassglass.agent.pairing.PairingClient
import dev.hassglass.agent.pairing.PairingCodeGenerator
import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.SharedPreferencesKeyValueStore
import java.util.UUID
import java.util.concurrent.Executors

/**
 * On-glasses launcher.
 *
 * Unpaired path:
 *  - Generate a 6-digit pairing code.
 *  - Discover HA via mDNS (`_home-assistant._tcp.local.`) — no URL input needed.
 *  - Advertise `_hassglass._tcp.local.` so HA's zeroconf integration surfaces a "Discovered"
 *    card with our identity + code in TXT records.
 *  - POST the code to HA's pairing endpoint and park; HA resolves the POST once the user
 *    confirms the code in the HA UI.
 *
 * Paired path:
 *  - Start/Stop the foreground agent service. Clear-pairing escape hatch.
 */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pairingExecutor = Executors.newSingleThreadExecutor()

    private lateinit var preferences: SharedPreferences
    private lateinit var settingsStore: AgentSettingsStore
    private lateinit var deviceId: String
    private lateinit var advertiser: HassGlassAdvertiser
    private lateinit var discovery: HomeAssistantDiscovery

    private var discoveryHandle: HomeAssistantDiscovery.Handle? = null
    private var pairingInFlight: Boolean = false
    private var pendingCode: String? = null

    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var pairButton: Button
    private lateinit var cancelPairButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearPairingButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(HassGlassAgentService.PREFERENCES_NAME, MODE_PRIVATE)
        settingsStore = AgentSettingsStore(SharedPreferencesKeyValueStore(preferences))
        deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        advertiser = HassGlassAdvertiser(nsd)
        discovery = HomeAssistantDiscovery(nsd)

        setContentView(buildLayout())
        renderState()
    }

    override fun onDestroy() {
        stopPairing()
        pairingExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startAgentService()
        } else if (requestCode == REQUEST_MIC) {
            setStatus("Microphone permission is required to start the agent.")
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "HassGlass"
        }
        root.addView(statusView, lp(0, bottom = 24))

        codeView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(codeView, lp(MATCH_PARENT, bottom = 16))

        pairButton = Button(this).apply {
            text = "Pair with Home Assistant"
            setOnClickListener { startPairing() }
        }
        root.addView(pairButton, lp(WRAP_CONTENT, bottom = 16))

        cancelPairButton = Button(this).apply {
            text = "Cancel pairing"
            setOnClickListener { stopPairing(); renderState() }
            visibility = View.GONE
        }
        root.addView(cancelPairButton, lp(WRAP_CONTENT, bottom = 16))

        startButton = Button(this).apply {
            text = "Start agent"
            setOnClickListener { onStartClicked() }
        }
        root.addView(startButton, lp(WRAP_CONTENT, bottom = 8))

        stopButton = Button(this).apply {
            text = "Stop agent"
            setOnClickListener { onStopClicked() }
        }
        root.addView(stopButton, lp(WRAP_CONTENT, bottom = 8))

        clearPairingButton = Button(this).apply {
            text = "Clear pairing"
            setOnClickListener { onClearPairingClicked() }
        }
        root.addView(clearPairingButton, lp(WRAP_CONTENT))

        return root
    }

    private fun lp(width: Int = WRAP_CONTENT, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (width == 0) MATCH_PARENT else width, WRAP_CONTENT).apply {
            bottomMargin = bottom
        }

    private fun renderState() {
        val paired = settingsStore.loadPairedSettings()
        if (paired == null) {
            if (pairingInFlight) {
                pairButton.visibility = View.GONE
                cancelPairButton.visibility = View.VISIBLE
                codeView.visibility = View.VISIBLE
            } else {
                setStatus("Not paired. Tap Pair, then enter the code in Home Assistant.")
                codeView.visibility = View.GONE
                pairButton.visibility = View.VISIBLE
                cancelPairButton.visibility = View.GONE
            }
            startButton.visibility = View.GONE
            stopButton.visibility = View.GONE
            clearPairingButton.visibility = View.GONE
        } else {
            setStatus("Paired with ${paired.haBaseUrl}.")
            codeView.visibility = View.GONE
            pairButton.visibility = View.GONE
            cancelPairButton.visibility = View.GONE
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.VISIBLE
            clearPairingButton.visibility = View.VISIBLE
        }
    }

    private fun startPairing() {
        if (pairingInFlight) return
        val identity = currentIdentity()
        val code = PairingCodeGenerator.generate()
        pendingCode = code
        pairingInFlight = true
        codeView.text = code
        setStatus("Searching for Home Assistant on the network…")
        renderState()
        advertiser.advertise(identity, code)
        discoveryHandle = discovery.discover(object : HomeAssistantDiscovery.Listener {
            override fun onDiscovered(result: HomeAssistantDiscovery.Discovered) {
                mainHandler.post {
                    setStatus("Found HA at ${result.baseUrl}. Enter $code in Home Assistant.")
                }
                submitClaim(result.baseUrl, code, identity)
            }

            override fun onError(message: String) {
                mainHandler.post { setStatus("Discovery error: $message") }
            }
        })
    }

    private fun submitClaim(baseUrl: String, code: String, identity: AgentIdentity) {
        if (!pairingInFlight) return
        pairingExecutor.execute {
            val client = PairingClient(OkHttpPairingTransport(), settingsStore)
            val result = runCatching { client.claim(baseUrl, code, identity) }
            mainHandler.post {
                if (!pairingInFlight || pendingCode != code) return@post
                pairingInFlight = false
                stopPairing()
                result.onSuccess {
                    setStatus("Paired with ${it.haBaseUrl}.")
                    renderState()
                }.onFailure { error ->
                    setStatus("Pairing failed: ${error.message ?: error.javaClass.simpleName}")
                    renderState()
                }
            }
        }
    }

    private fun stopPairing() {
        pairingInFlight = false
        pendingCode = null
        discoveryHandle?.stop()
        discoveryHandle = null
        advertiser.unregister()
    }

    @Suppress("DEPRECATION")
    private fun currentIdentity(): AgentIdentity = AgentIdentity(
        deviceId = deviceId,
        serial = Build.SERIAL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: deviceId,
        firmware = Build.DISPLAY ?: Build.VERSION.RELEASE,
        agentVersion = AGENT_VERSION,
        name = Build.MODEL ?: "HassGlass",
    )

    private fun onStartClicked() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        startAgentService()
    }

    private fun startAgentService() {
        val intent = Intent(this, HassGlassAgentService::class.java)
        startForegroundService(intent)
        setStatus("Agent service started.")
    }

    private fun onStopClicked() {
        stopService(Intent(this, HassGlassAgentService::class.java))
        setStatus("Agent service stopped.")
    }

    private fun onClearPairingClicked() {
        settingsStore.clearPairing()
        renderState()
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    companion object {
        private const val REQUEST_MIC = 1001
        private const val KEY_DEVICE_ID = "device_uuid"
        private const val AGENT_VERSION = "0.1.0"
    }
}
