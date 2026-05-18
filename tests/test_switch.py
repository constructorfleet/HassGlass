"""Tests for the switch platform."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.switch import (
    ListeningEnabledSwitch,
    WakeWordSwitch,
)


def _record(wake: bool = True, listen: bool = True) -> DeviceRecord:
    return DeviceRecord(
        device_id="d1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test",
        wake_word_enabled=wake,
        listening_enabled=listen,
    )


@pytest.fixture
def hub_with_record() -> tuple[MagicMock, DeviceRecord]:
    """Hub mock that resolves get_device + the two setter methods."""
    record = _record()
    hub = MagicMock()
    hub.get_device.return_value = record
    hub.set_device_wake_word = MagicMock(side_effect=_make_setter(record, "wake_word_enabled"))
    hub.set_device_listening = MagicMock(side_effect=_make_setter(record, "listening_enabled"))
    return hub, record


def _make_setter(record: DeviceRecord, field: str):
    """Build an async setter that mutates the in-memory record."""

    async def _set(device_id: str, *, enabled: bool) -> None:
        assert device_id == record.device_id
        setattr(record, field, enabled)

    return _set


async def test_wake_word_switch_reflects_record(
    hub_with_record: tuple[MagicMock, DeviceRecord],
) -> None:
    hub, record = hub_with_record
    sw = WakeWordSwitch(hub, "d1")
    assert sw.is_on is True
    record.wake_word_enabled = False
    assert sw.is_on is False


async def test_wake_word_switch_toggles(
    hub_with_record: tuple[MagicMock, DeviceRecord],
) -> None:
    hub, record = hub_with_record
    sw = WakeWordSwitch(hub, "d1")
    await sw.async_turn_off()
    assert record.wake_word_enabled is False
    await sw.async_turn_on()
    assert record.wake_word_enabled is True


async def test_listening_enabled_switch_toggles(
    hub_with_record: tuple[MagicMock, DeviceRecord],
) -> None:
    hub, record = hub_with_record
    sw = ListeningEnabledSwitch(hub, "d1")
    assert sw.is_on is True
    await sw.async_turn_off()
    assert record.listening_enabled is False
    await sw.async_turn_on()
    assert record.listening_enabled is True


async def test_switch_available_when_paired_even_if_offline(
    hub_with_record: tuple[MagicMock, DeviceRecord],
) -> None:
    hub, _ = hub_with_record
    hub.runtime_for.return_value = None  # offline
    sw = WakeWordSwitch(hub, "d1")
    assert sw.available is True


async def test_switch_unavailable_when_device_not_paired() -> None:
    hub = MagicMock()
    hub.get_device.return_value = None
    sw = WakeWordSwitch(hub, "unknown")
    assert sw.available is False
