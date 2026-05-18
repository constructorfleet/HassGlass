"""HassGlass — Home Assistant integration for Rokid AR Glasses."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant

from .hub import HassGlassHub
from .hud import HudDispatcher
from .pairing import PairingBroker
from .pairing_view import HassGlassPairingView
from .pipeline_bridge import PipelineBridge
from .services import async_register_services, async_unregister_services
from .tts_relay import TtsRelay
from .ws_server import HassGlassWsView

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [
    Platform.SENSOR,
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.SELECT,
    Platform.SWITCH,
    Platform.MEDIA_PLAYER,
    Platform.EVENT,
    Platform.NOTIFY,
]


@dataclass(slots=True)
class HassGlassRuntimeData:
    """Runtime data attached to the config entry via `entry.runtime_data`."""

    hub: HassGlassHub
    pairing_broker: PairingBroker
    hud: HudDispatcher
    bridge: PipelineBridge
    tts_relay: TtsRelay
    options_snapshot: dict[str, object]


type HassGlassConfigEntry = ConfigEntry[HassGlassRuntimeData]


async def async_setup_entry(hass: HomeAssistant, entry: HassGlassConfigEntry) -> bool:
    """Set up HassGlass from its config entry."""
    hub = HassGlassHub(hass, entry)
    await hub.async_load()
    broker = PairingBroker()
    hud = HudDispatcher(hass, hub)
    tts_relay = TtsRelay(hass)
    bridge = PipelineBridge(hub, hud, tts_relay=tts_relay)
    entry.runtime_data = HassGlassRuntimeData(
        hub=hub,
        pairing_broker=broker,
        hud=hud,
        bridge=bridge,
        tts_relay=tts_relay,
        options_snapshot=dict(entry.options),
    )

    hass.http.register_view(HassGlassWsView(hass, hub, bridge))
    hass.http.register_view(HassGlassPairingView(hub))
    async_register_services(hass, entry)

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_async_update_listener))

    _LOGGER.info("HassGlass set up with %d paired device(s)", len(hub.devices))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: HassGlassConfigEntry) -> bool:
    """Unload the entry. HTTP views cannot be deregistered; they short-circuit
    once `entry.runtime_data` is no longer populated."""
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        # Singleton integration — services are owned by the (only) entry.
        async_unregister_services(hass)
    return unloaded


async def _async_update_listener(
    hass: HomeAssistant,
    entry: HassGlassConfigEntry,
) -> None:
    """Reload the integration when *options* change, not when entry.data does.

    Device records persist via a Store (M4 refactor) so `entry.data` only
    holds init markers. The remaining triggers for this listener are options-
    flow submissions (master defaults) and the one-time legacy-data migration
    in `HassGlassHub.async_load`. We compare against the snapshot captured at
    setup and skip reloading if options didn't actually change — so the
    migration write doesn't cause a spurious reload, and toggling a service
    call that briefly updates `entry.options` to the same value is also a
    no-op.
    """
    snapshot = entry.runtime_data.options_snapshot
    if dict(entry.options) == snapshot:
        return
    _LOGGER.info("HassGlass options changed; reloading entry")
    await hass.config_entries.async_reload(entry.entry_id)
