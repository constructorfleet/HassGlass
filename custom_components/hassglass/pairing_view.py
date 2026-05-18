"""HTTP endpoint the Glass Agent calls to complete a pairing handshake.

POST /api/hassglass/pair
{
  "code":           "123456",
  "device_id":      "rokid-001",
  "serial":         "SN12345",
  "firmware":       "yoda-1.4.0",
  "agent_version":  "0.1.0",
  "name":           "Living-Room Glasses"
}

Response on success (HTTP 200):
{
  "token":     "...",
  "device_id": "rokid-001"
}

The pairing UI in HA's config flow is what generates the code (via
`PairingBroker.begin()`) and shows it to the user; the user reads it off the
HUD and enters it in HA. The Glass Agent independently POSTs here to claim the
code. The first side to commit wins; the other waits on
`PendingPairing.completed`.
"""

from __future__ import annotations

import logging
from http import HTTPStatus
from typing import TYPE_CHECKING

import voluptuous as vol
from aiohttp import web
from homeassistant.helpers.http import HomeAssistantView

from .device import DeviceRecord
from .pairing import PairingError

if TYPE_CHECKING:
    from .hub import HassGlassHub

_LOGGER = logging.getLogger(__name__)

PAIRING_URL = "/api/hassglass/pair"

_SCHEMA = vol.Schema(
    {
        vol.Required("code"): vol.All(str, vol.Length(min=6, max=6)),
        vol.Required("device_id"): vol.All(str, vol.Length(min=1, max=128)),
        vol.Required("serial"): vol.All(str, vol.Length(min=1, max=128)),
        vol.Required("firmware"): vol.All(str, vol.Length(min=1, max=64)),
        vol.Required("agent_version"): vol.All(str, vol.Length(min=1, max=64)),
        vol.Optional("name", default="Rokid Glasses"): vol.All(str, vol.Length(min=1, max=128)),
    },
)


class HassGlassPairingView(HomeAssistantView):
    """Accepts pairing-code claims from the Glass Agent."""

    url = PAIRING_URL
    name = "api:hassglass:pair"
    requires_auth = False  # unauthenticated by design — the code IS the auth

    def __init__(self, hub: HassGlassHub) -> None:
        self.hub = hub

    async def post(self, request: web.Request) -> web.Response:
        try:
            payload = await request.json()
        except ValueError:
            return self.json_message("invalid JSON", HTTPStatus.BAD_REQUEST)
        try:
            validated = _SCHEMA(payload)
        except vol.Invalid as exc:
            return self.json_message(f"invalid payload: {exc}", HTTPStatus.BAD_REQUEST)

        broker = self.hub.entry.runtime_data.pairing_broker
        try:
            record = broker.complete(
                validated["code"],
                record_factory=lambda token: DeviceRecord(
                    device_id=validated["device_id"],
                    serial=validated["serial"],
                    firmware=validated["firmware"],
                    agent_version=validated["agent_version"],
                    token=token,
                    name=validated["name"],
                ),
            )
        except PairingError as exc:
            _LOGGER.info("pairing claim rejected: %s", exc)
            return self.json_message(str(exc), HTTPStatus.UNAUTHORIZED)

        await self.hub.add_device(record)
        _LOGGER.info("paired device %s (%s)", record.device_id, record.name)
        return self.json({"token": record.token, "device_id": record.device_id})
