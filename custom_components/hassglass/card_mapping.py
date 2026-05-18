"""Map Assist pipeline replies into small HUD cards."""

from __future__ import annotations

import re
from typing import Any, Final

_BULLET_RE: Final = re.compile(r"^\s*(?:[-*]|\d+[.)])\s+(.+?)\s*$")
_WEATHER_RE: Final = re.compile(r"\b(weather|forecast|rain|snow|windy|sunny|cloudy)\b", re.I)
_MUSIC_RE: Final = re.compile(r"^playing\s+(?P<title>.+?)(?:\s+by\s+(?P<artist>.+?))?[.!]?$", re.I)
_MIN_LIST_LINES: Final = 2


def card_from_pipeline_reply(reply: str) -> dict[str, Any]:
    """Return the most useful HUD card for a spoken Assist reply.

    The mapper is intentionally heuristic. It uses only the final response text
    so it works across conversation agents, then returns one of the existing
    card kinds already validated by ``cards.py``.
    """
    text = _clean(reply)
    if not text:
        return {"kind": "toast", "text": "", "severity": "info"}

    list_card = _list_card(text)
    if list_card is not None:
        return list_card

    music_card = _music_card(text)
    if music_card is not None:
        return music_card

    if _WEATHER_RE.search(text):
        return {
            "kind": "icon_text",
            "title": "Weather",
            "body": text,
            "icon": "weather-partly-cloudy",
        }

    return {"kind": "toast", "text": text, "severity": "info"}


def _clean(value: str) -> str:
    return value.strip()


def _list_card(text: str) -> dict[str, Any] | None:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) < _MIN_LIST_LINES:
        return None

    title = lines[0].removesuffix(":").strip() or "List"
    items: list[str] = []
    for line in lines[1:]:
        match = _BULLET_RE.match(line)
        items.append(match.group(1) if match else line)

    if not items:
        return None

    return {"kind": "list", "title": title, "items": items[:4]}


def _music_card(text: str) -> dict[str, Any] | None:
    match = _MUSIC_RE.match(text)
    if match is None:
        return None

    card: dict[str, Any] = {"kind": "media", "title": match.group("title").strip()}
    artist = match.group("artist")
    if artist:
        card["subtitle"] = artist.strip()
    return card
