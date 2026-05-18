"""Notify entity — `notify.send_message` target per glasses device.

Modern `NotifyEntity` platform (NOT the legacy notify-platform service
auto-creation, which doesn't fit the multi-device hub model). One entity
per paired device. The `send_message` action lands as a `toast` card on
the HUD by default; a `title` upgrades it to an `icon_text`.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from .const import DOMAIN, SIGNAL_DEVICE_UPDATED
from .entity import HassGlassEntity

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant
    from homeassistant.helpers.entity_platform import AddEntitiesCallback

    from .hub import HassGlassHub
    from .hud import HudDispatcher


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """One NotifyEntity per device, refreshing on dispatcher signal."""
    hub: HassGlassHub = entry.runtime_data.hub
    hud: HudDispatcher = entry.runtime_data.hud
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        known.update(new_devices)
        async_add_entities(
            [GlassesNotify(hub, hud, device_id) for device_id in new_devices],
        )

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


class GlassesNotify(HassGlassEntity, NotifyEntity):
    """Push notifications from `notify.send_message` to the glasses HUD."""

    _attr_supported_features = NotifyEntityFeature.TITLE
    _attr_translation_key = "notify"

    def __init__(self, hub: HassGlassHub, hud: HudDispatcher, device_id: str) -> None:
        super().__init__(hub, device_id)
        self._hud = hud
        self._attr_unique_id = f"{DOMAIN}_{device_id}_notify"

    async def async_send_message(
        self,
        message: str,
        title: str | None = None,
        **_kwargs: Any,
    ) -> None:
        """Render the notification as a HUD card.

        Without a title the message goes in a `toast` (single line).
        With a title we use `icon_text` so the title is prominent and the
        message lives in the subtitle slot.
        """
        if title:
            card: dict[str, Any] = {"kind": "icon_text", "title": title, "subtitle": message}
        else:
            card = {"kind": "toast", "text": message}
        await self._hud.show(
            device_id=self._device_id,
            card_id="notify",
            card=card,
            priority=50,
            ttl_ms=8000,
        )
