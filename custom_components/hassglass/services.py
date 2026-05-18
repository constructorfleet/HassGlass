"""HassGlass services."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

import voluptuous as vol
from homeassistant.core import ServiceCall, callback
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv

from .const import DOMAIN

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant

    from . import HassGlassConfigEntry

_LOGGER = logging.getLogger(__name__)

SERVICE_NOTIFY = "notify"
SERVICE_DISMISS = "dismiss"
SERVICE_IDENTIFY = "identify"

_NOTIFY_SCHEMA = vol.Schema(
    {
        vol.Required("device_id"): cv.string,
        vol.Optional("card_id"): cv.string,
        vol.Required("card"): vol.Schema({vol.Required("kind"): cv.string}, extra=vol.ALLOW_EXTRA),
        vol.Optional("priority", default=30): vol.All(int, vol.Range(min=0, max=100)),
        vol.Optional("ttl_ms"): vol.All(int, vol.Range(min=500, max=600_000)),
    },
)

_DISMISS_SCHEMA = vol.Schema(
    {
        vol.Required("device_id"): cv.string,
        vol.Required("card_id"): cv.string,
    },
)

_IDENTIFY_SCHEMA = vol.Schema({vol.Required("device_id"): cv.string})


@callback
def async_register_services(hass: HomeAssistant, entry: HassGlassConfigEntry) -> None:
    """Register all HassGlass services on first entry setup.

    Services are idempotent across multiple entries (only one entry is allowed
    anyway), so we only register if not already present.
    """
    if hass.services.has_service(DOMAIN, SERVICE_NOTIFY):
        return

    async def _notify(call: ServiceCall) -> None:
        hub_entry = _resolve_hub_entry(hass)
        await _ensure_device(hub_entry, call.data["device_id"])
        card_id = call.data.get("card_id", _auto_card_id(call.data["card"]))
        await hub_entry.runtime_data.hud.show(
            device_id=call.data["device_id"],
            card_id=card_id,
            card=call.data["card"],
            priority=call.data["priority"],
            ttl_ms=call.data.get("ttl_ms"),
        )

    async def _dismiss(call: ServiceCall) -> None:
        hub_entry = _resolve_hub_entry(hass)
        await _ensure_device(hub_entry, call.data["device_id"])
        await hub_entry.runtime_data.hud.dismiss(
            device_id=call.data["device_id"],
            card_id=call.data["card_id"],
        )

    async def _identify(call: ServiceCall) -> None:
        hub_entry = _resolve_hub_entry(hass)
        await _ensure_device(hub_entry, call.data["device_id"])
        await hub_entry.runtime_data.hud.show(
            device_id=call.data["device_id"],
            card_id="hassglass-identify",
            card={"kind": "toast", "text": "✓ Identified", "severity": "info"},
            priority=80,
            ttl_ms=2000,
        )

    hass.services.async_register(DOMAIN, SERVICE_NOTIFY, _notify, schema=_NOTIFY_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_DISMISS, _dismiss, schema=_DISMISS_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_IDENTIFY, _identify, schema=_IDENTIFY_SCHEMA)


@callback
def async_unregister_services(hass: HomeAssistant) -> None:
    for svc in (SERVICE_NOTIFY, SERVICE_DISMISS, SERVICE_IDENTIFY):
        hass.services.async_remove(DOMAIN, svc)


def _resolve_hub_entry(hass: HomeAssistant) -> HassGlassConfigEntry:
    entries = hass.config_entries.async_entries(DOMAIN)
    if not entries:
        msg = "HassGlass is not set up"
        raise ServiceValidationError(msg)
    return entries[0]


async def _ensure_device(entry: HassGlassConfigEntry, device_id: str) -> None:
    if entry.runtime_data.hub.get_device(device_id) is None:
        msg = f"unknown HassGlass device: {device_id}"
        raise ServiceValidationError(msg)


def _auto_card_id(card: dict[str, Any]) -> str:
    return f"auto-{card.get('kind', 'card')}"
