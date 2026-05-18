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
 * Foreground service placeholder for the on-glasses agent.
 *
 * The concrete Rokid CXR-L binding, wake-word path, and notification channel are
 * deliberately deferred until the protocol and WebSocket skeleton are stable
 * and testable without hardware.
 */
class HassGlassAgentService : Service() {
    private var controller: AgentController? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val settingsStore = AgentSettingsStore(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE),
            ),
        )
        controller = AgentController(
            settingsStore = settingsStore,
            connector = WsClient(OkHttpWsTransport()),
            telemetryProvider = { TelemetrySnapshot() },
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
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val PREFERENCES_NAME = "hassglass_agent"
    }
}
