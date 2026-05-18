"""Tests for forwarding Assist pipeline events to the Glass Agent."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any
from unittest.mock import AsyncMock, MagicMock

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.pipeline_bridge import PipelineBridge
from custom_components.hassglass.protocol import MessageType


class FakePipelineEventType(StrEnum):
    STT_END = "stt-end"
    TTS_START = "tts-start"
    TTS_END = "tts-end"


@dataclass(frozen=True)
class FakePipelineEvent:
    type: FakePipelineEventType
    data: dict[str, Any] | None = None
    timestamp: str = "2026-05-18T06:00:00+00:00"


def _make_runtime() -> MagicMock:
    record = DeviceRecord(
        device_id="rokid-1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test Glasses",
    )
    return MagicMock(
        spec=["record", "send_message", "current_card_id", "last_intent", "connected"],
        record=record,
        send_message=AsyncMock(),
        current_card_id=None,
        last_intent=None,
        connected=True,
    )


def _make_bridge() -> tuple[PipelineBridge, MagicMock, MagicMock, MagicMock]:
    runtime = _make_runtime()
    hub = MagicMock()
    hub.runtime_for.return_value = runtime
    hub.default_ttl_ms = 8000
    hud = MagicMock()
    hud.show = AsyncMock()
    relay = MagicMock()
    relay.relay = AsyncMock()
    return PipelineBridge(hub, hud, relay), runtime, hud, relay


async def test_pipeline_event_is_forwarded_to_runtime() -> None:
    bridge, runtime, _, _ = _make_bridge()
    event = FakePipelineEvent(
        FakePipelineEventType.STT_END,
        {"stt_output": {"text": "turn on the kitchen lights"}},
    )

    await bridge.handle_event("rokid-1", event)

    runtime.send_message.assert_awaited_once_with(
        MessageType.PIPELINE_EVENT,
        event="stt-end",
        state="heard",
        data={"stt_output": {"text": "turn on the kitchen lights"}},
        timestamp="2026-05-18T06:00:00+00:00",
    )
    assert runtime.last_intent == "turn on the kitchen lights"


async def test_tts_end_auto_renders_last_tts_text_as_card() -> None:
    bridge, runtime, hud, _ = _make_bridge()

    await bridge.handle_event(
        "rokid-1",
        FakePipelineEvent(FakePipelineEventType.TTS_START, {"tts_input": "Done."}),
    )
    runtime.send_message.reset_mock()

    await bridge.handle_event(
        "rokid-1",
        FakePipelineEvent(
            FakePipelineEventType.TTS_END,
            {"tts_output": {"media_id": "media-source://tts/abc"}},
        ),
    )

    runtime.send_message.assert_awaited_once()
    hud.show.assert_awaited_once_with(
        "rokid-1",
        "pipeline-reply",
        {"kind": "toast", "text": "Done.", "severity": "info"},
        priority=40,
        ttl_ms=8000,
    )


async def test_event_for_offline_device_is_ignored() -> None:
    bridge, runtime, hud, _ = _make_bridge()
    bridge._hub.runtime_for.return_value = None

    await bridge.handle_event("rokid-1", FakePipelineEvent(FakePipelineEventType.STT_END))

    runtime.send_message.assert_not_awaited()
    hud.show.assert_not_awaited()


async def test_tts_end_relays_tts_output_audio() -> None:
    bridge, runtime, _, relay = _make_bridge()
    tts_output = {"url": "https://ha.example/api/tts.wav", "mime_type": "audio/wav"}

    await bridge.handle_event(
        "rokid-1",
        FakePipelineEvent(FakePipelineEventType.TTS_END, {"tts_output": tts_output}),
    )

    relay.relay.assert_awaited_once_with(runtime, tts_output)
