package dev.hassglass.agent

import android.content.Context
import android.content.Intent

enum class AgentServiceStartResult {
    STARTED,
    FAILED_FOREGROUND_START,
    FAILED,
}

/**
 * Helper to start the foreground agent service in a testable way.
 * Production usage: AgentServiceStarter(this).start()
 */
class AgentServiceStarter(
    private val intentFactory: () -> Intent,
    private val starter: (Intent) -> Unit,
) {
    constructor(context: Context) : this(
        { Intent(context, HassGlassAgentService::class.java) },
        { intent -> context.startForegroundService(intent) },
    )

    fun start(): AgentServiceStartResult {
        return try {
            starter(intentFactory())
            AgentServiceStartResult.STARTED
        } catch (e: RuntimeException) {
            AgentServiceStartResult.FAILED_FOREGROUND_START
        } catch (e: Exception) {
            AgentServiceStartResult.FAILED
        }
    }
}
