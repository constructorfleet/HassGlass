"""WebSocket endpoint and per-session reader task.

The view is registered at `/api/hassglass/ws/v1`. The Glass Agent connects with
`Authorization: Bearer <device-token>`, sends a `hello`, and is then expected
to keep the connection open indefinitely. The reader task fans inbound frames
out via the device's `DeviceBus`.
"""

from __future__ import annotations

import asyncio
import logging
from http import HTTPStatus
from typing import TYPE_CHECKING

from aiohttp import WSMsgType, web
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.http import HomeAssistantView

from .audio import run_assist_pipeline
from .auth import find_device_by_token
from .const import (
    DOMAIN,
    EVENT_BUTTON,
    EVENT_GESTURE,
    SIGNAL_DEVICE_UPDATED,
    SIGNAL_INPUT_BUTTON,
    SIGNAL_INPUT_GESTURE,
    WS_URL_PATH,
)
from .device import DeviceBus, GlassesRuntime, IncomingFrame
from .protocol import (
    MessageType,
    ProtocolError,
    decode_audio_frame,
    decode_message,
    validate_hello,
    validate_telemetry,
)

if TYPE_CHECKING:
    from . import HassGlassRuntimeData
    from .hub import HassGlassHub
    from .pipeline_bridge import PipelineBridge

_LOGGER = logging.getLogger(__name__)

_HEARTBEAT_S = 10.0
_HELLO_TIMEOUT_S = 5.0


class HassGlassWsView(HomeAssistantView):
    """aiohttp view that upgrades to a WebSocket for a paired device.

    Stateless: looks up hub and bridge on every new connection via self.hass
    so it stays correct across entry reloads and hub delete-then-recreate.
    """

    url = WS_URL_PATH
    name = "api:hassglass:ws"
    requires_auth = False  # we use our own per-device tokens

    def _get_runtime(self) -> HassGlassRuntimeData | None:
        entries = self.hass.config_entries.async_entries(DOMAIN)  # type: ignore[attr-defined]
        entry = next(
            (e for e in entries if e.unique_id == DOMAIN and hasattr(e, "runtime_data")),
            None,
        )
        return entry.runtime_data if entry is not None else None

    async def get(self, request: web.Request) -> web.StreamResponse:
        runtime = self._get_runtime()
        if runtime is None:
            return web.Response(status=HTTPStatus.SERVICE_UNAVAILABLE, text="hub not set up")
        hub: HassGlassHub = runtime.hub
        bridge: PipelineBridge = runtime.bridge

        token = _extract_bearer_token(request)
        if token is None:
            return web.Response(status=HTTPStatus.UNAUTHORIZED, text="missing bearer token")
        device_id = find_device_by_token(token, hub.devices)
        if device_id is None:
            return web.Response(status=HTTPStatus.UNAUTHORIZED, text="unknown token")
        record = hub.get_device(device_id)
        assert record is not None  # guarded by find_device_by_token

        ws = web.WebSocketResponse(heartbeat=_HEARTBEAT_S, autoping=True)
        await ws.prepare(request)

        glass_runtime = GlassesRuntime(record=record, ws=ws)
        # Drop any previous session for this device — the new one wins.
        await hub.detach_runtime(device_id)
        hub.attach_runtime(glass_runtime)
        _LOGGER.info("Glass Agent connected: device_id=%s", device_id)

        try:
            await _await_hello(glass_runtime)
            await _reader_loop(hub, bridge, glass_runtime)
        except _HelloError as exc:
            _LOGGER.warning("hello handshake failed for %s: %s", device_id, exc)
        finally:
            await hub.detach_runtime(device_id)
            _LOGGER.info("Glass Agent disconnected: device_id=%s", device_id)
        return ws


class _HelloError(Exception):
    """Raised when the hello handshake cannot complete."""


def _extract_bearer_token(request: web.Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith("bearer "):
        return None
    token = header[len("Bearer ") :].strip()
    return token or None


async def _await_hello(runtime: GlassesRuntime) -> None:
    """Wait for the first text frame and verify it's a valid hello."""
    try:
        msg = await asyncio.wait_for(runtime.ws.receive(), timeout=_HELLO_TIMEOUT_S)
    except TimeoutError as exc:
        raise _HelloError("no hello within timeout") from exc

    if msg.type is not WSMsgType.TEXT:
        raise _HelloError(f"first frame must be text, got {msg.type.name}")

    try:
        decoded = decode_message(msg.data)
        validate_hello(decoded)
    except ProtocolError as exc:
        raise _HelloError(str(exc)) from exc

    if decoded.data["device_id"] != runtime.record.device_id:
        raise _HelloError("hello device_id does not match authenticated device")

    await runtime.send_message(
        MessageType.HELLO_ACK,
        server="hassglass",
        protocol_version=decoded.data["protocol_version"],
    )


async def _reader_loop(hub: HassGlassHub, bridge: PipelineBridge, runtime: GlassesRuntime) -> None:
    """Read frames until the socket closes; publish to the device bus."""
    bus = hub.bus_for(runtime.record.device_id)
    async for ws_msg in runtime.ws:
        if ws_msg.type is WSMsgType.TEXT:
            await _handle_text(hub, bridge, runtime, bus, ws_msg.data)
        elif ws_msg.type is WSMsgType.BINARY:
            try:
                frame = decode_audio_frame(ws_msg.data)
            except ProtocolError as exc:
                _LOGGER.warning("bad binary frame from %s: %s", runtime.record.device_id, exc)
                continue
            bus.publish(IncomingFrame(audio=frame))
        elif ws_msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.CLOSING):
            break
        elif ws_msg.type is WSMsgType.ERROR:
            _LOGGER.warning("ws error on %s: %s", runtime.record.device_id, runtime.ws.exception())
            break


async def _handle_text(
    hub: HassGlassHub,
    bridge: PipelineBridge,
    runtime: GlassesRuntime,
    bus: DeviceBus,
    raw: str,
) -> None:
    try:
        decoded = decode_message(raw)
    except ProtocolError as exc:
        _LOGGER.warning("bad text frame from %s: %s", runtime.record.device_id, exc)
        return

    if decoded.type is MessageType.TELEMETRY:
        try:
            validate_telemetry(decoded)
        except ProtocolError as exc:
            _LOGGER.warning("bad telemetry from %s: %s", runtime.record.device_id, exc)
            return
        runtime.telemetry.update(decoded.data)
        async_dispatcher_send(hub.hass, SIGNAL_DEVICE_UPDATED, runtime.record.device_id)
    elif decoded.type is MessageType.PING:
        await runtime.send_message(MessageType.PONG)
    elif decoded.type is MessageType.AUDIO_START:
        hub.hass.async_create_task(
            run_assist_pipeline(
                hub.hass,
                hub,
                bridge,
                runtime,
                wake_word_phrase=_wake_word_phrase(decoded.data),
            )
        )
    elif decoded.type is MessageType.INPUT_GESTURE:
        _emit_input(
            hub,
            runtime.record.device_id,
            SIGNAL_INPUT_GESTURE,
            EVENT_GESTURE,
            decoded.data,
        )
    elif decoded.type is MessageType.INPUT_BUTTON:
        _emit_input(
            hub,
            runtime.record.device_id,
            SIGNAL_INPUT_BUTTON,
            EVENT_BUTTON,
            decoded.data,
        )

    bus.publish(IncomingFrame(message=decoded))


def _emit_input(
    hub: HassGlassHub,
    device_id: str,
    signal: str,
    bus_event: str,
    payload: dict[str, object],
) -> None:
    """Fan an input.* frame out to entities (via signal) and the HA event bus.

    Two channels because some user automations key off the HA bus event
    name (cleaner for `event` triggers) while the EventEntity needs the
    per-device dispatcher signal to update without polling.
    """
    dispatch_payload = {"device_id": device_id, **payload}
    async_dispatcher_send(hub.hass, signal, device_id, payload)
    hub.hass.bus.async_fire(bus_event, dispatch_payload)


def _wake_word_phrase(data: dict[str, object]) -> str | None:
    if data.get("trigger") != "wake_word":
        return None
    phrase = data.get("phrase")
    return phrase if isinstance(phrase, str) and phrase else None
