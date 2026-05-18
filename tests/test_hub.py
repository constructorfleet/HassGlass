"""Tests for HassGlass hub device option persistence."""

from __future__ import annotations

from unittest.mock import MagicMock

from custom_components.hassglass.const import CONF_PIPELINE_ID
from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.hub import HassGlassHub


def _make_record(**overrides: object) -> DeviceRecord:
    values = {
        "device_id": "rokid-1",
        "serial": "SN",
        "firmware": "fw",
        "agent_version": "0.1.0",
        "token": "t",
        "name": "Test Glasses",
    }
    values.update(overrides)
    return DeviceRecord(**values)


async def test_set_device_pipeline_persists_override() -> None:
    hass = MagicMock()
    entry = MagicMock()
    entry.data = {"devices": {"rokid-1": _make_record().to_dict()}}
    entry.options = {CONF_PIPELINE_ID: "default-pipeline"}
    hub = HassGlassHub(hass, entry)

    await hub.set_device_pipeline("rokid-1", "kitchen-pipeline")

    record = hub.get_device("rokid-1")
    assert record is not None
    assert record.pipeline_id == "kitchen-pipeline"
    assert hub.resolved_options_for("rokid-1")[CONF_PIPELINE_ID] == "kitchen-pipeline"
    hass.config_entries.async_update_entry.assert_called_once()


async def test_set_device_pipeline_blank_clears_override() -> None:
    hass = MagicMock()
    entry = MagicMock()
    entry.data = {"devices": {"rokid-1": _make_record(pipeline_id="old").to_dict()}}
    entry.options = {CONF_PIPELINE_ID: "default-pipeline"}
    hub = HassGlassHub(hass, entry)

    await hub.set_device_pipeline("rokid-1", "")

    assert hub.get_device("rokid-1").pipeline_id is None
    assert hub.resolved_options_for("rokid-1")[CONF_PIPELINE_ID] == "default-pipeline"
