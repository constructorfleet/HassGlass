package dev.hassglass.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.hassglass.agent.hud.HudOverlayWindow
import dev.hassglass.agent.network.NetworkEvent
import dev.hassglass.agent.network.NetworkMonitor
import dev.hassglass.agent.network.NetworkObserver
import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.SharedPreferencesKeyValueStore
import dev.hassglass.agent.wake.MasterAssistWakeBridge
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
    private var networkMonitor: NetworkMonitor? = null
    private var wakeBridge: MasterAssistWakeBridge? = null
    private var hudOverlayDetach: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val settingsStore =
                AgentSettingsStore(
                        SharedPreferencesKeyValueStore(
                                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE),
                        ),
                )
        val runtime = AgentRuntime()
        this.runtime = runtime
        controller =
                AgentController(
                        settingsStore = settingsStore,
                        connector =
                                WsClient(
                                        transport = OkHttpWsTransport(),
                                        onTextMessage = runtime::handleText,
                                        onBinaryFrame = runtime::handleBinary,
                                ),
                        telemetryProvider = { TelemetrySnapshot() },
                        onConnected = runtime::attachConnection,
                        onDisconnected = runtime::detachConnection,
                )

        networkMonitor =
                NetworkMonitor(
                        this,
                        NetworkObserver { event ->
                            when (event) {
                                NetworkEvent.Lost -> controller?.stop()
                                NetworkEvent.Available -> {
                                    if (worker?.isAlive != true) {
                                        worker =
                                                Thread { controller?.runUntilStopped() }.also {
                                                    it.start()
                                                }
                                    }
                                }
                            }
                        }
                )

        val bridge = MasterAssistWakeBridge(this)
        bridge.setListener { event -> runtime?.startWakeWordCapture(event.phrase) }
        wakeBridge = bridge

        hudOverlayDetach = HudOverlayWindow(this).attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        networkMonitor?.start()
        wakeBridge?.start()
        if (worker?.isAlive != true) {
            worker = Thread { controller?.runUntilStopped() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification: Notification =
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle("HassGlass agent")
                        .setContentText("Listening for Home Assistant.")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setOngoing(true)
                        .build()
        // Use the simple 2-argument form to avoid Rokid-specific rejections of the
        // 3-argument FGS type API.  The manifest still declares foregroundServiceType="microphone"
        // so the type is advertised for OS queries.
        @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel =
                NotificationChannel(
                                NOTIFICATION_CHANNEL_ID,
                                "HassGlass agent",
                                NotificationManager.IMPORTANCE_LOW,
                        )
                        .apply {
                            description = "Background voice agent session"
                            setShowBadge(false)
                        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        hudOverlayDetach?.invoke()
        hudOverlayDetach = null
        wakeBridge?.stop()
        controller?.stop()
        runtime?.shutdown()
        worker?.interrupt()
        worker = null
        networkMonitor?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFERENCES_NAME = "hassglass_agent"
        private const val NOTIFICATION_CHANNEL_ID = "hassglass_agent"
        private const val NOTIFICATION_ID = 1
    }
}
