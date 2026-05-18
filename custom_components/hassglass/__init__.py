"""HassGlass — Home Assistant integration for Rokid AR Glasses."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant

from .hub import HassGlassHub
from .pairing import PairingBroker
from .pairing_view import HassGlassPairingView
from .ws_server import HassGlassWsView

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [
    Platform.SENSOR,
    Platform.BINARY_SENSOR,
]


@dataclass(slots=True)
class HassGlassRuntimeData:
    """Runtime data attached to the config entry via `entry.runtime_data`."""

    hub: HassGlassHub
    pairing_broker: PairingBroker


type HassGlassConfigEntry = ConfigEntry[HassGlassRuntimeData]


async def async_setup_entry(hass: HomeAssistant, entry: HassGlassConfigEntry) -> bool:
    """Set up HassGlass from its config entry."""
    hub = HassGlassHub(hass, entry)
    broker = PairingBroker()
    entry.runtime_data = HassGlassRuntimeData(hub=hub, pairing_broker=broker)

    hass.http.register_view(HassGlassWsView(hass, hub))
    hass.http.register_view(HassGlassPairingView(hub))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    _LOGGER.info("HassGlass set up with %d paired device(s)", len(hub.devices))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: HassGlassConfigEntry) -> bool:
    """Unload the entry. HTTP views cannot be deregistered; they short-circuit
    once `entry.runtime_data` is no longer populated."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
