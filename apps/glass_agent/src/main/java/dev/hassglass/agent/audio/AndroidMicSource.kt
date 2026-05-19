package dev.hassglass.agent.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Shipping [MicSource] backed by Android `AudioRecord`.
 *
 * This captures 16 kHz mono PCM-16 frames using the standard Android mic path so the existing
 * [MicSession] / WebSocket pipeline is usable on stock Android builds today. The privileged CXR-L
 * route that requests Rokid's AEC-enhanced mic path remains a follow-up and stays isolated in
 * [CxrlMicSource].
 *
 * `start` is intentionally synchronous: it opens a fresh AudioRecord session for the utterance,
 * runs the blocking read loop on the caller's thread, and closes the native resource when the loop
 * exits.
 */
class AndroidMicSource
internal constructor(
        private val sessionFactory: MicCaptureSessionFactory = AndroidMicCaptureSessionFactory(),
        private val frameBytes: Int = FRAME_BYTES_20MS_16K_MONO,
) : MicSource {

    @Volatile private var running = false

    @Volatile private var activeSession: MicCaptureSession? = null

    override fun start(onFrame: (ByteArray) -> Unit) {
        if (running) {
            return
        }

        val session = sessionFactory.open()
        activeSession = session
        running = true
        val buffer = ByteArray(frameBytes)

        try {
            session.start()
            while (running) {
                val read = session.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    return
                }
                onFrame(buffer.copyOf(read))
            }
        } finally {
            running = false
            activeSession = null
            session.close()
        }
    }

    override fun stop() {
        running = false
        activeSession?.stop()
    }

    private companion object {
        const val SAMPLE_RATE_16K = 16_000
        const val FRAME_BYTES_20MS_16K_MONO = 640
    }
}

internal interface MicCaptureSessionFactory {
    fun open(): MicCaptureSession
}

internal interface MicCaptureSession {
    fun start()

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun stop()

    fun close()
}

internal class AndroidMicCaptureSessionFactory(
        private val sampleRate: Int = 16_000,
        private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) : MicCaptureSessionFactory {

    @SuppressLint("MissingPermission")
    override fun open(): MicCaptureSession {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        val frameBytes = 2 * (sampleRate / 50)
        val bufferSize =
                if (minBuffer > 0) {
                    maxOf(minBuffer, frameBytes)
                } else {
                    frameBytes
                }

        val audioRecord =
                AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                                AudioFormat.Builder()
                                        .setSampleRate(sampleRate)
                                        .setChannelMask(channelMask)
                                        .setEncoding(encoding)
                                        .build(),
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()

        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize"
        }

        return AndroidMicCaptureSession(audioRecord)
    }
}

internal class AndroidMicCaptureSession(
        private val audioRecord: AudioRecord,
) : MicCaptureSession {

    private var started = false

    override fun start() {
        if (started) {
            return
        }
        audioRecord.startRecording()
        started = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            audioRecord.read(buffer, offset, length, AudioRecord.READ_BLOCKING)

    override fun stop() {
        if (!started) {
            return
        }
        try {
            audioRecord.stop()
        } catch (_: IllegalStateException) {
            // Another thread may already have torn the session down.
        }
        started = false
    }

    override fun close() {
        stop()
        audioRecord.release()
    }
}
