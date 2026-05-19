"""Config flow tests for HassGlass.

Verifies:
- Initial setup succeeds with default options.
- Singleton enforcement: a second setup attempt aborts.
- Options flow round-trips master defaults.
- Zeroconf-driven pairing surfaces a confirmation form and resolves the
  broker's pending claim on submit.
"""

from __future__ import annotations

from ipaddress import IPv4Address
from types import SimpleNamespace
from typing import TYPE_CHECKING

from homeassistant.config_entries import SOURCE_USER, SOURCE_ZEROCONF
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.hassglass.const import (
    CONF_DEFAULT_TTL_MS,
    CONF_PIPELINE_ID,
    CONF_WAKE_WORD_ENABLED,
    DOMAIN,
)
from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.pairing import PairingBroker

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant


def _make_record(token: str) -> DeviceRecord:
    return DeviceRecord(
        device_id="rokid-1",
        serial="SN1",
        firmware="fw",
        agent_version="0.1.0",
        token=token,
        name="Living-Room Glasses",
    )


def _discovery_info(
    device_id: str = "rokid-1",
    name: str = "Living-Room Glasses",
) -> ZeroconfServiceInfo:
    return ZeroconfServiceInfo(
        ip_address=IPv4Address("10.0.0.42"),
        ip_addresses=[IPv4Address("10.0.0.42")],
        port=8123,
        hostname="rokid.local.",
        type="_hassglass._tcp.local.",
        name=f"{device_id}._hassglass._tcp.local.",
        properties={"device_id": device_id, "name": name},
    )


def _hub_entry_with_broker(broker: PairingBroker) -> MockConfigEntry:
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id=DOMAIN,
        data={"devices": {}},
        options={},
    )
    entry.runtime_data = SimpleNamespace(pairing_broker=broker)
    return entry


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


async def test_zeroconf_aborts_when_hub_not_configured(hass: HomeAssistant) -> None:
    result = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": SOURCE_ZEROCONF},
        data=_discovery_info(),
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "hub_not_configured"


async def test_zeroconf_confirm_completes_pairing(hass: HomeAssistant) -> None:
    broker = PairingBroker()
    _hub_entry_with_broker(broker).add_to_hass(hass)
    broker.claim(
        "123456",
        device_id="rokid-1",
        name="Living-Room Glasses",
        record_factory=_make_record,
    )

    result = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": SOURCE_ZEROCONF},
        data=_discovery_info(),
    )
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "confirm"

    result = await hass.config_entries.flow.async_configure(
        result["flow_id"],
        user_input={"code": "123456"},
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "device_paired"


async def test_zeroconf_confirm_surfaces_invalid_code(hass: HomeAssistant) -> None:
    broker = PairingBroker()
    _hub_entry_with_broker(broker).add_to_hass(hass)
    broker.claim(
        "123456",
        device_id="rokid-1",
        name="Living-Room Glasses",
        record_factory=_make_record,
    )

    result = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": SOURCE_ZEROCONF},
        data=_discovery_info(),
    )
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"],
        user_input={"code": "000000"},
    )
    assert result["type"] is FlowResultType.FORM
    assert result["errors"] == {"base": "invalid_code"}
