package dev.hassglass.agent.audio

/**
 * Producer of PCM mic frames.
 *
 * The contract is intentionally narrow: start() turns the mic on, the
 * implementation calls back with raw bytes (16 kHz mono PCM in production,
 * arbitrary in tests), and stop() turns it off. Lifecycle is one-shot per
 * utterance — a fresh start() call gets a fresh stream.
 *
 * Implementations:
 *  - `CxrlMicSource` (hardware-gated) — wraps the YodaOS AIDL service that
 *    binds the privileged mic path through the NXP RT600 AEC chain.
 *  - `FakeMicSource` (tests + dev workflow) — emits a canned byte stream
 *    so the WS/Assist path is exercisable without real glasses.
 */
interface MicSource {
    /** Start capturing. `onFrame` is invoked for each PCM chunk produced. */
    fun start(onFrame: (ByteArray) -> Unit)

    /** Stop capturing. After this returns, `onFrame` will not be invoked. */
    fun stop()
}

/**
 * Test / dev MicSource. Pre-loaded with a list of byte chunks; calling
 * `start` synchronously emits them one by one. Useful for replay tests and
 * for running the agent against a recorded session in CI.
 */
class FakeMicSource(private val chunks: List<ByteArray>) : MicSource {
    @Volatile
    private var running = false

    override fun start(onFrame: (ByteArray) -> Unit) {
        running = true
        for (chunk in chunks) {
            if (!running) {
                return
            }
            onFrame(chunk)
        }
    }

    override fun stop() {
        running = false
    }
}

/**
 * Placeholder for the CXR-L–backed implementation. The real version will:
 *  1. Bind via Android `Context.bindService` to the CXR-L AI service AIDL
 *     declared in `aidl/dev/hassglass/agent/IRokidMic.aidl` (added once
 *     the SDK headers are checked in alongside the project).
 *  2. Call `requestMicPath(MicProfile.AEC_VOICE)` so audio routes through
 *     the NXP RT600 echo-cancel chain.
 *  3. Fan AudioRecord PCM (16 kHz mono, 20 ms frames) into `onFrame`.
 *
 * Lives here so the orchestration in [MicSession] is hardware-agnostic;
 * swap this for [FakeMicSource] in tests / when running off-glasses.
 */
class CxrlMicSource : MicSource {
    override fun start(onFrame: (ByteArray) -> Unit) {
        error("CxrlMicSource is hardware-gated; see KDoc.")
    }

    override fun stop() {
        // No-op until hardware binding lands.
    }
}
