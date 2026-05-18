"""Tests for the per-device Assist pipeline select entity."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from custom_components.hassglass.const import CONF_PIPELINE_ID
from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.select import PipelineSelect


def _make_hub() -> MagicMock:
    record = DeviceRecord(
        device_id="rokid-1",
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token="t",
        name="Test Glasses",
    )
    hub = MagicMock()
    hub.get_device.return_value = record
    hub.resolved_options_for.return_value = {CONF_PIPELINE_ID: "default-pipeline"}
    hub.set_device_pipeline = AsyncMock()
    return hub


def test_pipeline_select_exposes_default_pipeline() -> None:
    entity = PipelineSelect(_make_hub(), "rokid-1")

    assert entity.current_option == "default-pipeline"


async def test_pipeline_select_updates_device_override() -> None:
    hub = _make_hub()
    entity = PipelineSelect(hub, "rokid-1")

    await entity.async_select_option("kitchen-pipeline")

    hub.set_device_pipeline.assert_awaited_once_with("rokid-1", "kitchen-pipeline")
