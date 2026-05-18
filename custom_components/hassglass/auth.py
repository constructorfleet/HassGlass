"""Per-device token issuance and validation.

Tokens are random 256-bit URL-safe strings. They're stored in the config entry's
data alongside the device record. Validation is constant-time.

This is a v1 simplification — eventually these should move into HA's
auth_token system so they show up under Profile → Long-lived tokens and can be
revoked from the standard UI. For now, revoke = remove the device entry.
"""

from __future__ import annotations

import hmac
import secrets
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .device import DeviceRecord

TOKEN_BYTES = 32  # 256 bits


def issue_token() -> str:
    """Generate a fresh per-device token."""
    return secrets.token_urlsafe(TOKEN_BYTES)


def verify_token(presented: str, stored: str) -> bool:
    """Constant-time token comparison."""
    return hmac.compare_digest(presented.encode("utf-8"), stored.encode("utf-8"))


def find_device_by_token(presented: str, devices: dict[str, DeviceRecord]) -> str | None:
    """Return the device_id whose token matches, or None.

    Iterates all devices and compares constant-time against each to avoid
    leaking which device_ids exist via timing.
    """
    matched: str | None = None
    for device_id, record in devices.items():
        if verify_token(presented, record.token):
            matched = device_id  # don't early-return — keep timing flat
    return matched
