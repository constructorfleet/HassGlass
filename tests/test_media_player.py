"""Tests for the glasses media_player entity (TTS / announce target)."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest
from homeassistant.components.media_player.const import MediaPlayerState

from custom_components.hassglass.device import DeviceRecord, GlassesRuntime
from custom_components.hassglass.media_player import GlassesMediaPlayer


def _record() -> DeviceRecord:
    return DeviceRecord(
        device_id="d1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test",
    )


@pytest.fixture
def media_player() -> tuple[GlassesMediaPlayer, MagicMock, MagicMock]:
    """Return (entity, runtime_mock, relay_mock)."""
    record = _record()
    runtime = MagicMock(spec=GlassesRuntime, record=record, connected=True)
    hub = MagicMock()
    hub.get_device.return_value = record
    hub.runtime_for.return_value = runtime
    relay = MagicMock()
    relay.relay = AsyncMock()
    entity = GlassesMediaPlayer(hub, relay, "d1")
    return entity, runtime, relay


async def test_play_media_streams_through_relay(
    media_player: tuple[GlassesMediaPlayer, MagicMock, MagicMock],
) -> None:
    entity, runtime, relay = media_player
    await entity.async_play_media("music", "http://example.com/tts.wav")
    relay.relay.assert_awaited_once()
    runtime_arg, payload = relay.relay.await_args.args
    assert runtime_arg is runtime
    assert payload["url"] == "http://example.com/tts.wav"
    assert payload["media_type"] == "music"


async def test_play_media_no_op_when_disconnected(
    media_player: tuple[GlassesMediaPlayer, MagicMock, MagicMock],
) -> None:
    entity, runtime, relay = media_player
    runtime.connected = False
    await entity.async_play_media("music", "http://example.com/x.wav")
    relay.relay.assert_not_awaited()


async def test_state_reflects_runtime_connectivity(
    media_player: tuple[GlassesMediaPlayer, MagicMock, MagicMock],
) -> None:
    entity, runtime, _relay = media_player
    assert entity.state is MediaPlayerState.IDLE
    runtime.connected = False
    assert entity.state is MediaPlayerState.OFF


async def test_state_off_when_no_runtime() -> None:
    hub = MagicMock()
    hub.get_device.return_value = _record()
    hub.runtime_for.return_value = None
    entity = GlassesMediaPlayer(hub, MagicMock(), "d1")
    assert entity.state is MediaPlayerState.OFF
