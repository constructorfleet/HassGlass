# HassGlass Architecture

This document describes the end-to-end design of HassGlass: how a pair of Rokid AR glasses participates in a Home Assistant Assist pipeline, how audio and HUD content move across the wire, and how the integration is layered on the HA side.

---

## 1. High-level topology

```
┌─────────────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Rokid Glasses (YodaOS / Android)        │        │ Home Assistant                           │
│                                         │        │                                          │
│  ┌────────────────────────────────────┐ │        │  ┌────────────────────────────────────┐  │
│  │ HassGlass Glass Agent (CXR-L app)  │ │        │  │ custom_components/hassglass        │  │
│  │  • mic capture (AEC)               │ │  WSS   │  │  • device registry / config_flow   │  │
│  │  • HUD renderer (Caps cards)       │◄┼────────┼─►│  • Wyoming-compatible audio bridge │  │
│  │  • IMU + touchpad + button events  │ │ JSON+  │  │  • HUD card dispatcher             │  │
│  │  • TTS audio playback              │ │ binary │  │  • services, entities, events      │  │
│  └────────────────────────────────────┘ │ frames │  └─────────────┬──────────────────────┘  │
│                                         │        │                │                         │
└──────────────────────────────────────────┘        │                ▼                         │
                                                    │     Home Assistant Assist pipeline       │
                                                    │     (STT → conversation → TTS)           │
                                                    └──────────────────────────────────────────┘

         (optional, for first-time pairing or off-LAN use)
┌──────────────────────────────────────┐
│ HassGlass Companion (iOS, CXR-M)     │  BLE GATT / Wi-Fi Direct
│  • initial Wi-Fi onboarding          │◄────────────────────► Glasses
│  • BLE relay when off home Wi-Fi     │
└──────────────────────────────────────┘
```

There are three software components in the system:

1. **Glass Agent** — an on-glasses Android app built against the Rokid **CXR-L** SDK. It captures mic audio, renders HUD cards, surfaces hardware events, and plays back TTS. It is the only component that runs on the glasses.
2. **HA Integration (`custom_components/hassglass`)** — the HACS-installable Python package. It owns device discovery, config flow, entity creation, the WebSocket server endpoint that the Glass Agent connects to, and the bridge into `assist_pipeline`.
3. **Companion App** (optional) — an iOS app built against **CXR-M**. Needed only for first-time Wi-Fi onboarding of the glasses and as a BLE-relay fallback when the glasses are away from the home Wi-Fi. (Android companion is a post-1.0 candidate.)

The glasses and HA communicate **directly over the home LAN** via a single multiplexed WebSocket. No Rokid cloud, no Anthropic / OpenAI cloud unless the user's Assist pipeline already uses one.

---

## 2. Rokid transport layer

### 2.1 SDK choice

| Rokid SDK | Where it runs | Role in HassGlass |
|---|---|---|
| **CXR-L** | On the glasses (YodaOS) | **Primary.** Glass Agent app binds to YodaOS AI service via AIDL and replaces the default assistant on the wake-word path. |
| **CXR-M** | iOS phone (v1); Android (post-1.0) | **Optional companion.** Used only for BLE pairing, Wi-Fi onboarding, and as a relay when off-LAN. |
| **CXR-S** | On the glasses | Not used in v1 — covered by CXR-L. |

Choosing CXR-L as primary means the glasses are a **first-class network citizen**: once on Wi-Fi they reach HA directly and the companion app is no longer in the critical path.

### 2.2 Wire protocol — `hassglass/1` over WebSocket

The Glass Agent opens a single TLS-terminated WebSocket to the HA integration:

```
wss://homeassistant.local:8123/api/hassglass/ws
```

Authentication uses a long-lived access token issued during pairing, sent as `Authorization: Bearer …` on the upgrade request — same model HA uses for its native WS API.

The connection is **bidirectional and multiplexed**. Frames are either:

- **Binary frames**: 16 kHz mono PCM (or Opus, negotiated) audio chunks, prefixed with a 4-byte little-endian channel id and a 4-byte sequence number. Channels: `0x01` mic-up, `0x02` tts-down.
- **Text frames**: JSON envelopes, one message per frame:

```json
{ "type": "hud.show", "id": "weather-now", "ttl_ms": 8000,
  "card": { "kind": "icon_text", "icon": "weather-rainy",
            "title": "62°F", "subtitle": "Rain in 20 min" } }
```

Message types (initial set):

| Type | Direction | Purpose |
|---|---|---|
| `hello` | Glass → HA | Identifies device (serial, fw, agent version, capabilities). |
| `pong` / `ping` | Both | Liveness; 10 s interval. |
| `audio.start` / `audio.stop` | Glass → HA | Brackets a mic capture session (wake word or button-initiated). |
| `pipeline.event` | HA → Glass | Mirrors `assist_pipeline` events (stt-start, intent-end, tts-start, error). Glass uses these to drive a default HUD "I'm listening / thinking / replying" state. |
| `hud.show` / `hud.dismiss` / `hud.update` | HA → Glass | Push a card to the HUD. |
| `input.gesture` / `input.button` / `input.head_pose` | Glass → HA | Hardware input → HA `event` entity. |
| `telemetry` | Glass → HA | Battery, charging, signal, mic level; throttled to 1 Hz. |
| `error` | Both | Structured error with reconnect/recover hints. |

The protocol is versioned by the URL path (`/api/hassglass/ws/v1`) and by a `protocol_version` field in `hello`. Breaking changes bump both.

### 2.3 Fallback: BLE-bridged mode

When the glasses are off Wi-Fi (e.g. user walked outside), the Companion app on the user's phone can act as a relay:

```
Glasses ── BLE GATT ──► Companion App ── HTTPS/WSS ──► Home Assistant
```

Only the **control plane** (HUD updates, simple notifications, intent commands) is relayed in this mode; live audio capture is too bandwidth-heavy for BLE. The integration advertises capabilities per session in `hello` so the HA side knows whether to enable wake-word audio streaming.

---

## 3. Assist pipeline integration

HassGlass does **not** invent a new pipeline. It plugs the glasses' mic + speaker into Home Assistant's existing `assist_pipeline` machinery, the same way Wyoming satellites and ESPHome voice assistant devices do.

### 3.1 Audio capture → STT

```
Glass mic → AEC (NXP RT600) → 16 kHz PCM → WS binary frame ch=0x01
                                              │
                                              ▼
                       custom_components/hassglass/audio.py
                                              │ (async generator of bytes)
                                              ▼
       assist_pipeline.async_pipeline_from_audio_stream(
           hass, context, event_callback,
           stt_metadata=..., stt_stream=audio_gen,
           pipeline_id=device_pipeline_id, ...)
```

Each glasses device has an associated `pipeline_id` (configurable per device, defaults to the user's preferred Assist pipeline). This is the same selector pattern View Assist uses for per-satellite pipeline overrides.

### 3.2 Pipeline events → HUD state machine

The integration subscribes to pipeline events and forwards a digested view of them to the glasses as `pipeline.event` messages:

| Pipeline event | HUD state on glasses |
|---|---|
| `wake_word-end` / `stt-start` | "Listening…" pulse |
| `stt-end` | Display recognized transcript (1.5 s) |
| `intent-start` | "Thinking…" spinner |
| `tts-end` | Begin streaming TTS audio frames, show conversation reply text |
| `error` | Show error toast |

The Glass Agent owns the visual treatment of these states; the integration sends only the abstract state, not pixels.

### 3.3 TTS → speaker

When the pipeline emits a `tts-end` event with a media URL, the integration fetches the audio server-side, transcodes to 16 kHz Opus (or PCM, per `hello.capabilities`), chunks it, and pushes it to the glasses on channel `0x02`. The Glass Agent plays it through the YodaOS audio HAL.

### 3.4 Wake word

Wake word runs **on-glass** through Rokid's native always-on keyword path. The v1 target is to hook the built-in **"Hi Rokid"** wake-word callback rather than ship a separate wake model. When the callback fires, the Glass Agent sends `audio.start { trigger: "wake_word", phrase: "hi rokid" }` and begins streaming mic audio on channel `0x01`. The HA integration treats this as a Wyoming-style `start_stage: "stt"` pipeline call — bypassing HA's own wake-word stage to save round-trip latency.

A "push-to-talk" mode using the side button is also supported and sends `audio.start { trigger: "button" }`.

---

## 4. HUD card model

The right-eye micro-LED panel is small and monocular. We do **not** try to render Lovelace dashboards on it. Instead, HUD content is a constrained set of **card kinds**, analogous to View Assist's pre-built views but sized for a HUD overlay.

### 4.1 Card kinds (v1)

| Kind | Fields | Use cases |
|---|---|---|
| `toast` | `text`, `icon?`, `severity` | Brief confirmation ("Lights on") |
| `icon_text` | `icon`, `title`, `subtitle?` | Weather, sensor readouts |
| `list` | `title`, `items[≤4]` | Shopping list, top news |
| `timer` | `label`, `expires_at` | Live-counting kitchen timer |
| `alert` | `title`, `body`, `actions[]` | Doorbell, motion, security |
| `media` | `title`, `artist?`, `album_art_url?` | Now-playing |
| `nav` | `instruction`, `distance_m`, `eta` | Future: turn-by-turn |

Cards are rendered by the Glass Agent using Rokid's Caps serialization for layout primitives (text, icon, divider). The integration sends only the semantic card spec — the agent owns visual styling so cards stay consistent with native YodaOS UI.

### 4.2 Card lifecycle

```
hud.show { id, card, ttl_ms?, priority? }   ── ┐
hud.update { id, patch }                       │  Glass Agent maintains
hud.dismiss { id }                             │  a priority-sorted stack
                                               │  and renders the top card.
hud.dismiss { id: "*" }                       ── ┘
```

Cards have integer priority (0–100). An `alert` with priority 90 will preempt a `media` card at priority 30. When the high-priority card is dismissed, the previous card returns. This mirrors how View Assist swaps between "default", "weather", "music", and "alert" views on a tablet.

### 4.3 Triggering cards

Three trigger sources:

1. **Automatic from pipeline reply.** When `tts-end` fires and the conversation response has structured data (e.g. weather skill), the integration auto-selects a card kind. Mapping is defined in `card_mapping.py` and is user-overridable via a `hassglass.card_map` blueprint.
2. **Explicit service call.** Any automation can call `hassglass.notify` with a target device and a card spec.
3. **Native HA notifier.** The integration registers itself as a `notify.<device>` platform so existing `notify.send_message` calls land on the HUD.

---

## 5. HA-side integration anatomy

`custom_components/hassglass/` follows the standard Home Assistant integration layout. See [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) for the full file list; below is the runtime view.

### 5.1 Entities created per glasses device

| Platform | Entity | Notes |
|---|---|---|
| `sensor` | `sensor.<glasses>_battery` | Percent + charging state |
| `sensor` | `sensor.<glasses>_signal` | Wi-Fi RSSI |
| `sensor` | `sensor.<glasses>_last_intent` | Text of last recognized intent |
| `sensor` | `sensor.<glasses>_current_card` | Card id currently on HUD |
| `binary_sensor` | `binary_sensor.<glasses>_worn` | Derived from IMU stillness + proximity |
| `binary_sensor` | `binary_sensor.<glasses>_listening` | True between `stt-start` and `stt-end` |
| `select` | `select.<glasses>_pipeline` | Per-device Assist pipeline override |
| `switch` | `switch.<glasses>_wake_word` | Enable/disable on-glass wake word |
| `button` | `button.<glasses>_dismiss_card` | Pop top HUD card |
| `event` | `event.<glasses>_gesture` | Fires on swipe / tap / long-press |
| `event` | `event.<glasses>_button` | Side-button press events |
| `media_player` | `media_player.<glasses>` | TTS / announce target |

### 5.2 Services exposed

| Service | Purpose |
|---|---|
| `hassglass.notify` | Push a card to one or more glasses. Mirrors View Assist's `broadcast_event`. |
| `hassglass.dismiss` | Dismiss a card (by id or `*`). |
| `hassglass.set_pipeline` | Switch the active Assist pipeline for a device. |
| `hassglass.set_state` | Update arbitrary device-scoped state (analog to View Assist's `view_assist.set_state`). |
| `hassglass.start_listening` | Programmatically start a mic capture (e.g. from an automation). |
| `hassglass.identify` | Flash the HUD so the user knows which physical pair is selected. |

### 5.3 Events emitted

| Event | When |
|---|---|
| `hassglass_gesture` | Touchpad gesture. Payload: `{ device_id, kind, direction }`. |
| `hassglass_button` | Hardware button event. Payload: `{ device_id, button, action }`. |
| `hassglass_card_dismissed` | User dismissed a card via gesture. |
| `hassglass_worn_changed` | Wearer state changed. |

---

## 6. Configuration model

HassGlass copies View Assist's **master + per-device overrides** model:

- A single config entry (the "master config") holds defaults: default pipeline, default card TTL, default wake word, default media player for fallback TTS, brightness profile.
- Each discovered pair of glasses becomes a sub-entry that **inherits** master defaults and may override any of them via the device-options flow.

Master config is created on first integration setup. Devices auto-appear via either:

- **mDNS discovery** — Glass Agent advertises `_hassglass._tcp` on the LAN.
- **Manual add** — user enters the glasses' pairing code shown on the HUD.

Pairing flow:

1. User opens HA → Add Integration → HassGlass → "Add device".
2. Glass Agent shows a 6-digit code on HUD.
3. User enters code in HA. HA issues a long-lived access token scoped to that device.
4. Glass Agent stores token, opens WebSocket, sends `hello`. Device is registered.

---

## 7. Security model

- **Transport** — WSS only. The integration requires HA to be served over HTTPS (rejects pairing if `base_url` is plaintext).
- **Auth** — per-device long-lived access tokens, revocable from the device options screen. Tokens are scoped (`hassglass:device:<id>`) so a stolen token cannot call arbitrary HA services.
- **Mic privacy** — `switch.<glasses>_listening_enabled` cuts mic streaming at the integration boundary. A red dot in the HUD always indicates an active stream (enforced by the Glass Agent, not optional).
- **No telemetry off-LAN** — the Glass Agent has no outbound network targets other than the configured HA URL.

---

## 8. Non-goals (v1)

- Rendering full Lovelace dashboards on the HUD — display is too small and monocular.
- Stereo / spatial audio output — Rokid speaker is mono.
- Camera capture — out of scope until a clear privacy story exists.
- Android Companion app — iOS-only at v1; Android is a post-1.0 candidate.
- Multi-user wearer identification — assumed 1 wearer per device at v1.

---

## 9. Open questions

These are explicitly unresolved and will be revisited during implementation:

1. **Wyoming-native vs custom protocol.** Should the audio leg be a literal Wyoming server so the glasses appear in HA's Wyoming device list, or remain a HassGlass-private channel? Custom is simpler v1; Wyoming buys interop with existing Wyoming tooling.
2. **TTS transcode location.** Transcode on HA side (CPU cost) vs. ship the original media URL to the Glass Agent and let it fetch (firewall complexity)? Leaning toward server-side.
3. **Wake-word model distribution.** Ship a default model in the Glass Agent APK vs. download on first run.
4. **Multi-master HA setups.** Behavior when the same glasses pair has been onboarded to two HA instances. Probably refuse with a clear error.
