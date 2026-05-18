"""Sensor entities: battery, signal, last intent, current card."""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.const import PERCENTAGE, SIGNAL_STRENGTH_DECIBELS_MILLIWATT, EntityCategory
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
    """Create one sensor stack per device, expanding as new devices are paired."""
    hub: HassGlassHub = entry.runtime_data.hub
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        entities: list[SensorEntity] = [
            cls(hub, device_id)
            for device_id in new_devices
            for cls in (BatterySensor, SignalSensor, LastIntentSensor, CurrentCardSensor)
        ]
        known.update(new_devices)
        async_add_entities(entities)

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_BATTERY = SensorEntityDescription(
    key="battery_pct",
    translation_key="battery",
    device_class=SensorDeviceClass.BATTERY,
    state_class=SensorStateClass.MEASUREMENT,
    native_unit_of_measurement=PERCENTAGE,
    entity_category=EntityCategory.DIAGNOSTIC,
)

_SIGNAL = SensorEntityDescription(
    key="rssi_dbm",
    translation_key="signal",
    device_class=SensorDeviceClass.SIGNAL_STRENGTH,
    state_class=SensorStateClass.MEASUREMENT,
    native_unit_of_measurement=SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
    entity_category=EntityCategory.DIAGNOSTIC,
    entity_registry_enabled_default=False,
)

_LAST_INTENT = SensorEntityDescription(
    key="last_intent",
    translation_key="last_intent",
    entity_category=EntityCategory.DIAGNOSTIC,
)

_CURRENT_CARD = SensorEntityDescription(
    key="current_card",
    translation_key="current_card",
    entity_category=EntityCategory.DIAGNOSTIC,
)


class _BaseSensor(HassGlassEntity, SensorEntity):
    def __init__(
        self,
        hub: HassGlassHub,
        device_id: str,
        description: SensorEntityDescription,
    ) -> None:
        super().__init__(hub, device_id)
        self.entity_description = description
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{description.key}"


class BatterySensor(_BaseSensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _BATTERY)

    @property
    def native_value(self) -> int | None:
        runtime = self._hub.runtime_for(self._device_id)
        return runtime.telemetry.battery_pct if runtime else None


class SignalSensor(_BaseSensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _SIGNAL)

    @property
    def native_value(self) -> int | None:
        runtime = self._hub.runtime_for(self._device_id)
        return runtime.telemetry.rssi_dbm if runtime else None


class LastIntentSensor(_BaseSensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _LAST_INTENT)

    @property
    def native_value(self) -> str | None:
        runtime = self._hub.runtime_for(self._device_id)
        return runtime.last_intent if runtime else None


class CurrentCardSensor(_BaseSensor):
    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _CURRENT_CARD)

    @property
    def native_value(self) -> str | None:
        runtime = self._hub.runtime_for(self._device_id)
        return runtime.current_card_id if runtime else None
