"""Card kinds and validation.

A *card* is an abstract description of something to render on the glasses HUD.
The integration sends cards as JSON; the Glass Agent draws them with native
Caps UI primitives — **never** as HTML or a webview.

Allowed kinds and their required fields are listed here. Anything not in this
schema is rejected before being sent to the agent.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Final

from .protocol import ProtocolError


class CardKind(StrEnum):
    TOAST = "toast"
    ICON_TEXT = "icon_text"
    LIST = "list"
    TIMER = "timer"
    ALERT = "alert"
    MEDIA = "media"


_REQUIRED_FIELDS: Final[dict[CardKind, tuple[str, ...]]] = {
    CardKind.TOAST: ("text",),
    CardKind.ICON_TEXT: ("title",),
    CardKind.LIST: ("title", "items"),
    CardKind.TIMER: ("label", "expires_at"),
    CardKind.ALERT: ("title", "body"),
    CardKind.MEDIA: ("title",),
}

_MAX_LIST_ITEMS: Final = 4
_MAX_PRIORITY: Final = 100
_MIN_PRIORITY: Final = 0


def validate_card(card: dict[str, Any]) -> None:
    """Validate a card dict before sending it to a glasses device."""
    kind_raw = card.get("kind")
    if not isinstance(kind_raw, str):
        raise ProtocolError("card.kind must be a string")
    try:
        kind = CardKind(kind_raw)
    except ValueError as exc:
        raise ProtocolError(f"unknown card kind: {kind_raw}") from exc

    for required in _REQUIRED_FIELDS[kind]:
        if required not in card:
            raise ProtocolError(f"card kind '{kind.value}' requires field '{required}'")

    if kind is CardKind.LIST:
        items = card["items"]
        if not isinstance(items, list) or not items:
            raise ProtocolError("list card requires a non-empty 'items' array")
        if len(items) > _MAX_LIST_ITEMS:
            raise ProtocolError(f"list card supports at most {_MAX_LIST_ITEMS} items")

    severity = card.get("severity")
    if severity is not None and severity not in ("info", "warning", "critical"):
        raise ProtocolError(f"unknown severity: {severity!r}")


def validate_priority(priority: int) -> int:
    if not isinstance(priority, int) or not (_MIN_PRIORITY <= priority <= _MAX_PRIORITY):
        raise ProtocolError(
            f"priority must be int in [{_MIN_PRIORITY}, {_MAX_PRIORITY}], got {priority!r}",
        )
    return priority
