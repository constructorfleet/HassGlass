package dev.hassglass.agent.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val AUDIO_HEADER_BYTES = 8
private const val MAX_UINT32 = 0xffffffffL

object ProtocolCodec {
    private val json = Json { encodeDefaults = false }

    fun encodeAudioFrame(channel: AudioChannel, seq: Long, payload: ByteArray): ByteArray {
        if (seq !in 0..MAX_UINT32) {
            throw ProtocolException("seq $seq out of range")
        }

        val output = ByteBuffer
            .allocate(AUDIO_HEADER_BYTES + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(channel.id)
        output.putInt(seq.toInt())
        output.put(payload)
        return output.array()
    }

    fun decodeAudioFrame(raw: ByteArray): AudioFrame {
        if (raw.size < AUDIO_HEADER_BYTES) {
            throw ProtocolException("audio frame too short: ${raw.size} bytes")
        }

        val input = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val channel = AudioChannel.fromId(input.int)
        val seq = input.int.toLong() and MAX_UINT32
        val payload = raw.copyOfRange(AUDIO_HEADER_BYTES, raw.size)
        return AudioFrame(channel, seq, payload)
    }

    fun encodeMessage(type: MessageType, fields: Map<String, JsonElement> = emptyMap()): String {
        val payload = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type.wireName))
        payload.putAll(fields)
        return json.encodeToString(JsonObject.serializer(), JsonObject(payload))
    }

    fun decodeMessage(raw: String): ProtocolMessage {
        val obj = json.parseToJsonElement(raw).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
            ?: throw ProtocolException("message missing 'type' field")
        return ProtocolMessage(
            type = MessageType.fromWireName(type),
            fields = obj.filterKeys { it != "type" },
        )
    }
}
