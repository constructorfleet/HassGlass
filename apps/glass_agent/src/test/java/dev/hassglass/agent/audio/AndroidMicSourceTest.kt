package dev.hassglass.agent.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AndroidMicSourceTest {
    @Test
    fun `start emits each positive read as its own frame and closes the session`() {
        val session =
                FakeMicCaptureSession(
                        reads =
                                listOf(
                                        "aaaa".toByteArray(),
                                        "bbbb".toByteArray(),
                                ),
                )
        val source = AndroidMicSource(FakeMicCaptureSessionFactory(session), frameBytes = 4)
        val frames = mutableListOf<ByteArray>()

        source.start(frames::add)

        assertEquals(1, session.startCalls)
        assertEquals(1, session.closeCalls)
        assertEquals(2, frames.size)
        assertContentEquals("aaaa".toByteArray(), frames[0])
        assertContentEquals("bbbb".toByteArray(), frames[1])
    }

    @Test
    fun `stop halts the read loop and stops the active session`() {
        lateinit var source: AndroidMicSource
        val session =
                FakeMicCaptureSession(
                        reads =
                                listOf(
                                        "aaaa".toByteArray(),
                                        "bbbb".toByteArray(),
                                ),
                        afterRead = { _, _ -> source.stop() },
                )
        source = AndroidMicSource(FakeMicCaptureSessionFactory(session), frameBytes = 4)

        val frames = mutableListOf<ByteArray>()
        source.start(frames::add)

        assertEquals(1, session.stopCalls)
        assertEquals(1, frames.size)
        assertContentEquals("aaaa".toByteArray(), frames.single())
    }

    @Test
    fun `non-positive read exits without spinning`() {
        val session = FakeMicCaptureSession(reads = emptyList(), terminalRead = -1)
        val source = AndroidMicSource(FakeMicCaptureSessionFactory(session), frameBytes = 4)
        var frames = 0

        source.start { frames += 1 }

        assertEquals(0, frames)
        assertEquals(1, session.readCalls)
        assertEquals(1, session.closeCalls)
    }
}

private class FakeMicCaptureSessionFactory(
        private val session: FakeMicCaptureSession,
) : MicCaptureSessionFactory {
    override fun open(): MicCaptureSession = session
}

private class FakeMicCaptureSession(
        private val reads: List<ByteArray>,
        private val terminalRead: Int = 0,
        private val afterRead: ((readCount: Int, bytesRead: Int) -> Unit)? = null,
) : MicCaptureSession {

    var startCalls = 0
    var stopCalls = 0
    var closeCalls = 0
    var readCalls = 0
    private var index = 0
    private var started = false

    override fun start() {
        startCalls += 1
        started = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(started)
        readCalls += 1
        val bytesRead =
                if (index < reads.size) {
                    val chunk = reads[index++]
                    chunk.copyInto(buffer, destinationOffset = offset, endIndex = chunk.size)
                    chunk.size
                } else {
                    terminalRead
                }
        afterRead?.invoke(readCalls, bytesRead)
        return bytesRead
    }

    override fun stop() {
        stopCalls += 1
        started = false
    }

    override fun close() {
        closeCalls += 1
        started = false
    }
}
