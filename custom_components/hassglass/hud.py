"""HUD card dispatcher.

The integration side owns a logical view of which cards are *active* on each
device. The Glass Agent maintains its own priority stack, but the integration
keeps a shadow copy so HA entities (`sensor.<glasses>_current_card`) can stay
accurate and so we can address-by-id when an automation dismisses a card.

Cards are addressed by a client-supplied `id` so an automation that issues a
"weather" card every 10 minutes can keep updating the same on-screen element
rather than stacking duplicates.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from homeassistant.helpers.dispatcher import async_dispatcher_send

from .cards import validate_card, validate_priority
from .const import SIGNAL_DEVICE_UPDATED
from .protocol import MessageType

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant

    from .hub import HassGlassHub

_LOGGER = logging.getLogger(__name__)

_DEFAULT_PRIORITY = 30


@dataclass(slots=True)
class ActiveCard:
    """Integration-side shadow of a card the agent is rendering."""

    card_id: str
    card: dict[str, Any]
    priority: int
    expires_at: float | None  # monotonic seconds; None = no TTL


@dataclass(slots=True)
class DeviceHudState:
    """Per-device active card stack, ordered by priority desc + age."""

    cards: dict[str, ActiveCard] = field(default_factory=dict)

    def top(self) -> ActiveCard | None:
        if not self.cards:
            return None
        return max(self.cards.values(), key=lambda c: c.priority)

    def upsert(self, card: ActiveCard) -> None:
        self.cards[card.card_id] = card

    def remove(self, card_id: str) -> ActiveCard | None:
        return self.cards.pop(card_id, None)

    def clear(self) -> list[ActiveCard]:
        cleared = list(self.cards.values())
        self.cards.clear()
        return cleared

    def prune_expired(self, now: float) -> list[ActiveCard]:
        expired = [
            c for c in self.cards.values() if c.expires_at is not None and c.expires_at <= now
        ]
        for c in expired:
            self.cards.pop(c.card_id, None)
        return expired


class HudDispatcher:
    """Sends hud.* messages to devices and maintains the shadow state."""

    def __init__(self, hass: HomeAssistant, hub: HassGlassHub) -> None:
        self._hass = hass
        self._hub = hub
        self._states: dict[str, DeviceHudState] = {}

    def state_for(self, device_id: str) -> DeviceHudState:
        if device_id not in self._states:
            self._states[device_id] = DeviceHudState()
        return self._states[device_id]

    async def show(
        self,
        device_id: str,
        card_id: str,
        card: dict[str, Any],
        *,
        priority: int = _DEFAULT_PRIORITY,
        ttl_ms: int | None = None,
    ) -> None:
        validate_card(card)
        validate_priority(priority)

        runtime = self._hub.runtime_for(device_id)
        if runtime is None:
            _LOGGER.debug("show dropped: device %s is offline", device_id)
            return

        expires_at = (time.monotonic() + ttl_ms / 1000.0) if ttl_ms else None
        state = self.state_for(device_id)
        state.upsert(
            ActiveCard(card_id=card_id, card=card, priority=priority, expires_at=expires_at),
        )

        payload: dict[str, Any] = {"id": card_id, "card": card, "priority": priority}
        if ttl_ms is not None:
            payload["ttl_ms"] = ttl_ms
        await runtime.send_message(MessageType.HUD_SHOW, **payload)
        self._publish_state(runtime, state)

    async def dismiss(self, device_id: str, card_id: str) -> None:
        runtime = self._hub.runtime_for(device_id)
        state = self.state_for(device_id)
        if card_id == "*":
            state.clear()
        else:
            state.remove(card_id)
        if runtime is not None:
            await runtime.send_message(MessageType.HUD_DISMISS, id=card_id)
        self._publish_state(runtime, state)

    def _publish_state(self, runtime: Any, state: DeviceHudState) -> None:
        if runtime is None:
            return
        top = state.top()
        runtime.current_card_id = top.card_id if top else None
        async_dispatcher_send(self._hass, SIGNAL_DEVICE_UPDATED, runtime.record.device_id)
