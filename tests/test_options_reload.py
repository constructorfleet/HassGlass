"""Verify the options-flow auto-reload listener.

The listener should reload the entry when *options* change but skip when
`entry.data` is updated (e.g. by the one-time legacy-`entry.data["devices"]`
migration) without options actually changing.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.hassglass.const import (
    CONF_DEFAULT_TTL_MS,
    CONF_WAKE_WORD_ENABLED,
    DOMAIN,
)

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant


async def _setup_entry(hass: HomeAssistant) -> MockConfigEntry:
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id=DOMAIN,
        data={"devices": {}},
        options={CONF_DEFAULT_TTL_MS: 8000, CONF_WAKE_WORD_ENABLED: True},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_options_change_reloads_entry(hass: HomeAssistant) -> None:
    entry = await _setup_entry(hass)
    original_runtime = entry.runtime_data

    # Simulate an options-flow submission changing the default TTL.
    hass.config_entries.async_update_entry(
        entry,
        options={CONF_DEFAULT_TTL_MS: 12000, CONF_WAKE_WORD_ENABLED: True},
    )
    await hass.async_block_till_done()

    # After reload, runtime_data is a fresh instance.
    assert entry.runtime_data is not original_runtime
    assert entry.runtime_data.options_snapshot[CONF_DEFAULT_TTL_MS] == 12000


async def test_data_only_change_does_not_reload(hass: HomeAssistant) -> None:
    entry = await _setup_entry(hass)
    original_runtime = entry.runtime_data

    # Simulate the legacy-data migration: data changes, options don't.
    hass.config_entries.async_update_entry(
        entry,
        data={"misc": "marker"},
        options=dict(entry.options),
    )
    await hass.async_block_till_done()

    # No reload — same runtime_data instance.
    assert entry.runtime_data is original_runtime
