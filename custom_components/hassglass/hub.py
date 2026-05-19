"""Hub runtime — the singleton that owns paired devices and live connections."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store

from .const import (
    CONF_DEFAULT_TTL_MS,
    CONF_FALLBACK_MEDIA_PLAYER,
    CONF_PIPELINE_ID,
    CONF_WAKE_WORD_ENABLED,
    DEFAULT_TTL_MS,
    DOMAIN,
    SIGNAL_DEVICE_REMOVED,
    SIGNAL_DEVICE_UPDATED,
)
from .device import DeviceBus, DeviceRecord, GlassesRuntime

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry

_LOGGER = logging.getLogger(__name__)

_STORE_VERSION = 1
_STORE_KEY = f"{DOMAIN}.devices"


async def async_clear_device_store(hass: HomeAssistant) -> None:
    """Erase the persisted device store. Called when the hub entry is removed."""
    store: Store[dict[str, dict[str, Any]]] = Store(hass, _STORE_VERSION, _STORE_KEY)
    await store.async_remove()


class HassGlassHub:
    """In-memory registry of paired devices + live runtimes.

    Device records persist via a dedicated `homeassistant.helpers.storage.Store`
    rather than `entry.data` — so add_device / remove_device / pipeline + toggle
    updates do NOT trigger config-entry reload listeners. Master options stay
    in `entry.options` where the options flow expects them.

    Migration: on first load, if a legacy `entry.data["devices"]` blob exists,
    it's copied into the Store and the entry data is cleared (one-time).
    """

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self._devices: dict[str, DeviceRecord] = {}
        self._runtimes: dict[str, GlassesRuntime] = {}
        self._buses: dict[str, DeviceBus] = {}
        self._store: Store[dict[str, dict[str, Any]]] = Store(
            hass,
            _STORE_VERSION,
            _STORE_KEY,
        )

    # -- Persistence ---------------------------------------------------------

    async def async_load(self) -> None:
        """Load device records from Store, migrating from entry.data if needed."""
        stored = await self._store.async_load()
        if stored is None:
            legacy = self.entry.data.get("devices")
            if isinstance(legacy, dict) and legacy:
                _LOGGER.info("migrating %d device record(s) from entry.data → Store", len(legacy))
                stored = legacy
                await self._store.async_save(stored)
                # Drop the legacy blob so it isn't a future source of truth.
                self.hass.config_entries.async_update_entry(
                    self.entry,
                    data={k: v for k, v in self.entry.data.items() if k != "devices"},
                )
            else:
                stored = {}

        for device_id, raw in stored.items():
            self._devices[device_id] = DeviceRecord.from_dict(raw)

    async def _persist(self) -> None:
        await self._store.async_save(
            {did: rec.to_dict() for did, rec in self._devices.items()},
        )

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

    async def set_device_wake_word(self, device_id: str, enabled: bool) -> None:
        """Persist the per-device on-glass wake-word toggle."""
        record = self._devices[device_id]
        record.wake_word_enabled = bool(enabled)
        await self._persist()
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_UPDATED, device_id)

    async def set_device_listening(self, device_id: str, enabled: bool) -> None:
        """Persist the per-device mic-privacy toggle.

        When False, `audio.run_assist_pipeline` refuses to start a pipeline
        run — the integration drops mic frames at the boundary rather than
        forwarding them to the Assist pipeline.
        """
        record = self._devices[device_id]
        record.listening_enabled = bool(enabled)
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
