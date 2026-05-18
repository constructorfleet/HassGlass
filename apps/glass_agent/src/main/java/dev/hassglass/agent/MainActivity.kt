package dev.hassglass.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.hassglass.agent.pairing.AgentIdentity
import dev.hassglass.agent.pairing.LoggingPairingCodeRenderer
import dev.hassglass.agent.pairing.OkHttpPairingTransport
import dev.hassglass.agent.pairing.PairingClient
import dev.hassglass.agent.pairing.PairingFlowController
import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.SharedPreferencesKeyValueStore
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Minimal on-glasses launcher: surfaces pairing entry when no settings exist, otherwise lets the
 * user start/stop the foreground agent service.
 */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pairingExecutor = Executors.newSingleThreadExecutor()

    private lateinit var preferences: SharedPreferences
    private lateinit var settingsStore: AgentSettingsStore
    private lateinit var deviceId: String

    private lateinit var statusView: TextView
    private lateinit var hostInput: EditText
    private lateinit var pairButton: Button
    private lateinit var codeView: TextView
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

        setContentView(buildLayout())
        renderState()
    }

    override fun onDestroy() {
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

        hostInput = EditText(this).apply {
            hint = "https://homeassistant.local:8123"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(hostInput, lp(WRAP_CONTENT, bottom = 16))

        pairButton = Button(this).apply {
            text = "Pair with Home Assistant"
            setOnClickListener { onPairClicked() }
        }
        root.addView(pairButton, lp(WRAP_CONTENT, bottom = 16))

        codeView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(codeView, lp(WRAP_CONTENT, bottom = 16))

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
            setStatus("Not paired. Enter your Home Assistant base URL, then tap Pair.")
            hostInput.visibility = View.VISIBLE
            pairButton.visibility = View.VISIBLE
            startButton.visibility = View.GONE
            stopButton.visibility = View.GONE
            clearPairingButton.visibility = View.GONE
        } else {
            setStatus("Paired with ${paired.haBaseUrl}.")
            hostInput.visibility = View.GONE
            pairButton.visibility = View.GONE
            codeView.visibility = View.GONE
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.VISIBLE
            clearPairingButton.visibility = View.VISIBLE
        }
    }

    private fun onPairClicked() {
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            setStatus("Enter the Home Assistant base URL first.")
            return
        }
        val identity = AgentIdentity(
            deviceId = deviceId,
            serial = Build.SERIAL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: deviceId,
            firmware = Build.DISPLAY ?: Build.VERSION.RELEASE,
            agentVersion = AGENT_VERSION,
            name = Build.MODEL ?: "HassGlass",
        )
        val flow = PairingFlowController(LoggingPairingCodeRenderer())
        val code = flow.start()
        codeView.text = code
        codeView.visibility = View.VISIBLE
        setStatus("Pairing code: $code — enter it in Home Assistant.")
        pairButton.isEnabled = false

        pairingExecutor.execute {
            val pairingClient = PairingClient(OkHttpPairingTransport(), settingsStore)
            val result = runCatching { pairingClient.claim(host, code, identity) }
            mainHandler.post {
                pairButton.isEnabled = true
                result.onSuccess {
                    setStatus("Paired with ${it.haBaseUrl}.")
                    renderState()
                }.onFailure { error ->
                    setStatus("Pairing failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

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
