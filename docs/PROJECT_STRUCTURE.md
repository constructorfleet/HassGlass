# HassGlass — Project Structure

This is the planned repository layout. Files marked **(v1)** are required for the first releasable milestone; **(v2+)** are deferred.

```
HassGlass/
├── README.md
├── LICENSE                                    # Apache-2.0 recommended
├── hacs.json                                  # HACS metadata (HACS reads this)
├── info.md                                    # Short blurb shown inside HACS UI
├── .gitignore
│
├── docs/
│   ├── ARCHITECTURE.md                        # System design, transport, pipeline, HUD
│   ├── PROJECT_STRUCTURE.md                   # This file
│   ├── ROADMAP.md                             # Milestones M0..M5
│   ├── PROTOCOL.md                            # (v1) Full hassglass/1 wire protocol spec
│   ├── CARD_KINDS.md                          # (v1) Card kind catalogue & JSON schemas
│   ├── PAIRING.md                             # (v1) End-user pairing walkthrough
│   └── DEVELOPING.md                          # (v1) How to run integration + agent locally
│
├── custom_components/
│   └── hassglass/                             # The HACS-installable integration
│       ├── __init__.py                        # async_setup_entry / async_unload_entry
│       ├── manifest.json                      # HA integration manifest
│       ├── const.py                           # Domain, signals, defaults, protocol consts
│       ├── config_flow.py                     # Pairing-code & mDNS discovery flow
│       ├── coordinator.py                     # DataUpdateCoordinator per device
│       ├── device.py                          # GlassesDevice runtime (per-pair state)
│       ├── api.py                             # Internal HTTP/WS view registered on HA
│       ├── ws_server.py                       # /api/hassglass/ws/v1 endpoint handler
│       ├── protocol.py                        # hassglass/1 frame codec, message types
│       ├── audio.py                           # Mic stream → assist_pipeline bridge
│       ├── tts_relay.py                       # Pipeline TTS → glass speaker chunks
│       ├── pipeline_bridge.py                 # Subscribes to assist_pipeline events
│       ├── hud.py                             # Card dispatcher, priority stack, mapping
│       ├── card_mapping.py                    # Auto-map intent replies → card kinds
│       ├── discovery.py                       # Zeroconf / mDNS advertiser & finder
│       ├── auth.py                            # Per-device token issuance & validation
│       ├── services.py                        # Service registrations & handlers
│       ├── services.yaml                      # Service schemas (UI / dev tools)
│       ├── sensor.py                          # battery, signal, last_intent, current_card
│       ├── binary_sensor.py                   # worn, listening
│       ├── select.py                          # pipeline selector
│       ├── switch.py                          # wake_word, listening_enabled
│       ├── button.py                          # dismiss_card, identify
│       ├── event.py                           # gesture, button events
│       ├── media_player.py                    # TTS / announce target
│       ├── notify.py                          # notify.<device> platform
│       ├── strings.json                       # i18n source (HA convention)
│       ├── translations/
│       │   └── en.json                        # English UI strings
│       └── blueprints/                        # Optional user automation templates
│           ├── doorbell_alert_card.yaml
│           └── timer_to_hud.yaml
│
├── apps/
│   ├── glass_agent/                           # (v1) Android app for the glasses (CXR-L)
│   │   ├── README.md
│   │   ├── build.gradle.kts
│   │   ├── app/
│   │   │   ├── build.gradle.kts
│   │   │   └── src/main/
│   │   │       ├── AndroidManifest.xml
│   │   │       ├── java/com/hassglass/agent/
│   │   │       │   ├── HassGlassAgentService.kt   # CXR-L AIDL binding, foreground svc
│   │   │       │   ├── ws/WsClient.kt              # WSS client w/ reconnect & backoff
│   │   │       │   ├── ws/ProtocolCodec.kt         # hassglass/1 framing
│   │   │       │   ├── audio/MicCapture.kt         # 16k mono PCM, hardware AEC
│   │   │       │   ├── audio/TtsPlayer.kt          # Opus/PCM playback via AudioTrack
│   │   │       │   ├── hud/HudRenderer.kt          # Renders cards (Caps primitives)
│   │   │       │   ├── hud/CardStack.kt            # Priority stack + lifecycle
│   │   │       │   ├── input/TouchpadHandler.kt    # Gestures → input.gesture msgs
│   │   │       │   ├── input/ButtonHandler.kt      # Side button → input.button msgs
│   │   │       │   ├── input/HeadPoseSensor.kt     # IMU sampling → head_pose msgs
│   │   │       │   ├── wake/WakeWordEngine.kt      # Always-on KWS
│   │   │       │   └── pairing/PairingFlow.kt      # 6-digit pairing UI
│   │   │       └── res/                            # Drawables, layouts (HUD-safe)
│   │   └── gradle/
│   │
│   └── companion_ios/                         # (v2) iOS phone companion (CXR-M)
│       ├── README.md
│       ├── Package.swift                      # SwiftPM manifest
│       ├── HassGlassCompanion.xcodeproj/
│       └── HassGlassCompanion/
│           ├── HassGlassCompanionApp.swift    # @main SwiftUI app entry
│           ├── Pairing/
│           │   ├── PairingView.swift          # Onboarding UI
│           │   └── PairingCoordinator.swift   # 6-digit handoff to HA
│           ├── Bluetooth/
│           │   ├── GlassesPeripheral.swift    # CoreBluetooth wrapper (CXR-M)
│           │   └── WifiDirectClient.swift     # MultipeerConnectivity bridge
│           ├── Relay/
│           │   ├── BleHaBridge.swift          # BLE ↔ HA WSS relay loop
│           │   └── BackgroundTask.swift       # iOS BGTask scheduling
│           └── Resources/Info.plist           # Bluetooth + background modes
│
├── protocol/                                  # (v1) Protocol artifacts shared across impls
│   ├── hassglass.proto                        # Optional protobuf for binary frames
│   ├── messages.schema.json                   # JSON Schema for control-plane msgs
│   └── card.schema.json                       # JSON Schema for hud.card payloads
│
├── tests/                                     # (v1) pytest suite for the integration
│   ├── conftest.py
│   ├── test_config_flow.py
│   ├── test_protocol.py
│   ├── test_audio_bridge.py
│   ├── test_pipeline_bridge.py
│   ├── test_hud_dispatcher.py
│   ├── test_services.py
│   └── fixtures/
│       ├── pipeline_events.json
│       └── ws_session_replay.jsonl
│
├── scripts/
│   ├── dev_ha.sh                              # Spin up HA in a venv with this integration
│   ├── replay_session.py                      # Replay a recorded WS session vs a live HA
│   └── package_release.sh                     # Build APK + zip integration for release
│
└── .github/
    ├── workflows/
    │   ├── ci.yml                             # ruff + mypy + pytest on integration
    │   ├── android.yml                        # Gradle build for glass_agent APK
    │   ├── hacs.yml                           # HACS validation (action-hacs)
    │   ├── hassfest.yml                       # HA manifest validation
    │   └── release.yml                        # Tag → GitHub release w/ APK + zip
    ├── ISSUE_TEMPLATE/
    │   ├── bug_report.yml
    │   └── feature_request.yml
    └── PULL_REQUEST_TEMPLATE.md
```

---

## File-by-file responsibilities

### Top-level metadata

- **`hacs.json`** — Tells HACS this is an integration repo. Declares name, minimum HA version, country list, render-readme flag.
- **`info.md`** — Short marketing blurb shown in HACS's repo browser.
- **`manifest.json`** (inside `custom_components/hassglass/`) — HA's required integration descriptor. Declares domain, version, dependencies, iot_class (`local_push`), config_flow flag, zeroconf type, codeowners.

### Integration runtime (`custom_components/hassglass/`)

- **`__init__.py`** — Standard HA entry points. `async_setup_entry` wires up the coordinator, registers the WS view, starts mDNS, and forwards entries to platform setups. `async_unload_entry` reverses everything.
- **`const.py`** — Single source of truth for the integration domain, default port, protocol version, signal names, and config keys.
- **`config_flow.py`** — Two flows: (a) initial **master config** (default pipeline, brightness, fallback media_player), (b) **per-device** flow triggered by mDNS or by the user choosing "Add device", driven by the 6-digit pairing code shown on the HUD. Options flow handles per-device overrides.
- **`coordinator.py`** — A thin `DataUpdateCoordinator` per device that aggregates telemetry, current card, last intent, and pipeline state. Entities subscribe to it.
- **`device.py`** — `GlassesDevice` class: owns the open WebSocket, the inbound audio queue, the outbound TTS pump, and the HUD stack reference. One instance per paired pair-of-glasses.
- **`api.py` / `ws_server.py`** — Registers `HassGlassWsView` on HA's HTTP component at `/api/hassglass/ws/v1`. Handles handshake, token validation, dispatch into `protocol.py`, and lifecycle of `GlassesDevice`.
- **`protocol.py`** — Pure encoding/decoding of the `hassglass/1` wire format. No HA imports. Testable in isolation.
- **`audio.py`** — Adapts inbound binary audio frames to an `async` byte generator consumable by `assist_pipeline.async_pipeline_from_audio_stream`.
- **`tts_relay.py`** — Subscribes to `tts-end` pipeline events for the device's pipeline, fetches the media, transcodes to the negotiated codec, chunks, and writes to the device's outbound channel.
- **`pipeline_bridge.py`** — Subscribes to all pipeline events for a given device, translates them into abstract `pipeline.event` messages, and forwards to the Glass Agent. Also calls `hud.py` to auto-render reply cards.
- **`hud.py`** — Card dispatcher. Maintains a priority stack per device, sends `hud.show` / `hud.update` / `hud.dismiss` messages, persists current card state.
- **`card_mapping.py`** — Heuristics + user-overridable mapping from intent responses to card kinds. (e.g. weather intent → `icon_text`, list intent → `list`.)
- **`discovery.py`** — Advertises the HA endpoint as `_hassglass._tcp` and listens for Glass Agents advertising themselves the same way.
- **`auth.py`** — Issues, stores, and validates per-device long-lived tokens. Wraps HA's `auth_token` machinery so revocation works through the standard UI.
- **`services.py` / `services.yaml`** — Defines and validates the public services (`notify`, `dismiss`, `set_pipeline`, `set_state`, `start_listening`, `identify`).
- **`sensor.py` / `binary_sensor.py` / `select.py` / `switch.py` / `button.py` / `event.py` / `media_player.py` / `notify.py`** — Standard HA platform modules, each creating entities described in [ARCHITECTURE.md §5.1](ARCHITECTURE.md#51-entities-created-per-glasses-device).
- **`strings.json` + `translations/`** — UI strings for config flow, options flow, and service descriptions. English first; community can add locales.
- **`blueprints/`** — Drop-in automation templates so a new user gets useful HUD behavior without writing YAML.

### Glass Agent app (`apps/glass_agent/`)

- **`HassGlassAgentService.kt`** — Android foreground service. Binds to YodaOS AI service via AIDL (CXR-L) and is the single owner of the WS connection.
- **`ws/WsClient.kt`** — WSS client with exponential-backoff reconnect, heartbeat, and bounded outbound queue.
- **`ws/ProtocolCodec.kt`** — Mirror of `protocol.py` in Kotlin. Encodes/decodes frames per `protocol/hassglass.proto` and `messages.schema.json`.
- **`audio/MicCapture.kt`** — Configures `AudioRecord` for 16 kHz mono PCM through the hardware AEC path, packages into 20 ms frames.
- **`audio/TtsPlayer.kt`** — Decodes Opus/PCM frames received on channel `0x02` and plays them through `AudioTrack` at lowest available latency.
- **`hud/HudRenderer.kt`** — Renders cards using Rokid Caps primitives so styling matches native YodaOS UI. Honors HUD safe-area and brightness constraints.
- **`hud/CardStack.kt`** — Priority stack + TTL expiry timer. Pops to next-highest when the active card is dismissed.
- **`input/*`** — Translates touchpad gestures, side-button presses, and IMU samples into `input.*` messages. Throttles head-pose to 5 Hz unless the integration subscribes to higher rate.
- **`wake/RokidWakeBridge.kt`** — Hooks Rokid's built-in "Hi Rokid" on-device wake-word callback and converts it into the HassGlass `audio.start` + mic-stream trigger for HA Assist.
- **`pairing/PairingFlow.kt`** — Renders the 6-digit code on the HUD, listens for completion handshake from HA, persists the issued token.

### Protocol package (`protocol/`)

Single shared source of truth so the Python and Kotlin codecs cannot drift:

- **`hassglass.proto`** — Optional protobuf definition for binary audio framing (header bytes are fixed even if payload is PCM).
- **`messages.schema.json`** — JSON Schema for every control-plane message. Both sides validate inbound messages against this.
- **`card.schema.json`** — JSON Schema for each card kind's payload.

Both `protocol.py` and `ws/ProtocolCodec.kt` are generated/validated against these.

### Tests (`tests/`)

- **`test_protocol.py`** — Round-trip encode/decode for every message type + binary framing edge cases.
- **`test_config_flow.py`** — Pairing flow, mDNS discovery, options flow, master-vs-device inheritance.
- **`test_audio_bridge.py`** — Feeds a recorded mic stream through the bridge and asserts `assist_pipeline` is called with the right shape.
- **`test_pipeline_bridge.py`** — Replays pipeline events and asserts the right `pipeline.event` + auto-cards are emitted.
- **`test_hud_dispatcher.py`** — Priority preemption, TTL expiry, dismiss-all semantics.
- **`test_services.py`** — Each service: schema validation, target resolution, error paths.
- **`fixtures/ws_session_replay.jsonl`** — A captured end-to-end session used by `scripts/replay_session.py` to smoke-test against a live HA.

### CI (`.github/workflows/`)

- **`ci.yml`** — Lint (ruff), type-check (mypy), test (pytest). Runs on every PR.
- **`hassfest.yml`** — HA's official manifest validator. Required for HACS-grade integrations.
- **`hacs.yml`** — HACS's repo-shape validator.
- **`android.yml`** — Builds the Glass Agent APK on every PR that touches `apps/glass_agent/`.
- **`ios.yml`** — Builds the iOS Companion app on every PR that touches `apps/companion_ios/`. Requires `macos-latest` runner + Xcode.
- **`release.yml`** — On tag push, builds APK + zips integration, attaches both to a GitHub Release. (iOS Companion ships separately via TestFlight once the project has a paid Apple Developer account.)
