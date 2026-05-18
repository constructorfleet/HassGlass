package dev.hassglass.agent.input

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CapturingConnection : WsConnection {
    val sentText: MutableList<String> = mutableListOf()
    override fun sendText(text: String) {
        sentText.add(text)
    }
    override fun sendBytes(bytes: ByteArray) = Unit
    override fun close() = Unit
}

/**
 * Feed [interpreter] a sequence of samples that traces `pitchAmplitude *
 * sin(2π·freqHz·t)` on the pitch axis (or yaw, depending on which lambda
 * the test supplies) at 100 Hz over `durationMs` milliseconds.
 */
private fun sweep(
    durationMs: Long,
    freqHz: Double,
    pitchAmplitude: Double,
    yawAmplitude: Double,
    onSample: (ImuSample) -> Unit,
) {
    val sampleRateHz = 100
    val sampleIntervalMs = 1_000L / sampleRateHz
    var t = 0L
    while (t <= durationMs) {
        val phase = 2.0 * Math.PI * freqHz * (t / 1_000.0)
        val sine = sin(phase)
        onSample(
            ImuSample(
                timestampMs = t,
                gyroPitchDps = pitchAmplitude * sine,
                gyroYawDps = yawAmplitude * sine,
            ),
        )
        t += sampleIntervalMs
    }
}

class HeadPoseInterpreterTest {
    @Test
    fun `pitch-axis sinusoid triggers NOD`() {
        val events: MutableList<HeadPoseGesture> = mutableListOf()
        val interpreter = HeadPoseInterpreter(events::add)
        sweep(
            durationMs = 1_500,
            freqHz = 2.0,
            pitchAmplitude = 120.0,
            yawAmplitude = 0.0,
            interpreter::onSample,
        )
        assertTrue(HeadPoseGesture.NOD in events, "expected NOD, got $events")
    }

    @Test
    fun `yaw-axis sinusoid triggers SHAKE`() {
        val events: MutableList<HeadPoseGesture> = mutableListOf()
        val interpreter = HeadPoseInterpreter(events::add)
        sweep(
            durationMs = 1_500,
            freqHz = 2.0,
            pitchAmplitude = 0.0,
            yawAmplitude = 120.0,
            interpreter::onSample,
        )
        assertTrue(HeadPoseGesture.SHAKE in events, "expected SHAKE, got $events")
    }

    @Test
    fun `low-amplitude noise does not trigger`() {
        val events: MutableList<HeadPoseGesture> = mutableListOf()
        val interpreter = HeadPoseInterpreter(events::add)
        // Amplitude well under peakDps threshold — should be ignored.
        sweep(
            durationMs = 1_500,
            freqHz = 2.0,
            pitchAmplitude = 20.0,
            yawAmplitude = 20.0,
            interpreter::onSample,
        )
        assertEquals(emptyList(), events)
    }

    @Test
    fun `refractory window prevents back-to-back triggers from one motion`() {
        val events: MutableList<HeadPoseGesture> = mutableListOf()
        val interpreter = HeadPoseInterpreter(events::add)
        // 3-second sweep — long enough to potentially register twice; the
        // refractory window should clamp it to one event per ~600ms.
        sweep(
            durationMs = 3_000,
            freqHz = 2.0,
            pitchAmplitude = 120.0,
            yawAmplitude = 0.0,
            interpreter::onSample,
        )
        // Three full seconds of nodding at 2 Hz could produce at most
        // 1 + ceil((3000 - first_trigger) / 600) ≈ 5 events; the value
        // we care about is "fewer than the number of physical peaks" —
        // i.e., the refractory actually fires.
        assertTrue(events.size in 2..6, "expected 2..6 events, got ${events.size}")
    }

    @Test
    fun `bridge emits input_gesture frames with the right kind`() {
        val conn = CapturingConnection()
        val bridge = HeadPoseGestureBridge(conn)

        bridge.emit(HeadPoseGesture.NOD)
        bridge.emit(HeadPoseGesture.SHAKE)

        val msgs = conn.sentText.map(ProtocolCodec::decodeMessage)
        assertEquals(MessageType.INPUT_GESTURE, msgs[0].type)
        assertEquals(MessageType.INPUT_GESTURE, msgs[1].type)
        assertEquals("head_nod", msgs[0].fields["kind"]!!.toString().trim('"'))
        assertEquals("head_shake", msgs[1].fields["kind"]!!.toString().trim('"'))
        assertEquals("pitch", msgs[0].fields["axis"]!!.toString().trim('"'))
        assertEquals("yaw", msgs[1].fields["axis"]!!.toString().trim('"'))
    }
}
