package dev.hassglass.agent

import dev.hassglass.agent.audio.AndroidAudioTrackWriter
import dev.hassglass.agent.audio.AndroidMicSource
import dev.hassglass.agent.audio.AudioTrackPlaybackSink
import dev.hassglass.agent.audio.MicFrameSender
import dev.hassglass.agent.audio.MicSession
import dev.hassglass.agent.audio.MicSource
import dev.hassglass.agent.audio.TtsPlayer
import dev.hassglass.agent.hud.HudController
import dev.hassglass.agent.hud.HudRenderer
import dev.hassglass.agent.hud.LoggingHudRenderer
import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection

/**
 * Owns the live, device-side runtime pieces that hang off a WebSocket session.
 *
 * The current service does not yet host a HUD surface, so the default renderer stays on
 * [LoggingHudRenderer]. Once a surface host exists, swap in
 * `CapsHudRenderer(AndroidCapsSurface(...))` without changing the inbound WS routing or mic/TTS
 * plumbing here.
 */
class AgentRuntime(
        hudRenderer: HudRenderer = LoggingHudRenderer(),
        private val micSourceFactory: () -> MicSource = { AndroidMicSource() },
        private val playbackSink: AudioTrackPlaybackSink =
                AudioTrackPlaybackSink(AndroidAudioTrackWriter()),
) {
    private val hudController = HudController(hudRenderer)
    private val ttsPlayer = TtsPlayer(playbackSink)

    @Volatile private var micSession: MicSession? = null

    fun attachConnection(connection: WsConnection) {
        micSession = MicSession(connection, micSourceFactory(), MicFrameSender(connection))
    }

    fun detachConnection() {
        micSession?.stop()
        micSession = null
        ttsPlayer.endOfUtterance()
    }

    fun handleText(text: String) {
        val message = ProtocolCodec.decodeMessage(text)
        when (message.type) {
            MessageType.HUD_SHOW, MessageType.HUD_DISMISS, MessageType.HUD_UPDATE, ->
                    hudController.handleMessage(text)
            MessageType.PIPELINE_EVENT -> {
                val event = message.fields["event"]?.toString()?.trim('"')
                if (event == "run-end" || event == "error") {
                    ttsPlayer.endOfUtterance()
                }
            }
            else -> Unit
        }
    }

    fun handleBinary(frame: ByteArray) {
        ttsPlayer.accept(frame)
    }

    fun startPushToTalk() {
        micSession?.start(trigger = "button")
    }

    fun startWakeWordCapture(phrase: String) {
        micSession?.start(trigger = "wake_word", phrase = phrase)
    }

    fun shutdown() {
        detachConnection()
        playbackSink.release()
    }
}
