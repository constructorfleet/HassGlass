"""Tests for card kind validation."""

from __future__ import annotations

import pytest

from custom_components.hassglass.cards import validate_card, validate_priority
from custom_components.hassglass.protocol import ProtocolError


class TestValidateCard:
    def test_toast_ok(self) -> None:
        validate_card({"kind": "toast", "text": "hi"})

    def test_icon_text_ok(self) -> None:
        validate_card({"kind": "icon_text", "title": "62°F", "icon": "weather-rainy"})

    def test_list_ok(self) -> None:
        validate_card({"kind": "list", "title": "Shopping", "items": ["a", "b"]})

    def test_list_rejects_empty(self) -> None:
        with pytest.raises(ProtocolError, match="non-empty"):
            validate_card({"kind": "list", "title": "x", "items": []})

    def test_list_rejects_too_many_items(self) -> None:
        with pytest.raises(ProtocolError, match="at most"):
            validate_card({"kind": "list", "title": "x", "items": list("abcde")})

    def test_unknown_kind(self) -> None:
        with pytest.raises(ProtocolError, match="unknown card kind"):
            validate_card({"kind": "hologram"})

    def test_missing_required_field(self) -> None:
        with pytest.raises(ProtocolError, match="requires field 'title'"):
            validate_card({"kind": "icon_text"})

    def test_non_string_kind(self) -> None:
        with pytest.raises(ProtocolError, match="must be a string"):
            validate_card({"kind": 7})

    def test_bad_severity(self) -> None:
        with pytest.raises(ProtocolError, match="severity"):
            validate_card({"kind": "toast", "text": "x", "severity": "panic"})


class TestValidatePriority:
    @pytest.mark.parametrize("ok", [0, 30, 100])
    def test_valid(self, ok: int) -> None:
        assert validate_priority(ok) == ok

    @pytest.mark.parametrize("bad", [-1, 101, 1.5, "high", None])
    def test_invalid(self, bad: object) -> None:
        with pytest.raises(ProtocolError, match="priority"):
            validate_priority(bad)  # type: ignore[arg-type]
