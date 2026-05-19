"""Base entity for HassGlass — wires up dispatcher subscription + device info."""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.core import callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import DOMAIN, MANUFACTURER, SIGNAL_DEVICE_REMOVED, SIGNAL_DEVICE_UPDATED

if TYPE_CHECKING:
    from .device import DeviceRecord
    from .hub import HassGlassHub


class HassGlassEntity(Entity):
    """Base class: ties entity lifecycle to a single device + hub."""

    _attr_should_poll = False
    _attr_has_entity_name = True

    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        self._hub = hub
        self._device_id = device_id

    @property
    def record(self) -> DeviceRecord | None:
        return self._hub.get_device(self._device_id)

    @property
    def device_info(self) -> DeviceInfo:
        rec = self.record
        return DeviceInfo(
            identifiers={(DOMAIN, self._device_id)},
            manufacturer=MANUFACTURER,
            model="Rokid AR Glasses",
            name=rec.name if rec else self._device_id,
            sw_version=rec.firmware if rec else None,
            serial_number=rec.serial if rec else None,
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                SIGNAL_DEVICE_UPDATED,
                self._handle_device_signal,
            ),
        )
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                SIGNAL_DEVICE_REMOVED,
                self._handle_device_signal,
            ),
        )

    @property
    def available(self) -> bool:
        return self._hub.get_device(self._device_id) is not None

    @callback
    def _handle_device_signal(self, device_id: str) -> None:
        if device_id == self._device_id:
            self.async_schedule_update_ha_state()
