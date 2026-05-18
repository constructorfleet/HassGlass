"""Bridge Home Assistant Assist pipeline events to a Glass Agent runtime."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from .card_mapping import card_from_pipeline_reply
from .protocol import MessageType

if TYPE_CHECKING:
    from .hub import HassGlassHub
    from .hud import HudDispatcher
    from .tts_relay import TtsRelay


_EVENT_STATES: dict[str, str] = {
    "run-start": "starting",
    "wake_word-start": "wake_word",
    "wake_word-end": "wake_word_done",
    "stt-start": "listening",
    "stt-vad-start": "speech",
    "stt-vad-end": "speech_done",
    "stt-end": "heard",
    "intent-start": "thinking",
    "intent-progress": "thinking",
    "intent-end": "intent_done",
    "tts-start": "replying",
    "tts-end": "done",
    "run-end": "idle",
    "error": "error",
}

_PIPELINE_REPLY_CARD_ID = "pipeline-reply"
_PIPELINE_REPLY_PRIORITY = 40


class PipelineBridge:
    """Translate Assist pipeline events into Glass Agent protocol messages."""

    def __init__(
        self,
        hub: HassGlassHub,
        hud: HudDispatcher,
        tts_relay: TtsRelay | None = None,
    ) -> None:
        self._hub = hub
        self._hud = hud
        self._tts_relay = tts_relay
        self._last_tts_text: dict[str, str] = {}

    async def handle_event(self, device_id: str, event: object) -> None:
        """Forward one pipeline event and render reply cards when possible."""
        runtime = self._hub.runtime_for(device_id)
        if runtime is None:
            return

        event_type = _event_type(event)
        data = _event_data(event)
        timestamp = _event_timestamp(event)

        _update_runtime_state(runtime, event_type, data)

        await runtime.send_message(
            MessageType.PIPELINE_EVENT,
            event=event_type,
            state=_EVENT_STATES.get(event_type, "unknown"),
            data=data,
            timestamp=timestamp,
        )

        if event_type == "tts-start":
            tts_input = data.get("tts_input")
            if isinstance(tts_input, str) and tts_input.strip():
                self._last_tts_text[device_id] = tts_input
        elif event_type == "tts-end":
            if self._tts_relay is not None:
                tts_output = data.get("tts_output")
                if isinstance(tts_output, dict):
                    await self._tts_relay.relay(runtime, tts_output)
            await self._render_reply_card(device_id)

    async def _render_reply_card(self, device_id: str) -> None:
        reply = self._last_tts_text.pop(device_id, None)
        if not reply:
            return

        await self._hud.show(
            device_id,
            _PIPELINE_REPLY_CARD_ID,
            card_from_pipeline_reply(reply),
            priority=_PIPELINE_REPLY_PRIORITY,
            ttl_ms=self._hub.default_ttl_ms,
        )


def _event_type(event: object) -> str:
    raw = event.get("type") if isinstance(event, dict) else getattr(event, "type", None)
    value = getattr(raw, "value", raw)
    return str(value) if value is not None else "unknown"


def _event_data(event: object) -> dict[str, Any]:
    raw = event.get("data") if isinstance(event, dict) else getattr(event, "data", None)
    return dict(raw) if isinstance(raw, dict) else {}


def _event_timestamp(event: object) -> str | None:
    raw = event.get("timestamp") if isinstance(event, dict) else getattr(event, "timestamp", None)
    return str(raw) if raw is not None else None


def _update_runtime_state(runtime: Any, event_type: str, data: dict[str, Any]) -> None:
    if event_type == "stt-start":
        runtime.listening = True
    elif event_type in {"stt-end", "run-end", "error"}:
        runtime.listening = False

    if event_type == "stt-end":
        stt_output = data.get("stt_output")
        if isinstance(stt_output, dict) and isinstance(stt_output.get("text"), str):
            runtime.last_intent = stt_output["text"]
