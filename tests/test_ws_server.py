"""Tests for Glass Agent WebSocket text-frame handling."""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import MagicMock

from custom_components.hassglass.device import DeviceBus, DeviceRecord, GlassesRuntime
from custom_components.hassglass.protocol import MessageType, encode_message
from custom_components.hassglass.ws_server import _handle_text


async def test_audio_start_launches_assist_pipeline(monkeypatch: Any) -> None:
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
    bridge = MagicMock()
    bus = DeviceBus()
    created_tasks: list[asyncio.Task[None]] = []

    async def fake_run_assist_pipeline(*args: Any, **kwargs: Any) -> None:
        assert args == (hub.hass, hub, bridge, runtime)
        assert kwargs == {"wake_word_phrase": "hey rokid"}

    hub.hass.async_create_task.side_effect = lambda coro: created_tasks.append(
        asyncio.create_task(coro)
    )
    monkeypatch.setattr(
        "custom_components.hassglass.ws_server.run_assist_pipeline",
        fake_run_assist_pipeline,
    )

    await _handle_text(
        hub,
        bridge,
        runtime,
        bus,
        encode_message(MessageType.AUDIO_START, trigger="wake_word", phrase="hey rokid"),
    )
    await asyncio.gather(*created_tasks)

    assert len(created_tasks) == 1
