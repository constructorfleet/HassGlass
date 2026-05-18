"""Media player entity per glasses device — the announce / TTS target."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from homeassistant.components.media_player import MediaPlayerEntity
from homeassistant.components.media_player.browse_media import BrowseMedia
from homeassistant.components.media_player.const import (
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
)
from homeassistant.components.media_source import (
    async_resolve_media,
    is_media_source_id,
)
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.network import get_url

from .const import DOMAIN, SIGNAL_DEVICE_UPDATED
from .entity import HassGlassEntity

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant
    from homeassistant.helpers.entity_platform import AddEntitiesCallback

    from .hub import HassGlassHub
    from .tts_relay import TtsRelay


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create one media_player per paired device."""
    hub: HassGlassHub = entry.runtime_data.hub
    relay: TtsRelay = entry.runtime_data.tts_relay
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        known.update(new_devices)
        async_add_entities(
            [GlassesMediaPlayer(hub, relay, device_id) for device_id in new_devices],
        )

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_SUPPORTED = MediaPlayerEntityFeature.PLAY_MEDIA | MediaPlayerEntityFeature.BROWSE_MEDIA


class GlassesMediaPlayer(HassGlassEntity, MediaPlayerEntity):
    """Plays inbound media URLs through the device's outbound TTS channel.

    Designed primarily as an `assist_pipeline` and `tts.speak` target so
    automations can route announcements to a specific pair of glasses:

        - service: tts.speak
          target: { entity_id: tts.cloud }
          data:
            media_player_entity_id: media_player.living_room_glasses
            message: "Doorbell ringing"
    """

    _attr_has_entity_name = True
    _attr_translation_key = "media_player"
    _attr_supported_features = _SUPPORTED
    _attr_media_content_type = MediaType.MUSIC

    def __init__(
        self,
        hub: HassGlassHub,
        relay: TtsRelay,
        device_id: str,
    ) -> None:
        super().__init__(hub, device_id)
        self._relay = relay
        self._attr_unique_id = f"{DOMAIN}_{device_id}_media_player"

    @property
    def state(self) -> MediaPlayerState:
        runtime = self._hub.runtime_for(self._device_id)
        if runtime is None or not runtime.connected:
            return MediaPlayerState.OFF
        return MediaPlayerState.IDLE

    async def async_play_media(
        self,
        media_type: str,
        media_id: str,
        **kwargs: Any,
    ) -> None:
        """Resolve `media_id`, fetch the bytes, and stream them to the device."""
        runtime = self._hub.runtime_for(self._device_id)
        if runtime is None or not runtime.connected:
            return

        url = await self._resolve_url(media_id)
        await self._relay.relay(runtime, {"url": url, "media_type": media_type})

    async def async_browse_media(
        self,
        media_content_type: str | None = None,
        media_content_id: str | None = None,
    ) -> BrowseMedia:
        from homeassistant.components.media_source import async_browse_media  # noqa: PLC0415

        return await async_browse_media(
            self.hass,
            media_content_id,
            content_filter=None,
        )

    async def _resolve_url(self, media_id: str) -> str:
        """Resolve a media_source:// id or pass through an HTTP URL."""
        if is_media_source_id(media_id):
            resolved = await async_resolve_media(self.hass, media_id, self.entity_id)
            return resolved.url
        if media_id.startswith("/"):
            # local HA media path — needs the absolute external URL
            return f"{get_url(self.hass)}{media_id}"
        return media_id
