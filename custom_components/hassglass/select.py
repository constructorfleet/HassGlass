"""Select entities for per-device options."""

from __future__ import annotations

from typing import TYPE_CHECKING

from homeassistant.components.select import SelectEntity, SelectEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from .const import CONF_PIPELINE_ID, DOMAIN, SIGNAL_DEVICE_UPDATED
from .entity import HassGlassEntity

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant
    from homeassistant.helpers.entity_platform import AddEntitiesCallback

    from .hub import HassGlassHub


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create one select stack per paired device."""
    hub: HassGlassHub = entry.runtime_data.hub
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        known.update(new_devices)
        async_add_entities([PipelineSelect(hub, device_id) for device_id in new_devices])

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_PIPELINE = SelectEntityDescription(
    key="pipeline",
    translation_key="pipeline",
    entity_category=EntityCategory.CONFIG,
)


class PipelineSelect(HassGlassEntity, SelectEntity):
    """Per-device Assist pipeline override.

    Home Assistant's available pipeline list is intentionally not imported here
    yet because the integration still lazy-loads Assist dependencies in M3. The
    entity accepts arbitrary pipeline ids for now and shows the resolved id.
    """

    entity_description = _PIPELINE

    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id)
        self._attr_unique_id = f"{DOMAIN}_{device_id}_pipeline"
        self._attr_options = []

    @property
    def current_option(self) -> str | None:
        value = self._hub.resolved_options_for(self._device_id).get(CONF_PIPELINE_ID)
        return value if isinstance(value, str) else None

    async def async_select_option(self, option: str) -> None:
        await self._hub.set_device_pipeline(self._device_id, option)
