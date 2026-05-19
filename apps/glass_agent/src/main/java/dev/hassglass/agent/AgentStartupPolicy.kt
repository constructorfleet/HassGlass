package dev.hassglass.agent

object AgentStartupPolicy {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"
    const val ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT"

    private val startupActions =
            setOf(
                    ACTION_BOOT_COMPLETED,
                    ACTION_LOCKED_BOOT_COMPLETED,
                    ACTION_USER_PRESENT,
            )

    fun shouldStart(
            action: String?,
            hasPairedSettings: Boolean,
            hasRecordAudioPermission: Boolean,
    ): Boolean =
            action in startupActions &&
                    hasPairedSettings &&
                    hasRecordAudioPermission
}
