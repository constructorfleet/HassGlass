"""Constants shared across the HassGlass integration."""

from __future__ import annotations

from typing import Final

DOMAIN: Final = "hassglass"
MANUFACTURER: Final = "Rokid"
DEFAULT_NAME: Final = "Rokid Glasses"

PROTOCOL_VERSION: Final = 1
WS_URL_PATH: Final = f"/api/{DOMAIN}/ws/v{PROTOCOL_VERSION}"

ZEROCONF_TYPE: Final = "_hassglass._tcp.local."

CONF_PAIRING_CODE: Final = "pairing_code"
CONF_DEVICE_ID: Final = "device_id"
CONF_DEVICE_TOKEN: Final = "device_token"  # noqa: S105 — config key, not a credential
CONF_DEVICE_SERIAL: Final = "serial"
CONF_DEVICE_FW: Final = "firmware"
CONF_AGENT_VERSION: Final = "agent_version"
CONF_PIPELINE_ID: Final = "pipeline_id"
CONF_WAKE_WORD_ENABLED: Final = "wake_word_enabled"
CONF_DEFAULT_TTL_MS: Final = "default_ttl_ms"
CONF_FALLBACK_MEDIA_PLAYER: Final = "fallback_media_player"

DEFAULT_TTL_MS: Final = 8000
DEFAULT_PAIRING_TIMEOUT_S: Final = 120

SIGNAL_DEVICE_UPDATED: Final = f"{DOMAIN}_device_updated"
SIGNAL_DEVICE_REMOVED: Final = f"{DOMAIN}_device_removed"

EVENT_GESTURE: Final = f"{DOMAIN}_gesture"
EVENT_BUTTON: Final = f"{DOMAIN}_button"
EVENT_CARD_DISMISSED: Final = f"{DOMAIN}_card_dismissed"
EVENT_WORN_CHANGED: Final = f"{DOMAIN}_worn_changed"
