"""Config flow for HassGlass.

The integration is a *hub*: one config entry per Home Assistant installation,
covering many devices. Initial setup collects master defaults; subsequent
pairings happen through the entry's "Devices" page (handled by the pairing
HTTP endpoint), so the flow itself is single-step.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import voluptuous as vol
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow

from .const import (
    CONF_DEFAULT_TTL_MS,
    CONF_FALLBACK_MEDIA_PLAYER,
    CONF_PIPELINE_ID,
    CONF_WAKE_WORD_ENABLED,
    DEFAULT_TTL_MS,
    DOMAIN,
)


class HassGlassConfigFlow(ConfigFlow, domain=DOMAIN):
    """Initial-setup flow."""

    VERSION = 1

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
