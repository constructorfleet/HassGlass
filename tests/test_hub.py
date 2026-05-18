"""Tests for HassGlass hub device persistence and option resolution."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from custom_components.hassglass.const import CONF_PIPELINE_ID
from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.hub import HassGlassHub


def _make_record(**overrides: object) -> DeviceRecord:
    values: dict[str, Any] = {
        "device_id": "rokid-1",
        "serial": "SN",
        "firmware": "fw",
        "agent_version": "0.1.0",
        "token": "t",
        "name": "Test Glasses",
    }
    values.update(overrides)
    return DeviceRecord(**values)


def _build_hub(*, stored: dict[str, Any] | None = None) -> tuple[HassGlassHub, MagicMock]:
    """Return a hub whose Store is mocked to return `stored`."""
    hass = MagicMock()
    entry = MagicMock()
    entry.data = {}
    entry.options = {CONF_PIPELINE_ID: "default-pipeline"}
    hub = HassGlassHub(hass, entry)
    # Replace the Store with a mock so async_load is hermetic.
    hub._store = MagicMock()
    hub._store.async_load = AsyncMock(return_value=stored)
    hub._store.async_save = AsyncMock()
    return hub, hass


async def test_async_load_populates_devices_from_store() -> None:
    record = _make_record(pipeline_id="kitchen")
    hub, _ = _build_hub(stored={"rokid-1": record.to_dict()})
    await hub.async_load()
    loaded = hub.get_device("rokid-1")
    assert loaded is not None
    assert loaded.pipeline_id == "kitchen"


async def test_async_load_migrates_legacy_entry_data(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If entry.data["devices"] is populated and Store is empty, migrate once."""
    hass = MagicMock()
    entry = MagicMock()
    record_dict = _make_record().to_dict()
    entry.data = {"devices": {"rokid-1": record_dict}}
    entry.options = {CONF_PIPELINE_ID: "default"}
    hub = HassGlassHub(hass, entry)

    save_mock = AsyncMock()
    hub._store = MagicMock()
    hub._store.async_load = AsyncMock(return_value=None)
    hub._store.async_save = save_mock

    await hub.async_load()

    assert hub.get_device("rokid-1") is not None
    save_mock.assert_awaited_once()
    # Legacy "devices" key removed from entry.data after migration.
    hass.config_entries.async_update_entry.assert_called_once()
    new_data = hass.config_entries.async_update_entry.call_args.kwargs["data"]
    assert "devices" not in new_data


async def test_set_device_pipeline_persists_override() -> None:
    hub, _ = _build_hub(stored={"rokid-1": _make_record().to_dict()})
    await hub.async_load()
    await hub.set_device_pipeline("rokid-1", "kitchen-pipeline")

    record = hub.get_device("rokid-1")
    assert record is not None
    assert record.pipeline_id == "kitchen-pipeline"
    assert hub.resolved_options_for("rokid-1")[CONF_PIPELINE_ID] == "kitchen-pipeline"
    hub._store.async_save.assert_awaited()


async def test_set_device_pipeline_blank_clears_override() -> None:
    hub, _ = _build_hub(stored={"rokid-1": _make_record(pipeline_id="old").to_dict()})
    await hub.async_load()
    await hub.set_device_pipeline("rokid-1", "")

    record = hub.get_device("rokid-1")
    assert record is not None
    assert record.pipeline_id is None
    assert hub.resolved_options_for("rokid-1")[CONF_PIPELINE_ID] == "default-pipeline"


async def test_add_and_remove_device_persist_to_store() -> None:
    hub, _ = _build_hub(stored={})
    await hub.async_load()
    record = _make_record()
    await hub.add_device(record)
    assert hub.get_device("rokid-1") is record
    await hub.remove_device("rokid-1")
    assert hub.get_device("rokid-1") is None
    # add + remove => 2 saves
    assert hub._store.async_save.await_count == 2
