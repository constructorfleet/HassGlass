"""HTTP endpoint the Glass Agent calls to start a pairing handshake.

POST /api/hassglass/pair
{
  "code":           "123456",
  "device_id":      "rokid-001",
  "serial":         "SN12345",
  "firmware":       "yoda-1.4.0",
  "agent_version":  "0.1.0",
  "name":           "Living-Room Glasses"
}

The request parks until the user confirms the code from the HA side (via the
zeroconf-driven config flow) or the broker's TTL elapses.

Response on success (HTTP 200):
{
  "token":     "...",
  "device_id": "rokid-001"
}

Failure modes:
- HTTP 400: malformed JSON or schema mismatch.
- HTTP 401: code rejected (lockout, mismatched on confirmation).
- HTTP 408: TTL elapsed without confirmation.
"""

from __future__ import annotations

import asyncio
import contextlib
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

# Match the broker's default TTL — the agent will give up on its own side too.
_CLAIM_TIMEOUT_S = 120.0

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
    """Accepts pairing claims from the Glass Agent and parks them until confirmed."""

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

        def record_factory(token: str) -> DeviceRecord:
            return DeviceRecord(
                device_id=validated["device_id"],
                serial=validated["serial"],
                firmware=validated["firmware"],
                agent_version=validated["agent_version"],
                token=token,
                name=validated["name"],
            )

        try:
            pending = broker.claim(
                validated["code"],
                device_id=validated["device_id"],
                name=validated["name"],
                record_factory=record_factory,
            )
        except PairingError as exc:
            _LOGGER.info("pairing claim rejected: %s", exc)
            return self.json_message(str(exc), HTTPStatus.UNAUTHORIZED)

        future = pending.ensure_future()
        try:
            record = await asyncio.wait_for(future, timeout=_CLAIM_TIMEOUT_S)
        except TimeoutError:
            broker.cancel(pending)
            return self.json_message("pairing timed out", HTTPStatus.REQUEST_TIMEOUT)
        except asyncio.CancelledError:
            broker.cancel(pending)
            raise
        except PairingError as exc:
            return self.json_message(str(exc), HTTPStatus.UNAUTHORIZED)

        with contextlib.suppress(asyncio.CancelledError):
            await self.hub.add_device(record)
        _LOGGER.info("paired device %s (%s)", record.device_id, record.name)
        return self.json({"token": record.token, "device_id": record.device_id})
