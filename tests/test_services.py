"""Integration tests for HassGlass services.

Sets up a full HA instance with a HassGlass config entry, swaps in a device,
and exercises the service handlers end-to-end. The hub's outbound message
sender is replaced with an AsyncMock so we can assert exactly what would have
been written to the WebSocket.
"""

from __future__ import annotations

from typing import TYPE_CHECKING
from unittest.mock import AsyncMock, MagicMock

import pytest
from homeassistant.exceptions import ServiceValidationError
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.hassglass.const import (
    CONF_DEFAULT_TTL_MS,
    CONF_WAKE_WORD_ENABLED,
    DOMAIN,
)
from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.protocol import MessageType

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant


@pytest.fixture
async def setup_hub(hass: HomeAssistant) -> tuple[MockConfigEntry, AsyncMock]:
    """Set up HassGlass and return (entry, mocked_runtime_send_message)."""
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id=DOMAIN,
        data={"devices": {}},
        options={CONF_DEFAULT_TTL_MS: 8000, CONF_WAKE_WORD_ENABLED: True},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()

    # Inject a paired device + a fake live runtime.
    hub = entry.runtime_data.hub
    record = DeviceRecord(
        device_id="rokid-1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test Glasses",
    )
    await hub.add_device(record)

    runtime = MagicMock(
        spec=["record", "send_message", "current_card_id", "connected"],
        record=record,
        send_message=AsyncMock(),
        current_card_id=None,
        connected=True,
    )
    hub._runtimes[record.device_id] = runtime
    return entry, runtime.send_message


async def test_notify_service_sends_hud_show(
    hass: HomeAssistant,
    setup_hub: tuple[MockConfigEntry, AsyncMock],
) -> None:
    _, send_mock = setup_hub
    await hass.services.async_call(
        DOMAIN,
        "notify",
        {
            "device_id": "rokid-1",
            "card_id": "doorbell",
            "card": {"kind": "alert", "title": "Doorbell", "body": "Front door"},
            "priority": 90,
            "ttl_ms": 10000,
        },
        blocking=True,
    )
    send_mock.assert_awaited_once()
    msg_type = send_mock.await_args.args[0]
    kwargs = send_mock.await_args.kwargs
    assert msg_type is MessageType.HUD_SHOW
    assert kwargs["id"] == "doorbell"
    assert kwargs["priority"] == 90
    assert kwargs["ttl_ms"] == 10000
    assert kwargs["card"]["title"] == "Doorbell"


async def test_dismiss_service_sends_hud_dismiss(
    hass: HomeAssistant,
    setup_hub: tuple[MockConfigEntry, AsyncMock],
) -> None:
    _, send_mock = setup_hub
    # First show something so there's state to dismiss.
    await hass.services.async_call(
        DOMAIN,
        "notify",
        {
            "device_id": "rokid-1",
            "card_id": "x",
            "card": {"kind": "toast", "text": "hi"},
        },
        blocking=True,
    )
    send_mock.reset_mock()

    await hass.services.async_call(
        DOMAIN,
        "dismiss",
        {"device_id": "rokid-1", "card_id": "x"},
        blocking=True,
    )
    msg_type = send_mock.await_args.args[0]
    assert msg_type is MessageType.HUD_DISMISS
    assert send_mock.await_args.kwargs["id"] == "x"


async def test_identify_service_pushes_toast(
    hass: HomeAssistant,
    setup_hub: tuple[MockConfigEntry, AsyncMock],
) -> None:
    _, send_mock = setup_hub
    await hass.services.async_call(
        DOMAIN,
        "identify",
        {"device_id": "rokid-1"},
        blocking=True,
    )
    msg_type = send_mock.await_args.args[0]
    kwargs = send_mock.await_args.kwargs
    assert msg_type is MessageType.HUD_SHOW
    assert kwargs["card"]["kind"] == "toast"
    assert kwargs["priority"] == 80


async def test_notify_unknown_device_raises(
    hass: HomeAssistant,
    setup_hub: tuple[MockConfigEntry, AsyncMock],
) -> None:
    with pytest.raises(ServiceValidationError, match="unknown HassGlass device"):
        await hass.services.async_call(
            DOMAIN,
            "notify",
            {
                "device_id": "nope",
                "card": {"kind": "toast", "text": "x"},
            },
            blocking=True,
        )
