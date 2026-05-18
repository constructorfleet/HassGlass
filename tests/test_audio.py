"""Tests for adapting inbound WS audio frames to Assist streams."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from custom_components.hassglass.audio import MicAudioStream, run_assist_pipeline
from custom_components.hassglass.device import (
    DeviceBus,
    DeviceRecord,
    GlassesRuntime,
    IncomingFrame,
)
from custom_components.hassglass.protocol import (
    AudioChannel,
    AudioFrame,
    Message,
    MessageType,
)


@dataclass(frozen=True)
class FakePipelineEvent:
    type: str
    data: dict[str, Any] | None = None
    timestamp: str = "2026-05-18T06:00:00+00:00"


async def test_mic_audio_stream_yields_mic_frames_until_audio_stop() -> None:
    bus = DeviceBus()
    stream = MicAudioStream(bus)
    chunks = stream.chunks()
    first_chunk = asyncio.create_task(anext(chunks))
    await asyncio.sleep(0)

    bus.publish(IncomingFrame(audio=AudioFrame(AudioChannel.MIC_UP, 1, b"abc")))

    assert await first_chunk == b"abc"

    bus.publish(IncomingFrame(message=Message(MessageType.AUDIO_STOP)))

    with pytest.raises(StopAsyncIteration):
        await anext(chunks)


async def test_mic_audio_stream_ignores_non_mic_audio_frames() -> None:
    bus = DeviceBus()
    stream = MicAudioStream(bus)
    chunks = stream.chunks()
    first_chunk = asyncio.create_task(anext(chunks))
    await asyncio.sleep(0)

    bus.publish(IncomingFrame(audio=AudioFrame(AudioChannel.TTS_DOWN, 1, b"tts")))
    bus.publish(IncomingFrame(audio=AudioFrame(AudioChannel.MIC_UP, 2, b"mic")))

    assert await first_chunk == b"mic"

    await chunks.aclose()


async def test_run_assist_pipeline_feeds_mic_stream_and_routes_events(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bus = DeviceBus()
    record = DeviceRecord(
        device_id="rokid-1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test Glasses",
    )
    runtime = MagicMock(spec=GlassesRuntime, record=record)
    hub = MagicMock()
    hub.bus_for.return_value = bus
    hub.resolved_options_for.return_value = {"pipeline_id": "preferred-pipeline"}
    bridge = MagicMock()
    bridge.handle_event = AsyncMock()

    created_tasks: list[asyncio.Task[Any]] = []
    hass = MagicMock()
    hass.config.language = "en"
    hass.async_create_task.side_effect = lambda coro: created_tasks.append(
        asyncio.create_task(coro)
    )

    captured: dict[str, Any] = {}

    async def fake_pipeline(*args: Any, **kwargs: Any) -> None:
        captured["args"] = args
        captured["kwargs"] = kwargs
        chunk_task = asyncio.create_task(anext(kwargs["stt_stream"]))
        await asyncio.sleep(0)
        bus.publish(IncomingFrame(audio=AudioFrame(AudioChannel.MIC_UP, 1, b"pcm")))
        assert await chunk_task == b"pcm"
        kwargs["event_callback"](FakePipelineEvent("stt-start"))

    monkeypatch.setattr(
        "custom_components.hassglass.audio.async_pipeline_from_audio_stream",
        fake_pipeline,
    )

    await run_assist_pipeline(hass, hub, bridge, runtime, wake_word_phrase="hey rokid")
    await asyncio.gather(*created_tasks)

    kwargs = captured["kwargs"]
    assert kwargs["pipeline_id"] == "preferred-pipeline"
    assert kwargs["wake_word_phrase"] == "hey rokid"
    assert kwargs["device_id"] == "rokid-1"
    assert kwargs["stt_metadata"].language == "en"
    bridge.handle_event.assert_awaited_once()


async def test_run_assist_pipeline_refuses_when_listening_disabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """ListeningEnabledSwitch=off cuts the mic at the integration boundary.

    audio.start arrives, run_assist_pipeline gets called, but it must NOT
    invoke async_pipeline_from_audio_stream — that's the whole point of the
    mic-privacy toggle.
    """
    record = DeviceRecord(
        device_id="rokid-1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test Glasses",
        listening_enabled=False,
    )
    runtime = MagicMock(spec=GlassesRuntime, record=record)

    pipeline_call = AsyncMock()
    monkeypatch.setattr(
        "custom_components.hassglass.audio.async_pipeline_from_audio_stream",
        pipeline_call,
    )

    await run_assist_pipeline(MagicMock(), MagicMock(), MagicMock(), runtime)
    pipeline_call.assert_not_awaited()
