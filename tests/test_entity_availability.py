"""Availability behavior for paired HassGlass entities."""

from __future__ import annotations

from unittest.mock import MagicMock

from custom_components.hassglass.binary_sensor import WornBinarySensor
from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.media_player import GlassesMediaPlayer
from custom_components.hassglass.sensor import BatterySensor


def _record() -> DeviceRecord:
    return DeviceRecord(
        device_id="d1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test",
    )


def test_paired_sensor_stays_available_without_live_runtime() -> None:
    hub = MagicMock()
    hub.get_device.return_value = _record()
    hub.runtime_for.return_value = None

    entity = BatterySensor(hub, "d1")

    assert entity.available is True
    assert entity.native_value is None


def test_paired_binary_sensor_stays_available_without_live_runtime() -> None:
    hub = MagicMock()
    hub.get_device.return_value = _record()
    hub.runtime_for.return_value = None

    entity = WornBinarySensor(hub, "d1")

    assert entity.available is True
    assert entity.is_on is None


def test_paired_media_player_stays_available_without_live_runtime() -> None:
    hub = MagicMock()
    hub.get_device.return_value = _record()
    hub.runtime_for.return_value = None

    entity = GlassesMediaPlayer(hub, MagicMock(), "d1")

    assert entity.available is True
