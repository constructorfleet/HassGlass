package dev.hassglass.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import android.content.Intent

class AgentServiceStarterTest {
    @Test
    fun `start invokes starter and returns started`() {
        var called = false
        val starter = AgentServiceStarter({ Intent() }, { intent -> called = true })
        val result = starter.start()
        assertEquals(AgentServiceStartResult.STARTED, result)
        assertTrue(called)
    }

    @Test
    fun `start returns failed_foreground_start on runtime exception`() {
        val starter = AgentServiceStarter({ Intent() }, { throw RuntimeException("rejected") })
        val result = starter.start()
        assertEquals(AgentServiceStartResult.FAILED_FOREGROUND_START, result)
    }

    @Test
    fun `start returns failed on other exceptions`() {
        val starter = AgentServiceStarter({ Intent() }, { throw Exception("boom") })
        val result = starter.start()
        assertEquals(AgentServiceStartResult.FAILED, result)
    }
}
