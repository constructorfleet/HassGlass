"""Relay Assist TTS media to the Glass Agent speaker channel."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING, Any

from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .protocol import AudioChannel

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant

    from .device import GlassesRuntime

type FetchBytes = Callable[[str], Awaitable[bytes]]

_DEFAULT_CHUNK_SIZE = 4096


class TtsRelay:
    """Fetch TTS media and write it to the outbound audio channel."""

    def __init__(
        self,
        hass: HomeAssistant,
        *,
        fetch_bytes: FetchBytes | None = None,
        chunk_size: int = _DEFAULT_CHUNK_SIZE,
    ) -> None:
        self._hass = hass
        self._fetch_bytes = fetch_bytes
        self._chunk_size = chunk_size

    async def relay(self, runtime: GlassesRuntime, tts_output: dict[str, Any]) -> None:
        """Relay a pipeline ``tts_output`` payload to one connected device."""
        url = tts_output.get("url")
        if not isinstance(url, str) or not url:
            return

        audio = await self._fetch(url)
        for start in range(0, len(audio), self._chunk_size):
            await runtime.send_audio(AudioChannel.TTS_DOWN, audio[start : start + self._chunk_size])

    async def _fetch(self, url: str) -> bytes:
        if self._fetch_bytes is not None:
            return await self._fetch_bytes(url)

        session = async_get_clientsession(self._hass)
        async with session.get(url) as response:
            response.raise_for_status()
            return await response.read()
