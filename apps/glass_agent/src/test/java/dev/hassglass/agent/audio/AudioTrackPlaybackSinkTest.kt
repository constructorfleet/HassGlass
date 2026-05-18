package dev.hassglass.agent.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeAudioTrackWriter(
    /** When > 0, every write only accepts this many bytes (forces retry loop). */
    private val acceptPerCall: Int = Int.MAX_VALUE,
    /** When true, write returns -1 to simulate the writer being in an error state. */
    private val fail: Boolean = false,
) : AudioTrackWriter {
    val written: MutableList<Byte> = mutableListOf()
    var playCount: Int = 0
        private set
    var flushCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        if (fail) return -1
        val accept = minOf(length, acceptPerCall)
        for (i in offset until offset + accept) {
            written.add(pcm[i])
        }
        return accept
    }

    override fun play() { playCount += 1 }
    override fun flush() { flushCount += 1 }
    override fun release() { releaseCount += 1 }
}

class AudioTrackPlaybackSinkTest {
    @Test
    fun `raw pcm chunks pass through unchanged`() {
        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)

        sink.write(byteArrayOf(1, 2, 3, 4))
        sink.write(byteArrayOf(5, 6))

        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), writer.written)
    }

    @Test
    fun `play is called once per utterance, not on every write`() {
        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)

        sink.write(byteArrayOf(1, 2))
        sink.write(byteArrayOf(3, 4))
        sink.write(byteArrayOf(5, 6))

        assertEquals(1, writer.playCount)
    }

    @Test
    fun `flush resets so the next utterance calls play again`() {
        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)

        sink.write(byteArrayOf(1))
        sink.flush()
        sink.write(byteArrayOf(2))

        assertEquals(2, writer.playCount)
        assertEquals(1, writer.flushCount)
    }

    @Test
    fun `partial writes are retried until the chunk is fully drained`() {
        val writer = FakeAudioTrackWriter(acceptPerCall = 2)
        val sink = AudioTrackPlaybackSink(writer)

        sink.write(byteArrayOf(1, 2, 3, 4, 5))

        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), writer.written)
    }

    @Test
    fun `writer failure drops the rest of the chunk without spinning`() {
        val writer = FakeAudioTrackWriter(fail = true)
        val sink = AudioTrackPlaybackSink(writer)

        sink.write(byteArrayOf(1, 2, 3))

        // play() still fired, but no bytes were written because every write returned -1.
        assertEquals(1, writer.playCount)
        assertEquals(emptyList<Byte>(), writer.written)
    }

    @Test
    fun `wav header is stripped from the first chunk`() {
        // 44-byte RIFF/WAVE/fmt /data header followed by 4 PCM bytes.
        val header = buildWavHeader(pcmByteCount = 4)
        val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val full = header + payload

        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)
        sink.write(full)

        assertEquals(payload.toList(), writer.written)
    }

    @Test
    fun `subsequent chunks after a wav header pass through unchanged`() {
        val header = buildWavHeader(pcmByteCount = 8)
        val firstPayload = byteArrayOf(1, 2, 3, 4)
        val secondPayload = byteArrayOf(5, 6, 7, 8)

        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)
        sink.write(header + firstPayload)
        sink.write(secondPayload)

        assertEquals((firstPayload + secondPayload).toList(), writer.written)
    }

    @Test
    fun `release closes the underlying writer`() {
        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)

        assertEquals(0, writer.releaseCount)
        sink.release()
        assertEquals(1, writer.releaseCount)
    }

    @Test
    fun `non-wav bytes that happen to start with 'R' are not misidentified`() {
        val writer = FakeAudioTrackWriter()
        val sink = AudioTrackPlaybackSink(writer)

        // 'R' as first byte but no RIFF/WAVE structure.
        val notWav = byteArrayOf('R'.code.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9)
        sink.write(notWav)

        assertEquals(notWav.toList(), writer.written)
    }

    private fun buildWavHeader(pcmByteCount: Int): ByteArray {
        // Minimal valid WAV header for 16 kHz mono PCM-16.
        val header = ByteArray(44)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        writeLe32(header, 4, 36 + pcmByteCount)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        writeLe32(header, 16, 16) // fmt chunk size
        writeLe16(header, 20, 1)  // PCM
        writeLe16(header, 22, 1)  // mono
        writeLe32(header, 24, 16_000)
        writeLe32(header, 28, 32_000) // byte rate
        writeLe16(header, 32, 2)  // block align
        writeLe16(header, 34, 16) // bits per sample
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        writeLe32(header, 40, pcmByteCount)
        return header
    }

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    @Suppress("unused")
    private fun _silenceUnused() {
        assertTrue(true)
        assertFalse(false)
    }
}
