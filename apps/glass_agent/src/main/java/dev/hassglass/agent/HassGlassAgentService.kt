package dev.hassglass.agent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.SharedPreferencesKeyValueStore
import dev.hassglass.agent.ws.OkHttpWsTransport
import dev.hassglass.agent.ws.TelemetrySnapshot
import dev.hassglass.agent.ws.WsClient

/**
 * Foreground service for the on-glasses agent runtime.
 *
 * Today it owns the WebSocket session, TTS playback path, and the Android mic source wiring. HUD
 * messages are also routed into the runtime, but rendering still stays on the logging renderer
 * until the service grows a real surface host for [dev.hassglass.agent.hud.AndroidCapsSurface].
 */
class HassGlassAgentService : Service() {
    private var runtime: AgentRuntime? = null
    private var controller: AgentController? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val settingsStore = AgentSettingsStore(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE),
            ),
        )
        val runtime = AgentRuntime()
        this.runtime = runtime
        controller = AgentController(
            settingsStore = settingsStore,
            connector = WsClient(
                transport = OkHttpWsTransport(),
                onTextMessage = runtime::handleText,
                onBinaryFrame = runtime::handleBinary,
            ),
            telemetryProvider = { TelemetrySnapshot() },
            onConnected = runtime::attachConnection,
            onDisconnected = runtime::detachConnection,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker = Thread {
            controller?.start()
        }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        controller?.stop()
        runtime?.shutdown()
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val PREFERENCES_NAME = "hassglass_agent"
    }
}
