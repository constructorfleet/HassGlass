"""Tests for the device-token auth module."""

from __future__ import annotations

from custom_components.hassglass.auth import (
    find_device_by_token,
    issue_token,
    verify_token,
)
from custom_components.hassglass.device import DeviceRecord


def _rec(device_id: str, token: str) -> DeviceRecord:
    return DeviceRecord(
        device_id=device_id,
        serial="SN",
        firmware="fw",
        agent_version="0.1.0",
        token=token,
        name=device_id,
    )


def test_issue_token_is_unique_and_long() -> None:
    tokens = {issue_token() for _ in range(100)}
    assert len(tokens) == 100
    for tok in tokens:
        assert len(tok) >= 32


def test_verify_token_matches_self() -> None:
    tok = issue_token()
    assert verify_token(tok, tok)
    assert not verify_token(tok, tok + "x")


def test_find_device_by_token_returns_matching_id() -> None:
    a, b = issue_token(), issue_token()
    devices = {"a": _rec("a", a), "b": _rec("b", b)}
    assert find_device_by_token(a, devices) == "a"
    assert find_device_by_token(b, devices) == "b"
    assert find_device_by_token("nope", devices) is None
