"""Tests for the pairing broker."""

from __future__ import annotations

import pytest

from custom_components.hassglass.device import DeviceRecord
from custom_components.hassglass.pairing import (
    PairingBroker,
    PairingError,
    generate_pairing_code,
)


def _make_record(token: str) -> DeviceRecord:
    return DeviceRecord(
        device_id="rokid-1",
        serial="SN1",
        firmware="fw",
        agent_version="0.1.0",
        token=token,
        name="Test glasses",
    )


def test_generate_pairing_code_is_six_digits() -> None:
    for _ in range(50):
        code = generate_pairing_code()
        assert len(code) == 6
        assert code.isdigit()


def test_complete_succeeds_with_matching_code() -> None:
    broker = PairingBroker()
    pending = broker.begin()
    record = broker.complete(pending.code, record_factory=_make_record)
    assert record.device_id == "rokid-1"
    assert record.token  # non-empty
    # `completed` future is lazy — only created when someone awaits.
    assert pending.completed is None


async def test_completed_future_resolves_for_async_waiter() -> None:
    broker = PairingBroker()
    pending = broker.begin()
    future = pending.ensure_future()
    record = broker.complete(pending.code, record_factory=_make_record)
    assert future.done()
    assert future.result() is record


def test_complete_with_unknown_code_raises() -> None:
    broker = PairingBroker()
    with pytest.raises(PairingError, match="unknown"):
        broker.complete("000000", record_factory=_make_record)


def test_expired_code_rejected() -> None:
    broker = PairingBroker(code_ttl_s=0.0)
    pending = broker.begin()
    with pytest.raises(PairingError, match="unknown or expired"):
        broker.complete(pending.code, record_factory=_make_record)


def test_lockout_after_repeated_failures() -> None:
    broker = PairingBroker()
    for _ in range(5):
        with pytest.raises(PairingError):
            broker.complete("000000", record_factory=_make_record)
    with pytest.raises(PairingError, match="locked"):
        broker.complete("000000", record_factory=_make_record)


def test_completing_consumes_pending() -> None:
    broker = PairingBroker()
    pending = broker.begin()
    broker.complete(pending.code, record_factory=_make_record)
    with pytest.raises(PairingError, match="unknown"):
        broker.complete(pending.code, record_factory=_make_record)
