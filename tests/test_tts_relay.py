"""Tests for relaying Assist TTS media to the Glass Agent."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, call

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.protocol import AudioChannel
from custom_components.hassglass.tts_relay import TtsRelay


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
        spec=["record", "send_audio", "connected"],
        record=record,
        send_audio=AsyncMock(),
        connected=True,
    )


async def test_tts_relay_fetches_and_chunks_audio() -> None:
    runtime = _make_runtime()

    async def fetch(url: str) -> bytes:
        assert url == "https://ha.example/api/tts.wav"
        return b"abcdefg"

    relay = TtsRelay(MagicMock(), fetch_bytes=fetch, chunk_size=3)

    await relay.relay(runtime, {"url": "https://ha.example/api/tts.wav"})

    runtime.send_audio.assert_has_awaits(
        [
            call(AudioChannel.TTS_DOWN, b"abc"),
            call(AudioChannel.TTS_DOWN, b"def"),
            call(AudioChannel.TTS_DOWN, b"g"),
        ]
    )


async def test_tts_relay_ignores_output_without_url() -> None:
    runtime = _make_runtime()

    async def fetch(url: str) -> bytes:
        raise AssertionError("fetch should not be called")

    relay = TtsRelay(MagicMock(), fetch_bytes=fetch)

    await relay.relay(runtime, {"media_id": "media-source://tts/abc"})

    runtime.send_audio.assert_not_awaited()
