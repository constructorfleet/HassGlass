package dev.hassglass.agent.protocol

import kotlinx.serialization.json.JsonElement

const val PROTOCOL_VERSION: Int = 1

enum class AudioChannel(val id: Int) {
    MIC_UP(0x01),
    TTS_DOWN(0x02);

    companion object {
        fun fromId(id: Int): AudioChannel =
            entries.firstOrNull { it.id == id } ?: throw ProtocolException("unknown audio channel id 0x${id.toString(16)}")
    }
}

data class AudioFrame(
    val channel: AudioChannel,
    val seq: Long,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is AudioFrame &&
            channel == other.channel &&
            seq == other.seq &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

enum class MessageType(val wireName: String) {
    HELLO("hello"),
    HELLO_ACK("hello_ack"),
    PING("ping"),
    PONG("pong"),
    TELEMETRY("telemetry"),
    AUDIO_START("audio.start"),
    AUDIO_STOP("audio.stop"),
    PIPELINE_EVENT("pipeline.event"),
    HUD_SHOW("hud.show"),
    HUD_UPDATE("hud.update"),
    HUD_DISMISS("hud.dismiss"),
    INPUT_GESTURE("input.gesture"),
    INPUT_BUTTON("input.button"),
    INPUT_HEAD_POSE("input.head_pose"),
    ERROR("error");

    companion object {
        fun fromWireName(value: String): MessageType =
            entries.firstOrNull { it.wireName == value } ?: throw ProtocolException("unknown message type: $value")
    }
}

data class ProtocolMessage(
    val type: MessageType,
    val fields: Map<String, JsonElement>,
)

class ProtocolException(message: String) : RuntimeException(message)
