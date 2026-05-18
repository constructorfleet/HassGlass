"""Unit tests for the hassglass/1 wire protocol codec.

These tests have no Home Assistant dependency — they run against `protocol.py`
in isolation and double as the spec for the Kotlin port on the Glass Agent.
"""

from __future__ import annotations

import json

import pytest

from custom_components.hassglass.protocol import (
    PROTOCOL_VERSION,
    AudioChannel,
    Message,
    MessageType,
    ProtocolError,
    decode_audio_frame,
    decode_message,
    encode_audio_frame,
    encode_message,
    validate_hello,
    validate_hud_show,
    validate_telemetry,
)


class TestAudioFraming:
    def test_round_trip_mic_up(self) -> None:
        payload = b"\x01\x02\x03\x04" * 100
        encoded = encode_audio_frame(AudioChannel.MIC_UP, seq=42, payload=payload)
        decoded = decode_audio_frame(encoded)
        assert decoded.channel is AudioChannel.MIC_UP
        assert decoded.seq == 42
        assert decoded.payload == payload

    def test_round_trip_tts_down_empty_payload(self) -> None:
        encoded = encode_audio_frame(AudioChannel.TTS_DOWN, seq=0, payload=b"")
        decoded = decode_audio_frame(encoded)
        assert decoded.channel is AudioChannel.TTS_DOWN
        assert decoded.seq == 0
        assert decoded.payload == b""

    def test_seq_high_bound(self) -> None:
        encoded = encode_audio_frame(AudioChannel.MIC_UP, seq=0xFFFFFFFF, payload=b"x")
        assert decode_audio_frame(encoded).seq == 0xFFFFFFFF

    def test_seq_out_of_range_rejected(self) -> None:
        with pytest.raises(ProtocolError, match="seq"):
            encode_audio_frame(AudioChannel.MIC_UP, seq=0x1_0000_0000, payload=b"x")

    def test_short_frame_rejected(self) -> None:
        with pytest.raises(ProtocolError, match="too short"):
            decode_audio_frame(b"\x01\x00")

    def test_unknown_channel_rejected(self) -> None:
        raw = b"\xff\x00\x00\x00\x00\x00\x00\x00payload"
        with pytest.raises(ProtocolError, match="unknown audio channel"):
            decode_audio_frame(raw)


class TestControlPlane:
    def test_encode_round_trip_minimal(self) -> None:
        encoded = encode_message(MessageType.PING)
        msg = decode_message(encoded)
        assert msg.type is MessageType.PING
        assert msg.data == {}

    def test_encode_round_trip_with_fields(self) -> None:
        encoded = encode_message(MessageType.TELEMETRY, battery_pct=87, rssi_dbm=-62)
        msg = decode_message(encoded)
        assert msg.type is MessageType.TELEMETRY
        assert msg.data == {"battery_pct": 87, "rssi_dbm": -62}

    def test_decode_preserves_unknown_fields(self) -> None:
        """Forward-compat: unknown fields on a known type pass through."""
        raw = json.dumps({"type": "telemetry", "battery_pct": 50, "future_field": "abc"})
        msg = decode_message(raw)
        assert msg.data["future_field"] == "abc"

    def test_decode_unknown_type_rejected(self) -> None:
        raw = json.dumps({"type": "hud.morph"})
        with pytest.raises(ProtocolError, match="unknown message type"):
            decode_message(raw)

    def test_decode_missing_type_rejected(self) -> None:
        with pytest.raises(ProtocolError, match="missing 'type'"):
            decode_message(json.dumps({"battery_pct": 50}))

    def test_decode_invalid_json_rejected(self) -> None:
        with pytest.raises(ProtocolError, match="invalid JSON"):
            decode_message("{not json")

    def test_decode_non_object_rejected(self) -> None:
        with pytest.raises(ProtocolError, match="JSON object"):
            decode_message("[]")

    def test_encoding_is_compact(self) -> None:
        """No whitespace — important for audio-adjacent frame budgets."""
        encoded = encode_message(MessageType.TELEMETRY, battery_pct=50)
        assert " " not in encoded


class TestHelloValidation:
    def _valid(self) -> Message:
        return Message(
            type=MessageType.HELLO,
            data={
                "device_id": "rokid-001",
                "serial": "SN12345",
                "firmware": "yoda-1.4.0",
                "agent_version": "0.1.0",
                "protocol_version": PROTOCOL_VERSION,
            },
        )

    def test_accepts_valid_hello(self) -> None:
        validate_hello(self._valid())

    def test_rejects_wrong_type(self) -> None:
        msg = Message(type=MessageType.PING, data={})
        with pytest.raises(ProtocolError, match="expected hello"):
            validate_hello(msg)

    @pytest.mark.parametrize(
        "missing",
        ["device_id", "serial", "firmware", "agent_version", "protocol_version"],
    )
    def test_rejects_missing_required(self, missing: str) -> None:
        msg = self._valid()
        del msg.data[missing]
        with pytest.raises(ProtocolError, match=missing):
            validate_hello(msg)

    def test_rejects_wrong_protocol_version(self) -> None:
        msg = self._valid()
        msg.data["protocol_version"] = PROTOCOL_VERSION + 1
        with pytest.raises(ProtocolError, match="unsupported protocol_version"):
            validate_hello(msg)


class TestTelemetryValidation:
    def test_accepts_full_telemetry(self) -> None:
        msg = Message(
            type=MessageType.TELEMETRY,
            data={"battery_pct": 80, "rssi_dbm": -55, "charging": True},
        )
        validate_telemetry(msg)

    def test_accepts_partial_telemetry(self) -> None:
        validate_telemetry(Message(type=MessageType.TELEMETRY, data={"battery_pct": 80}))
        validate_telemetry(Message(type=MessageType.TELEMETRY, data={}))

    @pytest.mark.parametrize("bad", [-1, 101, "high", None])
    def test_rejects_bad_battery(self, bad: object) -> None:
        if bad is None:
            return  # None means "absent" which is allowed
        msg = Message(type=MessageType.TELEMETRY, data={"battery_pct": bad})
        with pytest.raises(ProtocolError, match="battery_pct"):
            validate_telemetry(msg)

    def test_rejects_non_numeric_rssi(self) -> None:
        msg = Message(type=MessageType.TELEMETRY, data={"rssi_dbm": "strong"})
        with pytest.raises(ProtocolError, match="rssi_dbm"):
            validate_telemetry(msg)


class TestHudShowValidation:
    def test_accepts_minimal(self) -> None:
        msg = Message(
            type=MessageType.HUD_SHOW,
            data={"id": "weather-1", "card": {"kind": "toast", "text": "Lights on"}},
        )
        validate_hud_show(msg)

    def test_rejects_missing_id(self) -> None:
        msg = Message(type=MessageType.HUD_SHOW, data={"card": {"kind": "toast"}})
        with pytest.raises(ProtocolError, match="'id'"):
            validate_hud_show(msg)

    def test_rejects_card_without_kind(self) -> None:
        msg = Message(type=MessageType.HUD_SHOW, data={"id": "x", "card": {}})
        with pytest.raises(ProtocolError, match="kind"):
            validate_hud_show(msg)
