"""Hub runtime — the singleton that owns paired devices and live connections."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from homeassistant.helpers.dispatcher import async_dispatcher_send

from .const import (
    CONF_DEFAULT_TTL_MS,
    CONF_FALLBACK_MEDIA_PLAYER,
    CONF_PIPELINE_ID,
    CONF_WAKE_WORD_ENABLED,
    DEFAULT_TTL_MS,
    SIGNAL_DEVICE_REMOVED,
    SIGNAL_DEVICE_UPDATED,
)
from .device import DeviceBus, DeviceRecord, GlassesRuntime

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant

_LOGGER = logging.getLogger(__name__)


class HassGlassHub:
    """In-memory registry of paired devices + live runtimes.

    One hub per config entry. Persistent state lives in `entry.data["devices"]`;
    live runtimes are created on WebSocket connect and dropped on disconnect.
    """

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self._devices: dict[str, DeviceRecord] = {}
        self._runtimes: dict[str, GlassesRuntime] = {}
        self._buses: dict[str, DeviceBus] = {}
        self._load_devices_from_entry()

    # -- Persistence ---------------------------------------------------------

    def _load_devices_from_entry(self) -> None:
        raw_devices = self.entry.data.get("devices", {})
        for device_id, raw in raw_devices.items():
            self._devices[device_id] = DeviceRecord.from_dict(raw)

    async def _persist(self) -> None:
        new_data = {
            **self.entry.data,
            "devices": {did: rec.to_dict() for did, rec in self._devices.items()},
        }
        self.hass.config_entries.async_update_entry(self.entry, data=new_data)

    # -- Device CRUD ---------------------------------------------------------

    @property
    def devices(self) -> dict[str, DeviceRecord]:
        return dict(self._devices)

    def get_device(self, device_id: str) -> DeviceRecord | None:
        return self._devices.get(device_id)

    async def add_device(self, record: DeviceRecord) -> None:
        self._devices[record.device_id] = record
        await self._persist()
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_UPDATED, record.device_id)

    async def remove_device(self, device_id: str) -> None:
        self._devices.pop(device_id, None)
        await self._disconnect_runtime(device_id)
        await self._persist()
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_REMOVED, device_id)

    async def set_device_pipeline(self, device_id: str, pipeline_id: str | None) -> None:
        """Persist the per-device Assist pipeline override."""
        record = self._devices[device_id]
        normalized = pipeline_id.strip() if isinstance(pipeline_id, str) else None
        record.pipeline_id = normalized or None
        await self._persist()
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_UPDATED, device_id)

    # -- Live runtime --------------------------------------------------------

    def runtime_for(self, device_id: str) -> GlassesRuntime | None:
        return self._runtimes.get(device_id)

    def bus_for(self, device_id: str) -> DeviceBus:
        if device_id not in self._buses:
            self._buses[device_id] = DeviceBus()
        return self._buses[device_id]

    def attach_runtime(self, runtime: GlassesRuntime) -> None:
        self._runtimes[runtime.record.device_id] = runtime
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_UPDATED, runtime.record.device_id)

    async def _disconnect_runtime(self, device_id: str) -> None:
        runtime = self._runtimes.pop(device_id, None)
        if runtime is not None:
            runtime.connected = False
            if not runtime.ws.closed:
                await runtime.ws.close()

    async def detach_runtime(self, device_id: str) -> None:
        await self._disconnect_runtime(device_id)
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_UPDATED, device_id)

    # -- Master options accessors -------------------------------------------

    @property
    def default_pipeline_id(self) -> str | None:
        value = self.entry.options.get(CONF_PIPELINE_ID)
        return value if isinstance(value, str) and value else None

    @property
    def default_ttl_ms(self) -> int:
        value = self.entry.options.get(CONF_DEFAULT_TTL_MS, DEFAULT_TTL_MS)
        return int(value)

    @property
    def default_wake_word_enabled(self) -> bool:
        return bool(self.entry.options.get(CONF_WAKE_WORD_ENABLED, True))

    @property
    def fallback_media_player(self) -> str | None:
        value = self.entry.options.get(CONF_FALLBACK_MEDIA_PLAYER)
        return value if isinstance(value, str) and value else None

    def resolved_options_for(self, device_id: str) -> dict[str, Any]:
        """Merge master defaults with per-device overrides.

        Per-device overrides live in the DeviceRecord itself; for fields where
        the record holds None, the master default is used.
        """
        rec = self.get_device(device_id)
        if rec is None:
            return {}
        return {
            CONF_PIPELINE_ID: rec.pipeline_id or self.default_pipeline_id,
            CONF_WAKE_WORD_ENABLED: rec.wake_word_enabled
            if rec.wake_word_enabled is not None
            else self.default_wake_word_enabled,
            CONF_DEFAULT_TTL_MS: self.default_ttl_ms,
            CONF_FALLBACK_MEDIA_PLAYER: self.fallback_media_player,
        }
