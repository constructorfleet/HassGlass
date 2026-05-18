"""Tests for the HUD dispatcher.

Uses a MagicMock for `GlassesRuntime` so we can verify outbound calls without
needing a live aiohttp WebSocket. The runtime's real implementation is covered
indirectly via the protocol-codec tests.
"""

from __future__ import annotations

import time
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.hud import ActiveCard, HudDispatcher
from custom_components.hassglass.protocol import MessageType, ProtocolError


def _make_runtime() -> MagicMock:
    record = DeviceRecord(
        device_id="d1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test",
    )
    return MagicMock(
        spec=["record", "send_message", "current_card_id", "connected"],
        record=record,
        send_message=AsyncMock(),
        current_card_id=None,
        connected=True,
    )


@pytest.fixture
def fake_hub_and_runtime() -> tuple[MagicMock, MagicMock]:
    runtime = _make_runtime()
    hub = MagicMock()
    hub.runtime_for.return_value = runtime
    return hub, runtime


@pytest.fixture
def hud(fake_hub_and_runtime: tuple[MagicMock, MagicMock]) -> HudDispatcher:
    hub, _ = fake_hub_and_runtime
    return HudDispatcher(MagicMock(), hub)


def _await_call(send_message: AsyncMock) -> tuple[Any, dict[str, Any]]:
    args, kwargs = send_message.await_args
    return args[0], dict(kwargs)


async def test_show_sends_hud_show(
    hud: HudDispatcher,
    fake_hub_and_runtime: tuple[MagicMock, MagicMock],
) -> None:
    _, runtime = fake_hub_and_runtime
    await hud.show("d1", "weather", {"kind": "icon_text", "title": "62°F"})

    runtime.send_message.assert_awaited_once()
    msg_type, kwargs = _await_call(runtime.send_message)
    assert msg_type is MessageType.HUD_SHOW
    assert kwargs["id"] == "weather"
    assert kwargs["card"]["title"] == "62°F"
    assert kwargs["priority"] == 30  # default
    assert runtime.current_card_id == "weather"


async def test_show_validates_card(hud: HudDispatcher) -> None:
    with pytest.raises(ProtocolError):
        await hud.show("d1", "bad", {"kind": "icon_text"})  # missing title


async def test_show_with_ttl_includes_ttl_field(
    hud: HudDispatcher,
    fake_hub_and_runtime: tuple[MagicMock, MagicMock],
) -> None:
    _, runtime = fake_hub_and_runtime
    await hud.show("d1", "x", {"kind": "toast", "text": "go"}, ttl_ms=5000)
    _, kwargs = _await_call(runtime.send_message)
    assert kwargs["ttl_ms"] == 5000

    active = hud.state_for("d1").cards["x"]
    assert active.expires_at is not None
    assert active.expires_at > time.monotonic()


async def test_higher_priority_card_becomes_top(hud: HudDispatcher) -> None:
    await hud.show("d1", "music", {"kind": "media", "title": "song"}, priority=30)
    await hud.show("d1", "alert", {"kind": "alert", "title": "!", "body": "x"}, priority=90)
    top = hud.state_for("d1").top()
    assert top is not None
    assert top.card_id == "alert"


async def test_dismiss_removes_card(
    hud: HudDispatcher,
    fake_hub_and_runtime: tuple[MagicMock, MagicMock],
) -> None:
    _, runtime = fake_hub_and_runtime
    await hud.show("d1", "x", {"kind": "toast", "text": "hi"})
    await hud.dismiss("d1", "x")

    assert "x" not in hud.state_for("d1").cards
    msg_type, kwargs = _await_call(runtime.send_message)
    assert msg_type is MessageType.HUD_DISMISS
    assert kwargs["id"] == "x"
    assert runtime.current_card_id is None


async def test_dismiss_all(hud: HudDispatcher) -> None:
    await hud.show("d1", "a", {"kind": "toast", "text": "a"})
    await hud.show("d1", "b", {"kind": "toast", "text": "b"})
    await hud.dismiss("d1", "*")
    assert hud.state_for("d1").cards == {}


async def test_show_when_device_offline_is_a_no_op(
    hud: HudDispatcher,
    fake_hub_and_runtime: tuple[MagicMock, MagicMock],
) -> None:
    hub, runtime = fake_hub_and_runtime
    hub.runtime_for.return_value = None  # offline
    await hud.show("d1", "x", {"kind": "toast", "text": "hi"})
    runtime.send_message.assert_not_awaited()
    assert hud.state_for("d1").cards == {}


def test_prune_expired_returns_dropped_cards() -> None:
    state = HudDispatcher(MagicMock(), MagicMock()).state_for("d1")
    state.upsert(ActiveCard("a", {"kind": "toast"}, 30, expires_at=time.monotonic() - 1))
    state.upsert(ActiveCard("b", {"kind": "toast"}, 30, expires_at=None))
    state.upsert(ActiveCard("c", {"kind": "toast"}, 30, expires_at=time.monotonic() + 100))

    dropped = state.prune_expired(time.monotonic())
    assert {c.card_id for c in dropped} == {"a"}
    assert set(state.cards) == {"b", "c"}
