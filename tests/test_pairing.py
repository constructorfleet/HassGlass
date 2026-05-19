"""Tests for the agent-initiated pairing broker."""

from __future__ import annotations

import asyncio

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


def _claim(
    broker: PairingBroker,
    code: str = "123456",
    *,
    device_id: str = "rokid-1",
    name: str = "Test glasses",
) -> None:
    broker.claim(
        code,
        device_id=device_id,
        name=name,
        record_factory=_make_record,
    )


def test_generate_pairing_code_is_six_digits() -> None:
    for _ in range(50):
        code = generate_pairing_code()
        assert len(code) == 6
        assert code.isdigit()


def test_confirm_returns_record_for_matching_claim() -> None:
    broker = PairingBroker()
    _claim(broker, "123456")
    record = broker.confirm("123456")
    assert record.device_id == "rokid-1"
    assert record.token  # non-empty


async def test_confirm_resolves_parked_future() -> None:
    broker = PairingBroker()
    pending = broker.claim(
        "123456",
        device_id="rokid-1",
        name="Test glasses",
        record_factory=_make_record,
    )
    future = pending.ensure_future()
    record = broker.confirm("123456")
    assert future.done()
    assert future.result() is record


def test_confirm_with_unknown_code_raises() -> None:
    broker = PairingBroker()
    with pytest.raises(PairingError, match="unknown"):
        broker.confirm("000000")


def test_expired_claim_rejected() -> None:
    broker = PairingBroker(code_ttl_s=0.0)
    _claim(broker, "123456")
    with pytest.raises(PairingError, match="unknown or expired"):
        broker.confirm("123456")


async def test_expired_claim_cancels_parked_future() -> None:
    broker = PairingBroker(code_ttl_s=0.0)
    pending = broker.claim(
        "123456",
        device_id="rokid-1",
        name="Test glasses",
        record_factory=_make_record,
    )
    future = pending.ensure_future()
    with pytest.raises(PairingError, match="unknown or expired"):
        broker.confirm("123456")
    assert future.done()
    with pytest.raises(PairingError, match="timed out"):
        future.result()


def test_lockout_after_repeated_failures() -> None:
    broker = PairingBroker()
    for _ in range(5):
        with pytest.raises(PairingError):
            broker.confirm("000000")
    with pytest.raises(PairingError, match="locked"):
        broker.confirm("000000")


def test_confirming_consumes_claim() -> None:
    broker = PairingBroker()
    _claim(broker, "123456")
    broker.confirm("123456")
    with pytest.raises(PairingError, match="unknown"):
        broker.confirm("123456")


def test_replacement_claim_cancels_prior_future() -> None:
    broker = PairingBroker()
    pending_a = broker.claim(
        "123456",
        device_id="rokid-1",
        name="Test glasses",
        record_factory=_make_record,
    )
    # Whoever last claimed the code wins; the prior parked POST is cancelled.
    async def _drive() -> None:
        future = pending_a.ensure_future()
        broker.claim(
            "123456",
            device_id="rokid-1",
            name="Test glasses",
            record_factory=_make_record,
        )
        assert future.cancelled()

    asyncio.run(_drive())


def test_list_pending_excludes_expired() -> None:
    broker = PairingBroker(code_ttl_s=0.0)
    _claim(broker, "111111", device_id="a", name="A")
    pending = list(broker.list_pending())
    assert pending == []


def test_list_pending_returns_active_claims() -> None:
    broker = PairingBroker()
    _claim(broker, "111111", device_id="a", name="A")
    _claim(broker, "222222", device_id="b", name="B")
    codes = sorted(p.code for p in broker.list_pending())
    assert codes == ["111111", "222222"]
