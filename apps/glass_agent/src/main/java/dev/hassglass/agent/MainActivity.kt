package dev.hassglass.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import dev.hassglass.agent.hud.HudCardEnvelope
import dev.hassglass.agent.hud.SharedHudDisplayStore
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
 * - Generate a 6-digit pairing code.
 * - Discover HA via mDNS (`_home-assistant._tcp.local.`) — no URL input needed.
 * - Advertise `_hassglass._tcp.local.` so HA's zeroconf integration surfaces a "Discovered"
 * ```
 *    card with our identity + code in TXT records.
 * ```
 * - POST the code to HA's pairing endpoint and park; HA resolves the POST once the user
 * ```
 *    confirms the code in the HA UI.
 * ```
 * Paired path:
 * - Start/Stop the foreground agent service. Clear-pairing escape hatch.
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
    private var hudUnsubscribe: (() -> Unit)? = null

    private lateinit var statusView: TextView
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudTitleView: TextView
    private lateinit var hudBodyView: TextView
    private lateinit var codeView: TextView
    private lateinit var pairButton: Button
    private lateinit var cancelPairButton: Button
    private lateinit var startAgentButton: Button
    private lateinit var clearPairingButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(HassGlassAgentService.PREFERENCES_NAME, MODE_PRIVATE)
        settingsStore = AgentSettingsStore(SharedPreferencesKeyValueStore(preferences))
        deviceId =
                preferences.getString(KEY_DEVICE_ID, null)
                        ?: UUID.randomUUID().toString().also {
                            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
                        }
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        advertiser = HassGlassAdvertiser(nsd)
        discovery = HomeAssistantDiscovery(nsd)

        setContentView(buildLayout())
        renderState()
    }

    override fun onResume() {
        super.onResume()
        hudUnsubscribe =
                SharedHudDisplayStore.addListener { card -> mainHandler.post { renderHud(card) } }
        if (!pairingInFlight) {
            renderState()
            maybeStartAgent()
        }
    }

    override fun onPause() {
        hudUnsubscribe?.invoke()
        hudUnsubscribe = null
        super.onPause()
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
        if (requestCode == REQUEST_MIC &&
                        grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            maybeStartAgent()
        } else if (requestCode == REQUEST_MIC) {
            setStatus("Microphone permission is required for the agent.")
        }
    }

    private fun buildLayout(): View {
        val root =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 48, 48, 48)
                    setBackgroundColor(Color.BLACK)
                    // Any touch counts as a user input event in the system's view, which grants a
                    // brief
                    // window during which the app can start a foreground-microphone service from
                    // API 31+
                    // even though the device's launcher never gives our window focus. Wire the root
                    // to
                    // trigger a service start so the wearer can boot the agent with a single tap.
                    setOnTouchListener { _, _ ->
                        maybeStartAgent()
                        false
                    }
                }

        statusView =
                TextView(this).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    text = "HassGlass"
                }
        root.addView(statusView, lp(0, bottom = 24))

        hudContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(28, 24, 28, 24)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#E6090F18"))
                                setStroke(3, Color.parseColor("#4FC3F7"))
                                cornerRadius = 18f
                            }
                }
        hudTitleView =
                TextView(this).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                }
        hudBodyView =
                TextView(this).apply {
                    setTextColor(Color.parseColor("#D6E0EB"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                }
        hudContainer.addView(hudTitleView, lp(MATCH_PARENT, bottom = 8))
        hudContainer.addView(hudBodyView, lp(MATCH_PARENT))
        root.addView(hudContainer, lp(MATCH_PARENT, bottom = 24))

        codeView =
                TextView(this).apply {
                    setTextColor(Color.parseColor("#00E5FF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                }
        root.addView(codeView, lp(MATCH_PARENT, bottom = 16))

        pairButton = focusableButton("Pair with Home Assistant") { startPairing() }
        root.addView(pairButton, lp(MATCH_PARENT, bottom = 16))

        cancelPairButton =
                focusableButton("Cancel pairing") {
                    stopPairing()
                    renderState()
                }
                        .apply { visibility = View.GONE }
        root.addView(cancelPairButton, lp(MATCH_PARENT, bottom = 16))

        startAgentButton = focusableButton("Start agent") { maybeStartAgent() }
        root.addView(startAgentButton, lp(MATCH_PARENT, bottom = 16))

        clearPairingButton = focusableButton("Clear pairing") { onClearPairingClicked() }
        root.addView(clearPairingButton, lp(MATCH_PARENT))

        return root
    }

    private fun focusableButton(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                background = focusableBackground()
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(32, 24, 32, 24)
                setOnClickListener { onClick() }
            }

    private fun focusableBackground(): StateListDrawable {
        val focused =
                GradientDrawable().apply {
                    setColor(Color.parseColor("#003B42"))
                    setStroke(8, Color.parseColor("#00E5FF"))
                    cornerRadius = 14f
                }
        val normal =
                GradientDrawable().apply {
                    setColor(Color.parseColor("#0E2628"))
                    setStroke(2, Color.parseColor("#1F4044"))
                    cornerRadius = 14f
                }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun lp(width: Int = WRAP_CONTENT, bottom: Int = 0): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(if (width == 0) MATCH_PARENT else width, WRAP_CONTENT).apply {
                bottomMargin = bottom
            }

    private fun renderState() {
        val paired = settingsStore.loadPairedSettings()
        val online = hasLocalNetwork()
        val initialFocus: View
        if (paired == null) {
            clearPairingButton.visibility = View.GONE
            startAgentButton.visibility = View.GONE
            when {
                pairingInFlight -> {
                    pairButton.visibility = View.GONE
                    cancelPairButton.visibility = View.VISIBLE
                    codeView.visibility = View.VISIBLE
                    initialFocus = cancelPairButton
                }
                !online -> {
                    setStatus(
                            "No Wi-Fi connection. Join the same network as Home Assistant, then tap Pair."
                    )
                    codeView.visibility = View.GONE
                    pairButton.visibility = View.VISIBLE
                    pairButton.isEnabled = false
                    cancelPairButton.visibility = View.GONE
                    initialFocus = pairButton
                }
                else -> {
                    setStatus("Not paired. Tap Pair, then enter the code in Home Assistant.")
                    codeView.visibility = View.GONE
                    pairButton.visibility = View.VISIBLE
                    pairButton.isEnabled = true
                    cancelPairButton.visibility = View.GONE
                    initialFocus = pairButton
                }
            }
        } else {
            setStatus(
                    if (online) "Paired with ${paired.haBaseUrl}. Agent will run while Wi-Fi is up."
                    else "Paired. Waiting for Wi-Fi…",
            )
            codeView.visibility = View.GONE
            pairButton.visibility = View.GONE
            cancelPairButton.visibility = View.GONE
            startAgentButton.visibility = View.VISIBLE
            clearPairingButton.visibility = View.VISIBLE
            initialFocus = startAgentButton
        }
        initialFocus.post { initialFocus.requestFocus() }
    }

    private fun renderHud(card: HudCardEnvelope?) {
        if (card == null) {
            hudContainer.visibility = View.GONE
            return
        }
        val fields = card.card.fields
        hudTitleView.text =
                fields["title"]
                        ?: fields["text"] ?: fields["label"] ?: card.card.kind.replace('_', ' ')
        hudBodyView.text =
                listOfNotNull(
                                fields["subtitle"],
                                fields["body"],
                                fields["artist"],
                                fields["items_0"],
                                fields["items_1"],
                                fields["items_2"],
                                fields["items_3"],
                        )
                        .joinToString("\n")
        hudBodyView.visibility = if (hudBodyView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        hudContainer.visibility = View.VISIBLE
    }

    private fun hasLocalNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun startPairing() {
        if (pairingInFlight) return
        if (!hasLocalNetwork()) {
            setStatus(
                    "No Wi-Fi connection. Join the same network as Home Assistant, then tap Pair."
            )
            renderState()
            return
        }
        val identity = currentIdentity()
        val code = PairingCodeGenerator.generate()
        pendingCode = code
        pairingInFlight = true
        codeView.text = code
        setStatus("Searching for Home Assistant on the network…")
        renderState()
        Log.i(TAG, "pair: started code=$code device_id=${identity.deviceId}")
        advertiser.advertise(identity, code)
        discoveryHandle =
                discovery.discover(
                        object : HomeAssistantDiscovery.Listener {
                            override fun onDiscovered(result: HomeAssistantDiscovery.Discovered) {
                                val baseUrl = result.baseUrl
                                if (baseUrl == null) {
                                    Log.w(
                                            TAG,
                                            "pair: HA at ${result.host}:${result.port} did not advertise an HTTPS " +
                                                    "internal_url/base_url; refusing cleartext fallback",
                                    )
                                    mainHandler.post {
                                        if (!pairingInFlight || pendingCode != code) return@post
                                        pairingInFlight = false
                                        stopPairing()
                                        setStatus(
                                                "HA didn't advertise an HTTPS internal_url. Set internal_url in HA " +
                                                        "configuration.yaml and retry.",
                                        )
                                        renderState()
                                    }
                                    return
                                }
                                Log.i(
                                        TAG,
                                        "pair: discovered HA at $baseUrl (from ${result.source})"
                                )
                                mainHandler.post {
                                    setStatus("Found HA. Enter $code in Home Assistant.")
                                }
                                submitClaim(baseUrl, code, identity)
                            }

                            override fun onError(message: String) {
                                Log.w(TAG, "pair: discovery error: $message")
                                mainHandler.post { setStatus("Discovery error: $message") }
                            }
                        }
                )
    }

    private fun submitClaim(baseUrl: String, code: String, identity: AgentIdentity) {
        if (!pairingInFlight) return
        pairingExecutor.execute {
            Log.i(TAG, "pair: POST ${baseUrl.trimEnd('/')}/api/hassglass/pair code=$code")
            val client = PairingClient(OkHttpPairingTransport(), settingsStore)
            val startedAt = System.currentTimeMillis()
            val result = runCatching { client.claim(baseUrl, code, identity) }
            val elapsed = System.currentTimeMillis() - startedAt
            mainHandler.post {
                if (!pairingInFlight || pendingCode != code) {
                    Log.i(TAG, "pair: result ignored (no longer in-flight or code rotated)")
                    return@post
                }
                pairingInFlight = false
                stopPairing()
                result
                        .onSuccess {
                            Log.i(TAG, "pair: success after ${elapsed}ms device_id=${it.deviceId}")
                            setStatus("Paired with ${it.haBaseUrl}.")
                            renderState()
                            maybeStartAgent()
                        }
                        .onFailure { error ->
                            Log.w(TAG, "pair: failed after ${elapsed}ms", error)
                            setStatus(
                                    "Pairing failed: ${error.message ?: error.javaClass.simpleName}"
                            )
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
    private fun currentIdentity(): AgentIdentity =
            AgentIdentity(
                    deviceId = deviceId,
                    serial = Build.SERIAL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
                                    ?: deviceId,
                    firmware = Build.DISPLAY ?: Build.VERSION.RELEASE,
                    agentVersion = AGENT_VERSION,
                    name = Build.MODEL ?: "HassGlass",
            )

    /**
     * Start the foreground agent service when we're paired and online. Prompts for the microphone
     * permission once if it hasn't been granted — startForeground requires it to promote the
     * service under FOREGROUND_SERVICE_TYPE_MICROPHONE.
     */
    private fun maybeStartAgent() {
        if (pairingInFlight) return
        if (settingsStore.loadPairedSettings() == null) return
        if (!hasLocalNetwork()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        Log.i(TAG, "starting agent service (paired, online, mic permission granted)")
        val result = AgentServiceStarter(this).start()
        when (result) {
            AgentServiceStartResult.STARTED -> setStatus("Agent service starting.")
            AgentServiceStartResult.FAILED_FOREGROUND_START -> {
                Log.w(TAG, "agent service start rejected")
                setStatus("Tap Start agent to grant Android's foreground-start window.")
            }
            else -> {
                Log.w(TAG, "agent service start failed: $result")
                setStatus("Failed to start agent service.")
            }
        }
        requestBatteryOptimizationExemptionIfNeeded()
        requestOverlayPermissionIfNeeded()
    }

    /**
     * Ask the OS to stop killing HassGlass for "app idle". On Rokid's Android, startForeground()
     * doesn't set isForeground=true in the service record, so stopInBackgroundLocked() would stop
     * the service after ~1 minute. Being on the power-save whitelist bypasses that check entirely.
     */
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        Log.i(TAG, "requesting battery optimization exemption")
        try {
            startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open battery optimization settings", e)
        }
    }

    /**
     * Ask the user to grant the SYSTEM_ALERT_WINDOW permission so HUD cards can be displayed as an
     * overlay on top of any app (including the Rokid home screen).
     */
    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this)) return
        Log.i(TAG, "requesting overlay (SYSTEM_ALERT_WINDOW) permission")
        try {
            startActivity(
                    Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                    )
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open overlay permission settings", e)
        }
    }

    private fun onClearPairingClicked() {
        settingsStore.clearPairing()
        stopService(Intent(this, HassGlassAgentService::class.java))
        renderState()
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    companion object {
        private const val TAG = "HassGlass"
        private const val REQUEST_MIC = 1001
        private const val KEY_DEVICE_ID = "device_uuid"
        private const val AGENT_VERSION = "0.1.0"
    }
}
