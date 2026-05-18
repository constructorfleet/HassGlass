"""Binary sensors: worn, listening, connected."""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
    BinarySensorEntityDescription,
)
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
        entities: list[BinarySensorEntity] = [
            cls(hub, device_id)
            for device_id in new_devices
            for cls in (WornBinarySensor, ListeningBinarySensor, ConnectedBinarySensor)
        ]
        known.update(new_devices)
        async_add_entities(entities)

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_WORN = BinarySensorEntityDescription(
    key="worn",
    translation_key="worn",
    device_class=BinarySensorDeviceClass.OCCUPANCY,
)

_LISTENING = BinarySensorEntityDescription(
    key="listening",
    translation_key="listening",
    entity_category=EntityCategory.DIAGNOSTIC,
)

_CONNECTED = BinarySensorEntityDescription(
    key="connected",
    translation_key="connected",
    device_class=BinarySensorDeviceClass.CONNECTIVITY,
    entity_category=EntityCategory.DIAGNOSTIC,
)


class _BaseBinarySensor(HassGlassEntity, BinarySensorEntity):
    def __init__(
        self,
        hub: HassGlassHub,
        device_id: str,
        description: BinarySensorEntityDescription,
    ) -> None:
        super().__init__(hub, device_id)
        self.entity_description = description
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{description.key}"


class WornBinarySensor(_BaseBinarySensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _WORN)

    @property
    def is_on(self) -> bool | None:
        runtime = self._hub.runtime_for(self._device_id)
        return runtime.telemetry.worn if runtime else None


class ListeningBinarySensor(_BaseBinarySensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _LISTENING)

    @property
    def is_on(self) -> bool:
        runtime = self._hub.runtime_for(self._device_id)
        return bool(runtime and runtime.listening)


class ConnectedBinarySensor(_BaseBinarySensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _CONNECTED)

    @property
    def is_on(self) -> bool:
        runtime = self._hub.runtime_for(self._device_id)
        return bool(runtime and runtime.connected)

    @property
    def available(self) -> bool:
        # Connectivity sensor must always be available so it can report False.
        return self._hub.get_device(self._device_id) is not None
