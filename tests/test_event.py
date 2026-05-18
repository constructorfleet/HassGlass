"""Tests for the gesture + button event platform."""

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.event import (
    BUTTON_EVENT_TYPES,
    GESTURE_EVENT_TYPES,
    ButtonEvent,
    GestureEvent,
)


def _record() -> DeviceRecord:
    return DeviceRecord(
        device_id="d1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test",
    )


@pytest.fixture
def hub() -> MagicMock:
    record = _record()
    hub = MagicMock()
    hub.get_device.return_value = record
    runtime = MagicMock(connected=True)
    hub.runtime_for.return_value = runtime
    return hub


def _fire_event(entity: GestureEvent | ButtonEvent, payload: dict[str, Any]) -> str | None:
    """Drive the entity's signal handler synchronously and return the event type fired."""
    fired: list[tuple[str, dict[str, Any]]] = []
    with (
        patch.object(
            entity, "_trigger_event", lambda kind, attrs=None: fired.append((kind, attrs or {}))
        ),
        patch.object(entity, "async_write_ha_state", MagicMock()),
    ):
        entity._handle_input(entity._device_id, payload)
    return fired[0][0] if fired else None


def test_gesture_event_fires_for_known_kind(hub: MagicMock) -> None:
    entity = GestureEvent(hub, "d1")
    fired = _fire_event(entity, {"kind": "swipe_forward", "duration_ms": 80})
    assert fired == "swipe_forward"


def test_gesture_event_ignores_unknown_kind(hub: MagicMock) -> None:
    entity = GestureEvent(hub, "d1")
    fired = _fire_event(entity, {"kind": "pinch_zoom"})
    assert fired is None


def test_gesture_event_ignores_other_device(hub: MagicMock) -> None:
    entity = GestureEvent(hub, "d1")
    fired: list[Any] = []
    with patch.object(entity, "_trigger_event", lambda *a, **kw: fired.append(a)):
        entity._handle_input("other-device", {"kind": "swipe_forward"})
    assert fired == []


def test_button_event_fires_for_action(hub: MagicMock) -> None:
    entity = ButtonEvent(hub, "d1")
    fired = _fire_event(entity, {"action": "side_press", "button": "side"})
    assert fired == "side_press"


def test_button_event_ignores_unknown_action(hub: MagicMock) -> None:
    entity = ButtonEvent(hub, "d1")
    fired = _fire_event(entity, {"action": "side_triple_press"})
    assert fired is None


def test_event_type_lists_are_complete() -> None:
    """Sanity check — keeps the platform docs and entity in sync."""
    assert "swipe_forward" in GESTURE_EVENT_TYPES
    assert "tap" in GESTURE_EVENT_TYPES
    assert "long_press" in GESTURE_EVENT_TYPES
    assert "head_nod" in GESTURE_EVENT_TYPES
    assert "head_shake" in GESTURE_EVENT_TYPES
    assert "side_press" in BUTTON_EVENT_TYPES
    assert "side_long_press" in BUTTON_EVENT_TYPES


def test_head_nod_fires_through_gesture_entity(hub: MagicMock) -> None:
    """Head-pose gestures share the same event entity as touchpad gestures."""
    entity = GestureEvent(hub, "d1")
    fired: list[str] = []
    with (
        patch.object(entity, "_trigger_event", lambda k, attrs=None: fired.append(k)),
        patch.object(entity, "async_write_ha_state", MagicMock()),
    ):
        entity._handle_input("d1", {"kind": "head_nod", "axis": "pitch"})
    assert fired == ["head_nod"]
