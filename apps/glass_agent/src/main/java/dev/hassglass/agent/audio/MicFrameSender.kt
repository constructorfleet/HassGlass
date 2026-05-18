package dev.hassglass.agent.audio

import dev.hassglass.agent.protocol.AudioChannel
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection

class MicFrameSender(
    private val connection: WsConnection,
) {
    private var seq = 0L

    fun send(pcmChunk: ByteArray) {
        connection.sendBytes(
            ProtocolCodec.encodeAudioFrame(
                channel = AudioChannel.MIC_UP,
                seq = seq,
                payload = pcmChunk,
            ),
        )
        seq = (seq + 1) and 0xffffffffL
    }
}
