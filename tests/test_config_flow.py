"""Config flow tests for HassGlass.

Verifies:
- Initial setup succeeds with default options.
- Singleton enforcement: a second setup attempt aborts.
- Options flow round-trips master defaults.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.config_entries import SOURCE_USER
from homeassistant.data_entry_flow import FlowResultType
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.hassglass.const import (
    CONF_DEFAULT_TTL_MS,
    CONF_PIPELINE_ID,
    CONF_WAKE_WORD_ENABLED,
    DOMAIN,
)

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant


async def test_user_flow_creates_entry(hass: HomeAssistant) -> None:
    result = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": SOURCE_USER},
    )
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "user"

    result = await hass.config_entries.flow.async_configure(
        result["flow_id"],
        user_input={
            CONF_PIPELINE_ID: "",
            CONF_WAKE_WORD_ENABLED: True,
            CONF_DEFAULT_TTL_MS: 8000,
            "fallback_media_player": "",
        },
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["title"] == "HassGlass"
    assert result["data"] == {"devices": {}}
    assert result["options"][CONF_DEFAULT_TTL_MS] == 8000


async def test_singleton_enforced(hass: HomeAssistant) -> None:
    MockConfigEntry(domain=DOMAIN, unique_id=DOMAIN).add_to_hass(hass)
    result = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": SOURCE_USER},
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "already_configured"


async def test_options_flow_round_trips(hass: HomeAssistant) -> None:
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id=DOMAIN,
        data={"devices": {}},
        options={CONF_DEFAULT_TTL_MS: 5000, CONF_WAKE_WORD_ENABLED: False},
    )
    entry.add_to_hass(hass)

    result = await hass.config_entries.options.async_init(entry.entry_id)
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "init"

    result = await hass.config_entries.options.async_configure(
        result["flow_id"],
        user_input={
            CONF_PIPELINE_ID: "preferred",
            CONF_WAKE_WORD_ENABLED: True,
            CONF_DEFAULT_TTL_MS: 10000,
            "fallback_media_player": "media_player.kitchen",
        },
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert entry.options[CONF_DEFAULT_TTL_MS] == 10000
    assert entry.options[CONF_PIPELINE_ID] == "preferred"
