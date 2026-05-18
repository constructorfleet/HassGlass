"""Event entities for touchpad gestures and side-button presses."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from homeassistant.components.event import (
    EventDeviceClass,
    EventEntity,
    EventEntityDescription,
)
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from .const import (
    DOMAIN,
    SIGNAL_DEVICE_UPDATED,
    SIGNAL_INPUT_BUTTON,
    SIGNAL_INPUT_GESTURE,
)
from .entity import HassGlassEntity

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant
    from homeassistant.helpers.entity_platform import AddEntitiesCallback

    from .hub import HassGlassHub


GESTURE_EVENT_TYPES: tuple[str, ...] = (
    "swipe_forward",
    "swipe_back",
    "swipe_up",
    "swipe_down",
    "tap",
    "double_tap",
    "long_press",
    "head_nod",
    "head_shake",
)

BUTTON_EVENT_TYPES: tuple[str, ...] = (
    "side_press",
    "side_long_press",
    "side_double_press",
    "side_release",
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create gesture + button event entities per paired device."""
    hub: HassGlassHub = entry.runtime_data.hub
    known: set[str] = set()

    @callback
    def _sync_devices(_device_id: str | None = None) -> None:
        new_devices = set(hub.devices) - known
        if not new_devices:
            return
        entities: list[EventEntity] = [
            cls(hub, device_id) for device_id in new_devices for cls in (GestureEvent, ButtonEvent)
        ]
        known.update(new_devices)
        async_add_entities(entities)

    _sync_devices()
    entry.async_on_unload(
        async_dispatcher_connect(hass, SIGNAL_DEVICE_UPDATED, _sync_devices),
    )


_GESTURE_DESC = EventEntityDescription(
    key="gesture",
    translation_key="gesture",
    event_types=list(GESTURE_EVENT_TYPES),
)

_BUTTON_DESC = EventEntityDescription(
    key="button",
    translation_key="button",
    device_class=EventDeviceClass.BUTTON,
    event_types=list(BUTTON_EVENT_TYPES),
)


class _BaseEventEntity(HassGlassEntity, EventEntity):
    """Bridge a per-device dispatcher signal to EventEntity._trigger_event."""

    _signal: str
    _kind_field: str
    _allowed_types: tuple[str, ...]

    def __init__(
        self,
        hub: HassGlassHub,
        device_id: str,
        description: EventEntityDescription,
    ) -> None:
        super().__init__(hub, device_id)
        self.entity_description = description
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{description.key}"

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(self.hass, self._signal, self._handle_input),
        )

    @callback
    def _handle_input(self, device_id: str, payload: dict[str, Any]) -> None:
        if device_id != self._device_id:
            return
        kind = payload.get(self._kind_field)
        if not isinstance(kind, str) or kind not in self._allowed_types:
            return
        attrs = {k: v for k, v in payload.items() if k != self._kind_field}
        self._trigger_event(kind, attrs)
        self.async_write_ha_state()


class GestureEvent(_BaseEventEntity):
    """Touchpad gesture event — kind in `GESTURE_EVENT_TYPES`."""

    _signal = SIGNAL_INPUT_GESTURE
    _kind_field = "kind"
    _allowed_types = GESTURE_EVENT_TYPES

    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _GESTURE_DESC)


class ButtonEvent(_BaseEventEntity):
    """Side-button event — kind in `BUTTON_EVENT_TYPES`."""

    _signal = SIGNAL_INPUT_BUTTON
    _kind_field = "action"
    _allowed_types = BUTTON_EVENT_TYPES

    def __init__(self, hub: HassGlassHub, device_id: str) -> None:
        super().__init__(hub, device_id, _BUTTON_DESC)
