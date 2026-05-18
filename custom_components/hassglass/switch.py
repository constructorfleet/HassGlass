"""Switch entities for per-device toggles."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from homeassistant.components.switch import SwitchEntity, SwitchEntityDescription
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
    """Create one wake-word + listening-enabled pair per device."""
    hub: HassGlassHub = entry.runtime_data.hub
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        entities: list[SwitchEntity] = [
            cls(hub, device_id)
            for device_id in new_devices
            for cls in (WakeWordSwitch, ListeningEnabledSwitch)
        ]
        known.update(new_devices)
        async_add_entities(entities)

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_WAKE_WORD = SwitchEntityDescription(
    key="wake_word",
    translation_key="wake_word",
    entity_category=EntityCategory.CONFIG,
)

_LISTENING = SwitchEntityDescription(
    key="listening_enabled",
    translation_key="listening_enabled",
    entity_category=EntityCategory.CONFIG,
)


class _BaseSwitch(HassGlassEntity, SwitchEntity):
    def __init__(
        self,
        hub: HassGlassHub,
        device_id: str,
        description: SwitchEntityDescription,
    ) -> None:
        super().__init__(hub, device_id)
        self.entity_description = description
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{description.key}"

    @property
    def available(self) -> bool:
        # Config switches stay available even when the device is offline so
        # the user can still toggle them; new value takes effect on next
        # reconnect / next audio.start.
        return self._hub.get_device(self._device_id) is not None


class WakeWordSwitch(_BaseSwitch):
    """Enable / disable the on-glasses "Hi Rokid" wake-word detector."""

    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _WAKE_WORD)

    @property
    def is_on(self) -> bool:
        rec = self.record
        return bool(rec.wake_word_enabled) if rec is not None else False

    async def async_turn_on(self, **_kwargs: Any) -> None:
        await self._hub.set_device_wake_word(self._device_id, enabled=True)

    async def async_turn_off(self, **_kwargs: Any) -> None:
        await self._hub.set_device_wake_word(self._device_id, enabled=False)


class ListeningEnabledSwitch(_BaseSwitch):
    """Mic privacy cut. When off, audio.run_assist_pipeline refuses to start."""

    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _LISTENING)

    @property
    def is_on(self) -> bool:
        rec = self.record
        return bool(rec.listening_enabled) if rec is not None else False

    async def async_turn_on(self, **_kwargs: Any) -> None:
        await self._hub.set_device_listening(self._device_id, enabled=True)

    async def async_turn_off(self, **_kwargs: Any) -> None:
        await self._hub.set_device_listening(self._device_id, enabled=False)
