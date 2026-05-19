"""Pairing flow: a short-lived registry of agent-initiated pairing claims.

The glasses generate a 6-digit code on-device, advertise themselves over
mDNS (`_hassglass._tcp.local.`), and POST the code to our pairing HTTP
endpoint. The POST is parked on a future. HA's zeroconf integration surfaces
the device under Integrations → Discovered; the user reads the code off the
HUD and enters it in HA. On match we resolve the parked future, hand the
agent its token, and persist the device.
"""

from __future__ import annotations

import asyncio
import secrets
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field

from .auth import issue_token
from .device import DeviceRecord

_DEFAULT_TTL_S = 120
_LOCKOUT_THRESHOLD = 5
_LOCKOUT_DURATION_S = 60

_RecordFactory = Callable[[str], DeviceRecord]


def generate_pairing_code() -> str:
    """Return a fresh 6-digit code as a zero-padded string."""
    return f"{secrets.randbelow(1_000_000):06d}"


@dataclass(slots=True)
class PendingPairing:
    """Server-side state for an in-progress, agent-initiated pairing.

    The agent's HTTP request parks on `completed`; HA's confirmation
    resolves it. `record_factory` was captured at claim time so the
    confirmation side doesn't need to know the agent's identity payload.

    `completed` is created lazily — the broker is usable in plain sync
    code (e.g. unit tests) without a running event loop.
    """

    code: str
    expires_at: float
    record_factory: _RecordFactory
    device_id: str
    name: str
    completed: asyncio.Future[DeviceRecord] | None = field(default=None)

    def is_expired(self, now: float | None = None) -> bool:
        return (now or time.monotonic()) > self.expires_at

    def ensure_future(self) -> asyncio.Future[DeviceRecord]:
        if self.completed is None:
            self.completed = asyncio.get_running_loop().create_future()
        return self.completed


class PairingBroker:
    """Holds agent-initiated pairing claims keyed by 6-digit code.

    Codes are short and easily brute-forceable, so:
    * each claim lives at most 120 s,
    * only one claim may be in flight per code,
    * after 5 wrong confirmation attempts on any code, the broker
      rate-limits new attempts for 60 s (defence-in-depth — the HA UI
      already gates this behind the user).
    """

    def __init__(self, *, code_ttl_s: float = _DEFAULT_TTL_S) -> None:
        self._pending: dict[str, PendingPairing] = {}
        self._code_ttl_s = code_ttl_s
        self._failed_attempts = 0
        self._lockout_until: float = 0.0

    def claim(
        self,
        code: str,
        *,
        device_id: str,
        name: str,
        record_factory: _RecordFactory,
    ) -> PendingPairing:
        """Register an agent-initiated pairing claim.

        Returns the pending entry. Replaces any existing claim with the same
        code — the freshest claim wins, and the prior request gets cancelled.
        """
        self._prune()
        existing = self._pending.get(code)
        if (
            existing is not None
            and existing.completed is not None
            and not existing.completed.done()
        ):
            existing.completed.cancel()
        pending = PendingPairing(
            code=code,
            expires_at=time.monotonic() + self._code_ttl_s,
            record_factory=record_factory,
            device_id=device_id,
            name=name,
        )
        self._pending[code] = pending
        return pending

    def cancel(self, pending: PendingPairing) -> None:
        """Drop a pending claim and cancel its future, if any."""
        self._pending.pop(pending.code, None)
        if pending.completed is not None and not pending.completed.done():
            pending.completed.cancel()

    def confirm(self, code: str) -> DeviceRecord:
        """Match a user-entered confirmation code to a pending claim.

        On success: issues a token, builds the DeviceRecord via the factory
        captured at claim time, resolves the pending future, removes the
        entry, and returns the record. Raises PairingError on any failure.
        """
        now = time.monotonic()
        if now < self._lockout_until:
            raise PairingError("pairing temporarily locked due to failed attempts")
        self._prune(now)
        pending = self._pending.get(code)
        if pending is None or pending.is_expired(now):
            self._note_failure()
            raise PairingError("unknown or expired pairing code")
        token = issue_token()
        record = pending.record_factory(token)
        self._pending.pop(code, None)
        if pending.completed is not None and not pending.completed.done():
            pending.completed.set_result(record)
        self._failed_attempts = 0
        return record

    def list_pending(self) -> Iterable[PendingPairing]:
        """Return non-expired pending claims (for UI surfacing)."""
        self._prune()
        return tuple(self._pending.values())

    def _note_failure(self) -> None:
        self._failed_attempts += 1
        if self._failed_attempts >= _LOCKOUT_THRESHOLD:
            self._lockout_until = time.monotonic() + _LOCKOUT_DURATION_S
            self._failed_attempts = 0

    def _prune(self, now: float | None = None) -> None:
        now = now or time.monotonic()
        for code, pending in list(self._pending.items()):
            if pending.is_expired(now):
                self._pending.pop(code, None)
                if pending.completed is not None and not pending.completed.done():
                    pending.completed.set_exception(PairingError("pairing timed out"))


class PairingError(Exception):
    """Raised when a pairing attempt cannot be completed."""
