"""Config flow for HassGlass.

The integration is a *hub*: one config entry per Home Assistant installation,
covering many devices. Initial setup collects master defaults. Subsequent
pairings start on the glasses (the agent advertises itself as
`_hassglass._tcp.local.` and POSTs a code to our pairing endpoint); HA's
zeroconf integration surfaces the device under Integrations → Discovered,
and this flow's zeroconf step prompts the user to type the code shown on
the HUD. Confirmation resolves the agent's parked POST.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping
from typing import Any

import voluptuous as vol
from homeassistant.config_entries import (
    SOURCE_ZEROCONF,
    ConfigEntry,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .const import (
    CONF_DEFAULT_TTL_MS,
    CONF_FALLBACK_MEDIA_PLAYER,
    CONF_PIPELINE_ID,
    CONF_WAKE_WORD_ENABLED,
    DEFAULT_TTL_MS,
    DOMAIN,
)
from .pairing import PairingError

_LOGGER = logging.getLogger(__name__)

CONF_CODE = "code"


class HassGlassConfigFlow(ConfigFlow, domain=DOMAIN):
    """Initial-setup flow."""

    VERSION = 1

    def __init__(self) -> None:
        self._pending_device_id: str | None = None
        self._pending_device_name: str | None = None

    async def async_step_user(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> ConfigFlowResult:
        """Single-entry singleton — only one HassGlass hub per HA install."""
        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()
        if user_input is None:
            return self.async_show_form(step_id="user", data_schema=_user_schema())
        return self.async_create_entry(title="HassGlass", data={"devices": {}}, options=user_input)

    async def async_step_zeroconf(
        self,
        discovery_info: ZeroconfServiceInfo,
    ) -> ConfigFlowResult:
        """Surface a discovered glasses device awaiting pairing confirmation.

        The agent advertises `_hassglass._tcp.local.` with TXT records carrying
        its identity. The hub must already be set up (the user runs the
        integration's user flow once); pairing additional devices afterwards
        is handled by this discovery → confirm step.
        """
        properties = discovery_info.properties or {}
        device_id = _str_property(properties, "device_id") or _str_property(properties, "id")
        name = _str_property(properties, "name") or "HassGlass device"
        if not device_id:
            return self.async_abort(reason="invalid_discovery_info")

        await self.async_set_unique_id(f"{DOMAIN}:{device_id}")
        self._abort_if_unique_id_configured()

        existing_entries = self._async_current_entries(include_ignore=False)
        hub_entry = next((entry for entry in existing_entries if entry.domain == DOMAIN), None)
        if hub_entry is None or not hasattr(hub_entry, "runtime_data"):
            return self.async_abort(reason="hub_not_configured")

        self._pending_device_id = device_id
        self._pending_device_name = name
        self.context["title_placeholders"] = {"name": name}
        return await self.async_step_confirm()

    async def async_step_confirm(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> ConfigFlowResult:
        """Prompt the user for the 6-digit code shown on the HUD."""
        device_name = self._pending_device_name or "the glasses"
        errors: dict[str, str] = {}

        if user_input is not None:
            code = str(user_input[CONF_CODE]).strip()
            hub_entry = next(
                (
                    entry
                    for entry in self._async_current_entries(include_ignore=False)
                    if entry.domain == DOMAIN
                ),
                None,
            )
            if hub_entry is None or not hasattr(hub_entry, "runtime_data"):
                return self.async_abort(reason="hub_not_configured")
            broker = hub_entry.runtime_data.pairing_broker
            try:
                broker.confirm(code)
            except PairingError as exc:
                _LOGGER.info("pairing confirm rejected: %s", exc)
                errors["base"] = "invalid_code"
            else:
                return self.async_abort(
                    reason="device_paired",
                    description_placeholders={"name": device_name},
                )

        return self.async_show_form(
            step_id="confirm",
            data_schema=vol.Schema(
                {vol.Required(CONF_CODE): vol.All(str, vol.Length(min=6, max=6))},
            ),
            description_placeholders={"name": device_name},
            errors=errors,
        )

    @staticmethod
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return HassGlassOptionsFlow(config_entry)


class HassGlassOptionsFlow(OptionsFlow):
    """Edit master defaults after initial setup."""

    def __init__(self, config_entry: ConfigEntry) -> None:
        self._entry = config_entry

    async def async_step_init(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(title="", data=user_input)
        return self.async_show_form(
            step_id="init",
            data_schema=_user_schema(defaults=self._entry.options),
        )


def _user_schema(defaults: Mapping[str, Any] | None = None) -> vol.Schema:
    defaults = defaults or {}
    return vol.Schema(
        {
            vol.Optional(
                CONF_PIPELINE_ID,
                default=defaults.get(CONF_PIPELINE_ID, ""),
            ): str,
            vol.Optional(
                CONF_WAKE_WORD_ENABLED,
                default=defaults.get(CONF_WAKE_WORD_ENABLED, True),
            ): bool,
            vol.Optional(
                CONF_DEFAULT_TTL_MS,
                default=defaults.get(CONF_DEFAULT_TTL_MS, DEFAULT_TTL_MS),
            ): vol.All(int, vol.Range(min=500, max=60_000)),
            vol.Optional(
                CONF_FALLBACK_MEDIA_PLAYER,
                default=defaults.get(CONF_FALLBACK_MEDIA_PLAYER, ""),
            ): str,
        },
    )


def _str_property(properties: Mapping[str, Any], key: str) -> str | None:
    """Decode a TXT-record property to a str, dropping bytes wrappers."""
    value = properties.get(key)
    if value is None:
        return None
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8").strip() or None
        except UnicodeDecodeError:
            return None
    text = str(value).strip()
    return text or None


__all__ = ["SOURCE_ZEROCONF", "HassGlassConfigFlow", "HassGlassOptionsFlow"]
