package dev.hassglass.agent.input

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Sample from the on-glass IMU. Units match `ICM-4x6xx` raw output.
 *  - timestampMs: monotonic clock
 *  - gyroPitchDps: degrees-per-second around Y (nodding axis)
 *  - gyroYawDps:   degrees-per-second around Z (shaking axis)
 *
 * Roll is intentionally absent — head-tilt isn't a gesture we surface,
 * and ignoring it keeps the noise floor down.
 */
data class ImuSample(
    val timestampMs: Long,
    val gyroPitchDps: Double,
    val gyroYawDps: Double,
)

/**
 * Detected head-pose gesture.
 *
 *  - NOD: ≥3 zero-crossings on the pitch axis within `windowMs` with
 *    each peak exceeding `peakDps`.
 *  - SHAKE: same pattern on yaw.
 */
enum class HeadPoseGesture { NOD, SHAKE }

/**
 * Streaming detector. Push IMU samples into [onSample] as they arrive
 * (typically 50–100 Hz). When a nod / shake is recognized, the
 * [listener] fires once and the detector enters a short refractory
 * window so a single physical motion doesn't trigger twice.
 *
 * The implementation is intentionally cheap: a fixed-size ring buffer of
 * recent samples, zero-crossing detection, and a tiny refractory clock.
 * No FFTs, no calibration phase — head nods are not subtle.
 */
class HeadPoseInterpreter(
    private val listener: (HeadPoseGesture) -> Unit,
    private val windowMs: Long = 1_200,
    private val peakDps: Double = 80.0,
    private val minCrossings: Int = 3,
    private val refractoryMs: Long = 600,
) {
    private val samples: ArrayDeque<ImuSample> = ArrayDeque()
    private var lockedUntil: Long = 0

    fun onSample(sample: ImuSample) {
        samples.addLast(sample)
        prune(sample.timestampMs)
        if (sample.timestampMs < lockedUntil) {
            return
        }
        when {
            detect(sample.timestampMs, { it.gyroPitchDps }) -> {
                lockedUntil = sample.timestampMs + refractoryMs
                listener(HeadPoseGesture.NOD)
            }
            detect(sample.timestampMs, { it.gyroYawDps }) -> {
                lockedUntil = sample.timestampMs + refractoryMs
                listener(HeadPoseGesture.SHAKE)
            }
        }
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }

    private fun detect(nowMs: Long, axis: (ImuSample) -> Double): Boolean {
        if (samples.size < minCrossings + 1) {
            return false
        }
        var crossings = 0
        var prevSign = 0
        var peakInWindow = false
        for (sample in samples) {
            if (sample.timestampMs < nowMs - windowMs) {
                continue
            }
            val v = axis(sample)
            if (abs(v) >= peakDps) {
                peakInWindow = true
            }
            val sign = when {
                v > 5.0 -> 1
                v < -5.0 -> -1
                else -> 0
            }
            if (sign != 0 && prevSign != 0 && sign != prevSign) {
                crossings += 1
            }
            if (sign != 0) {
                prevSign = sign
            }
        }
        return peakInWindow && crossings >= minCrossings
    }
}

/**
 * Wires [HeadPoseInterpreter] events to the WS connection as
 * `input.gesture` messages whose kind matches the integration's
 * `GESTURE_EVENT_TYPES` whitelist.
 */
class HeadPoseGestureBridge(private val connection: WsConnection) {
    fun emit(gesture: HeadPoseGesture) {
        val kind = when (gesture) {
            HeadPoseGesture.NOD -> "head_nod"
            HeadPoseGesture.SHAKE -> "head_shake"
        }
        val axis = when (gesture) {
            HeadPoseGesture.NOD -> "pitch"
            HeadPoseGesture.SHAKE -> "yaw"
        }
        val fields = linkedMapOf<String, JsonElement>(
            "kind" to JsonPrimitive(kind),
            "axis" to JsonPrimitive(axis),
        )
        connection.sendText(ProtocolCodec.encodeMessage(MessageType.INPUT_GESTURE, fields))
    }
}
