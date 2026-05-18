"""Pairing flow: a short-lived registry of 6-digit codes.

The user enters the code shown on the glasses HUD into the Home Assistant
config flow. The flow then waits up to `DEFAULT_PAIRING_TIMEOUT_S` for the
glasses to call our pairing HTTP endpoint with that code; on match we issue a
fresh token and persist the device.
"""

from __future__ import annotations

import asyncio
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass

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
    """Server-side state for an in-progress pairing.

    `completed` is only created if a coroutine actually wants to await the
    pairing — keeping it lazy means the broker is usable in plain sync code
    (e.g. unit tests) without a running event loop.
    """

    code: str
    expires_at: float
    completed: asyncio.Future[DeviceRecord] | None = None

    def is_expired(self, now: float | None = None) -> bool:
        return (now or time.monotonic()) > self.expires_at

    def ensure_future(self) -> asyncio.Future[DeviceRecord]:
        if self.completed is None:
            self.completed = asyncio.get_running_loop().create_future()
        return self.completed


class PairingBroker:
    """Holds pending pairings keyed by 6-digit code.

    Codes are short and easily brute-forceable, so:
    * each code lives at most 120 s,
    * only one pairing may be in flight per code,
    * after 5 wrong attempts on any code, the broker rate-limits new
      attempts for 60 s (defence-in-depth — pairing UI is also user-gated).
    """

    def __init__(self, *, code_ttl_s: float = _DEFAULT_TTL_S) -> None:
        self._pending: dict[str, PendingPairing] = {}
        self._code_ttl_s = code_ttl_s
        self._failed_attempts = 0
        self._lockout_until: float = 0.0

    def begin(self) -> PendingPairing:
        """Create and register a new pending pairing."""
        self._prune()
        # Avoid colliding codes.
        for _ in range(10):
            code = generate_pairing_code()
            if code not in self._pending:
                pending = PendingPairing(
                    code=code,
                    expires_at=time.monotonic() + self._code_ttl_s,
                )
                self._pending[code] = pending
                return pending
        msg = "could not generate non-colliding pairing code"
        raise RuntimeError(msg)

    def cancel(self, pending: PendingPairing) -> None:
        self._pending.pop(pending.code, None)
        if pending.completed is not None and not pending.completed.done():
            pending.completed.cancel()

    def complete(self, code: str, *, record_factory: _RecordFactory) -> DeviceRecord:
        """Match an inbound pairing claim against a pending code.

        On success: issues a token, builds the DeviceRecord, resolves the
        pending future, and removes the pending entry. Raises PairingError on
        any failure path.
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
        record = record_factory(token)
        self._pending.pop(code, None)
        if pending.completed is not None and not pending.completed.done():
            pending.completed.set_result(record)
        self._failed_attempts = 0
        return record

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
