package dev.hassglass.agent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.SharedPreferencesKeyValueStore

/**
 * Starts the foreground agent service on device boot when the glasses are already paired and
 * the wearer has previously granted RECORD_AUDIO. This makes "paired + Wi-Fi up" a sufficient
 * condition for the agent to be running — no app launch required.
 *
 * If RECORD_AUDIO is not granted, the receiver skips startup: the runtime permission can only
 * be granted from the foreground UI, so the wearer has to open the app once to grant. After
 * that, every subsequent boot starts the service headlessly.
 *
 * Network state isn't checked here — the WS client retries internally when Wi-Fi flaps, and we
 * want the service alive as soon as possible so it's ready when the connection comes up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val preferences = context.getSharedPreferences(
            HassGlassAgentService.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val settings = AgentSettingsStore(SharedPreferencesKeyValueStore(preferences))
        if (settings.loadPairedSettings() == null) {
            Log.i(TAG, "boot: not paired; skipping agent start")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "boot: paired but RECORD_AUDIO not granted; skipping agent start")
            return
        }
        Log.i(TAG, "boot: starting agent service")
        context.startForegroundService(Intent(context, HassGlassAgentService::class.java))
    }

    companion object {
        private const val TAG = "HassGlass"
    }
}
