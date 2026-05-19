package dev.hassglass.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentStartupPolicyTest {
    @Test
    fun bootStartsWhenPairedAndAudioPermissionGranted() {
        assertTrue(
                AgentStartupPolicy.shouldStart(
                        action = AgentStartupPolicy.ACTION_BOOT_COMPLETED,
                        hasPairedSettings = true,
                        hasRecordAudioPermission = true,
                ),
        )
    }

    @Test
    fun userPresentStartsWhenPairedAndAudioPermissionGranted() {
        assertTrue(
                AgentStartupPolicy.shouldStart(
                        action = AgentStartupPolicy.ACTION_USER_PRESENT,
                        hasPairedSettings = true,
                        hasRecordAudioPermission = true,
                ),
        )
    }

    @Test
    fun startupActionDoesNotStartWhenUnpaired() {
        assertFalse(
                AgentStartupPolicy.shouldStart(
                        action = AgentStartupPolicy.ACTION_USER_PRESENT,
                        hasPairedSettings = false,
                        hasRecordAudioPermission = true,
                ),
        )
    }

    @Test
    fun startupActionDoesNotStartWithoutAudioPermission() {
        assertFalse(
                AgentStartupPolicy.shouldStart(
                        action = AgentStartupPolicy.ACTION_BOOT_COMPLETED,
                        hasPairedSettings = true,
                        hasRecordAudioPermission = false,
                ),
        )
    }

    @Test
    fun unrelatedActionDoesNotStart() {
        assertFalse(
                AgentStartupPolicy.shouldStart(
                        action = "android.intent.action.PACKAGE_ADDED",
                        hasPairedSettings = true,
                        hasRecordAudioPermission = true,
                ),
        )
    }
}
