"""Runtime model for a paired pair of Rokid glasses."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from .protocol import (
    AudioChannel,
    AudioFrame,
    Message,
    MessageType,
    encode_audio_frame,
    encode_message,
)

if TYPE_CHECKING:
    from aiohttp.web import WebSocketResponse

_LOGGER = logging.getLogger(__name__)


@dataclass(slots=True)
class DeviceRecord:
    """Persistent record of a paired pair of glasses.

    Stored in `ConfigEntry.data["devices"][device_id]`. Mirrors what the Glass
    Agent sent during pairing plus the token we issued back.
    """

    device_id: str
    serial: str
    firmware: str
    agent_version: str
    token: str
    name: str
    pipeline_id: str | None = None
    wake_word_enabled: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "device_id": self.device_id,
            "serial": self.serial,
            "firmware": self.firmware,
            "agent_version": self.agent_version,
            "token": self.token,
            "name": self.name,
            "pipeline_id": self.pipeline_id,
            "wake_word_enabled": self.wake_word_enabled,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> DeviceRecord:
        return cls(
            device_id=data["device_id"],
            serial=data["serial"],
            firmware=data["firmware"],
            agent_version=data["agent_version"],
            token=data["token"],
            name=data["name"],
            pipeline_id=data.get("pipeline_id"),
            wake_word_enabled=data.get("wake_word_enabled", True),
        )


@dataclass(slots=True)
class TelemetryState:
    """Most recent telemetry frame for a device."""

    battery_pct: int | None = None
    charging: bool | None = None
    rssi_dbm: int | None = None
    mic_level: float | None = None
    worn: bool | None = None

    def update(self, payload: dict[str, Any]) -> None:
        if "battery_pct" in payload:
            self.battery_pct = int(payload["battery_pct"])
        if "charging" in payload:
            self.charging = bool(payload["charging"])
        if "rssi_dbm" in payload:
            self.rssi_dbm = int(payload["rssi_dbm"])
        if "mic_level" in payload:
            self.mic_level = float(payload["mic_level"])
        if "worn" in payload:
            self.worn = bool(payload["worn"])


@dataclass(slots=True)
class GlassesRuntime:
    """Live state for one connected device.

    Lifetime is bounded by the WebSocket session; on disconnect the runtime is
    torn down but the `DeviceRecord` (persistent) remains in the config entry.
    """

    record: DeviceRecord
    ws: WebSocketResponse
    telemetry: TelemetryState = field(default_factory=TelemetryState)
    current_card_id: str | None = None
    last_intent: str | None = None
    listening: bool = False
    connected: bool = True
    audio_out_seq: int = 0

    async def send_message(self, msg_type: MessageType, **fields: Any) -> None:
        """Send a control-plane JSON frame to the agent."""
        if not self.connected or self.ws.closed:
            _LOGGER.debug("send_message dropped (disconnected): %s", msg_type)
            return
        try:
            await self.ws.send_str(encode_message(msg_type, **fields))
        except (ConnectionResetError, RuntimeError):
            _LOGGER.warning("send_message failed for %s", self.record.device_id)
            self.connected = False

    async def send_audio(self, channel: AudioChannel, payload: bytes) -> None:
        """Send an audio frame to the agent."""
        if not self.connected or self.ws.closed:
            return
        seq = self.audio_out_seq
        self.audio_out_seq = (self.audio_out_seq + 1) & 0xFFFFFFFF
        try:
            await self.ws.send_bytes(encode_audio_frame(channel, seq, payload))
        except (ConnectionResetError, RuntimeError):
            self.connected = False


@dataclass(slots=True)
class IncomingFrame:
    """Either a decoded control-plane message or an audio frame."""

    message: Message | None = None
    audio: AudioFrame | None = None


class DeviceBus:
    """Per-device fan-out of incoming frames.

    Multiple subsystems (audio bridge, telemetry coordinator, HUD ack tracker)
    want to react to inbound traffic. The bus lets each subscribe without the
    WS reader needing to know about them.
    """

    def __init__(self) -> None:
        self._subscribers: list[asyncio.Queue[IncomingFrame]] = []

    def subscribe(self) -> asyncio.Queue[IncomingFrame]:
        queue: asyncio.Queue[IncomingFrame] = asyncio.Queue(maxsize=256)
        self._subscribers.append(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[IncomingFrame]) -> None:
        if queue in self._subscribers:
            self._subscribers.remove(queue)

    def publish(self, frame: IncomingFrame) -> None:
        for queue in self._subscribers:
            if queue.full():
                # Drop oldest — slow consumer must not stall the WS reader.
                with contextlib.suppress(asyncio.QueueEmpty):
                    queue.get_nowait()
            queue.put_nowait(frame)
