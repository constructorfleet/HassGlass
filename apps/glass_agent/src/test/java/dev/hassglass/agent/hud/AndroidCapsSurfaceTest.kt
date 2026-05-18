package dev.hassglass.agent.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidCapsSurfaceTest {
    @Test
    fun `render paints the staged title subtitle and body lines`() {
        val sink = RecordingCanvasOpSink()
        val surface = AndroidCapsSurface(sink, timeProvider = { 1_000L })

        surface.execute(CapsCommand.Clear)
        surface.execute(CapsCommand.Severity(CapsCommand.SeverityLevel.WARNING))
        surface.execute(CapsCommand.Icon("weather-rainy"))
        surface.execute(CapsCommand.Title("62F"))
        surface.execute(CapsCommand.Subtitle("Rain in 20 min"))
        surface.execute(CapsCommand.Body("Carry a jacket"))
        surface.execute(CapsCommand.ListItem("milk", 0))
        surface.execute(CapsCommand.ListItem("eggs", 1))
        surface.execute(CapsCommand.TimerCountdown(61_000L, "Tea"))
        surface.execute(CapsCommand.Render)

        val ops = sink.frames.single()
        assertIs<CanvasOp.Clear>(ops[0])
        val panel = assertIs<CanvasOp.Panel>(ops[1])
        assertEquals(CapsCommand.SeverityLevel.WARNING, panel.severity)
        assertEquals("weather-rainy", panel.iconKey)
        assertEquals("62F", panel.title)
        assertEquals("Rain in 20 min", panel.subtitle)
        assertEquals(
                listOf("Carry a jacket", "1. milk", "2. eggs", "Tea: 1:00"),
                panel.lines,
        )
    }

    @Test
    fun `clear then render produces an empty frame`() {
        val sink = RecordingCanvasOpSink()
        val surface = AndroidCapsSurface(sink)

        surface.execute(CapsCommand.Title("Doorbell"))
        surface.execute(CapsCommand.Render)
        surface.execute(CapsCommand.Clear)
        surface.execute(CapsCommand.Render)

        assertEquals(2, sink.frames.size)
        assertEquals(listOf(CanvasOp.Clear), sink.frames[1])
    }
}

private class RecordingCanvasOpSink : CanvasOpSink {
    val frames: MutableList<List<CanvasOp>> = mutableListOf()

    override fun render(ops: List<CanvasOp>) {
        frames += ops
    }
}
