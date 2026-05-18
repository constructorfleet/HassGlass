"""Wire protocol for hassglass/1.

Pure module — no Home Assistant imports — so it can be unit-tested in isolation
and ported to other languages (Kotlin on the Glass Agent) by following the
schemas defined here.

Two frame kinds share one WebSocket:

* **Binary frames** — audio. 8-byte header `<channel:uint32-le><seq:uint32-le>`
  followed by payload bytes. Channels: 0x01 mic-up, 0x02 tts-down.
* **Text frames** — JSON envelopes, one message per frame. Every message has
  a `type` field; the rest of the shape is defined per-type below.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, ClassVar, Final

PROTOCOL_VERSION: Final = 1

AUDIO_HEADER_FMT: Final = "<II"
AUDIO_HEADER_SIZE: Final = struct.calcsize(AUDIO_HEADER_FMT)
_MAX_UINT32: Final = 0xFFFFFFFF
_BATTERY_PCT_MAX: Final = 100


class AudioChannel(StrEnum):
    """Logical audio channels multiplexed on the binary frame stream."""

    MIC_UP = "mic_up"
    TTS_DOWN = "tts_down"


_CHANNEL_TO_INT: Final[dict[AudioChannel, int]] = {
    AudioChannel.MIC_UP: 0x01,
    AudioChannel.TTS_DOWN: 0x02,
}
_INT_TO_CHANNEL: Final[dict[int, AudioChannel]] = {v: k for k, v in _CHANNEL_TO_INT.items()}


class MessageType(StrEnum):
    """Control-plane message types."""

    HELLO = "hello"
    HELLO_ACK = "hello_ack"
    PING = "ping"
    PONG = "pong"
    TELEMETRY = "telemetry"
    AUDIO_START = "audio.start"
    AUDIO_STOP = "audio.stop"
    PIPELINE_EVENT = "pipeline.event"
    HUD_SHOW = "hud.show"
    HUD_UPDATE = "hud.update"
    HUD_DISMISS = "hud.dismiss"
    INPUT_GESTURE = "input.gesture"
    INPUT_BUTTON = "input.button"
    INPUT_HEAD_POSE = "input.head_pose"
    ERROR = "error"


class ProtocolError(Exception):
    """Raised when a frame cannot be decoded or violates the schema."""


@dataclass(slots=True, frozen=True)
class AudioFrame:
    """Decoded audio frame: 8-byte header + opaque payload."""

    channel: AudioChannel
    seq: int
    payload: bytes


@dataclass(slots=True)
class Message:
    """A control-plane message.

    Subclasses provide a stable shape, but the generic `Message` is also used
    when round-tripping unknown types (forward compatibility).
    """

    type: MessageType
    data: dict[str, Any] = field(default_factory=dict)

    REQUIRED_FIELDS: ClassVar[tuple[str, ...]] = ()

    def to_json(self) -> str:
        payload: dict[str, Any] = {"type": self.type.value, **self.data}
        return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)


def encode_audio_frame(channel: AudioChannel, seq: int, payload: bytes) -> bytes:
    """Encode a binary audio frame."""
    if seq < 0 or seq > _MAX_UINT32:
        raise ProtocolError(f"seq {seq} out of range")
    header = struct.pack(AUDIO_HEADER_FMT, _CHANNEL_TO_INT[channel], seq)
    return header + payload


def decode_audio_frame(raw: bytes) -> AudioFrame:
    """Decode a binary audio frame."""
    if len(raw) < AUDIO_HEADER_SIZE:
        raise ProtocolError(f"audio frame too short: {len(raw)} bytes")
    channel_int, seq = struct.unpack(AUDIO_HEADER_FMT, raw[:AUDIO_HEADER_SIZE])
    if channel_int not in _INT_TO_CHANNEL:
        raise ProtocolError(f"unknown audio channel id 0x{channel_int:02x}")
    return AudioFrame(
        channel=_INT_TO_CHANNEL[channel_int],
        seq=seq,
        payload=raw[AUDIO_HEADER_SIZE:],
    )


def encode_message(msg_type: MessageType, **fields: Any) -> str:
    """Encode a control-plane message to a JSON text frame."""
    return Message(type=msg_type, data=dict(fields)).to_json()


def decode_message(raw: str) -> Message:
    """Decode a text frame into a Message.

    Unknown `type` values are rejected; unknown fields on a known type are
    preserved in `Message.data` for forward compatibility.
    """
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ProtocolError(f"invalid JSON: {exc}") from exc

    if not isinstance(obj, dict):
        raise ProtocolError(f"message must be a JSON object, got {type(obj).__name__}")

    msg_type_raw = obj.pop("type", None)
    if not isinstance(msg_type_raw, str):
        raise ProtocolError("message missing 'type' field")
    try:
        msg_type = MessageType(msg_type_raw)
    except ValueError as exc:
        raise ProtocolError(f"unknown message type: {msg_type_raw}") from exc

    return Message(type=msg_type, data=obj)


# -- Validators for the most load-bearing message shapes -----------------------
# These are intentionally narrow: only check what the runtime *relies on*. The
# broader JSON Schema in `protocol/messages.schema.json` is authoritative; this
# is the fast-path runtime guard.


def validate_hello(msg: Message) -> None:
    """Validate a hello message from the Glass Agent."""
    if msg.type is not MessageType.HELLO:
        raise ProtocolError(f"expected hello, got {msg.type.value}")
    for required in ("device_id", "serial", "firmware", "agent_version", "protocol_version"):
        if required not in msg.data:
            raise ProtocolError(f"hello missing required field: {required}")
    pv = msg.data["protocol_version"]
    if not isinstance(pv, int) or pv != PROTOCOL_VERSION:
        raise ProtocolError(
            f"unsupported protocol_version {pv}; this server speaks v{PROTOCOL_VERSION}",
        )


def validate_telemetry(msg: Message) -> None:
    """Validate a telemetry frame."""
    if msg.type is not MessageType.TELEMETRY:
        raise ProtocolError(f"expected telemetry, got {msg.type.value}")
    battery = msg.data.get("battery_pct")
    if battery is not None and not (
        isinstance(battery, int | float) and 0 <= battery <= _BATTERY_PCT_MAX
    ):
        raise ProtocolError(f"battery_pct out of range: {battery!r}")
    rssi = msg.data.get("rssi_dbm")
    if rssi is not None and not isinstance(rssi, int | float):
        raise ProtocolError(f"rssi_dbm must be numeric, got {type(rssi).__name__}")


def validate_hud_show(msg: Message) -> None:
    """Validate a hud.show command before sending it to the Glass Agent."""
    if msg.type is not MessageType.HUD_SHOW:
        raise ProtocolError(f"expected hud.show, got {msg.type.value}")
    if "id" not in msg.data or not isinstance(msg.data["id"], str):
        raise ProtocolError("hud.show requires string 'id'")
    card = msg.data.get("card")
    if not isinstance(card, dict) or "kind" not in card:
        raise ProtocolError("hud.show requires 'card' object with 'kind'")
