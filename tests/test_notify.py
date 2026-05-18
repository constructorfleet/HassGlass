"""Tests for the NotifyEntity that targets the HUD."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.notify import GlassesNotify


@pytest.fixture
def entity() -> tuple[GlassesNotify, MagicMock]:
    record = DeviceRecord(
        device_id="d1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test",
    )
    hub = MagicMock()
    hub.get_device.return_value = record
    runtime = MagicMock(connected=True)
    hub.runtime_for.return_value = runtime
    hud = MagicMock()
    hud.show = AsyncMock()
    return GlassesNotify(hub, hud, "d1"), hud


async def test_send_message_without_title_is_toast(
    entity: tuple[GlassesNotify, MagicMock],
) -> None:
    notify, hud = entity
    await notify.async_send_message("Lights on")
    hud.show.assert_awaited_once()
    kwargs = hud.show.await_args.kwargs
    assert kwargs["card"] == {"kind": "toast", "text": "Lights on"}
    assert kwargs["card_id"] == "notify"
    assert kwargs["priority"] == 50


async def test_send_message_with_title_is_icon_text(
    entity: tuple[GlassesNotify, MagicMock],
) -> None:
    notify, hud = entity
    await notify.async_send_message("Front door", title="Doorbell")
    kwargs = hud.show.await_args.kwargs
    assert kwargs["card"]["kind"] == "icon_text"
    assert kwargs["card"]["title"] == "Doorbell"
    assert kwargs["card"]["subtitle"] == "Front door"
