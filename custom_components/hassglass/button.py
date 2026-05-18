"""Button entities: dismiss top card, identify."""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from .const import DOMAIN, SIGNAL_DEVICE_UPDATED
from .entity import HassGlassEntity

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant
    from homeassistant.helpers.entity_platform import AddEntitiesCallback

    from .hub import HassGlassHub


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    hub: HassGlassHub = entry.runtime_data.hub
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        entities: list[ButtonEntity] = [
            cls(hub, device_id)
            for device_id in new_devices
            for cls in (DismissTopCardButton, IdentifyButton)
        ]
        known.update(new_devices)
        async_add_entities(entities)

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_DISMISS = ButtonEntityDescription(
    key="dismiss_top_card",
    translation_key="dismiss_top_card",
    entity_category=EntityCategory.CONFIG,
)

_IDENTIFY = ButtonEntityDescription(
    key="identify",
    translation_key="identify",
    entity_category=EntityCategory.DIAGNOSTIC,
)


class _BaseButton(HassGlassEntity, ButtonEntity):
    def __init__(
        self,
        hub: HassGlassHub,
        device_id: str,
        description: ButtonEntityDescription,
    ) -> None:
        super().__init__(hub, device_id)
        self.entity_description = description
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{description.key}"


class DismissTopCardButton(_BaseButton):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _DISMISS)

    async def async_press(self) -> None:
        hud = self._hub.entry.runtime_data.hud
        top = hud.state_for(self._device_id).top()
        if top is not None:
            await hud.dismiss(self._device_id, top.card_id)


class IdentifyButton(_BaseButton):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _IDENTIFY)

    async def async_press(self) -> None:
        await self.hass.services.async_call(
            DOMAIN,
            "identify",
            {"device_id": self._device_id},
            blocking=False,
        )
