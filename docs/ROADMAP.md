# HassGlass Roadmap

Milestones are scoped so each one ends with something runnable and demoable. Dates are intentionally omitted — this is a hobby-scale community project and a milestone is "done" when it works, not when a date passes.

Legend: ✅ done · 🟡 partial · ⏳ in progress · ⬜ not started · ⏭ deferred to a later milestone.

---

## M0 — Skeleton ✅

**Outcome:** repo bootstrapped, CI green on an empty integration.

- [x] Architecture, project structure, roadmap docs.
- [x] `custom_components/hassglass/manifest.json` + `__init__.py` returning `True` from `async_setup_entry`.
- [x] `hacs.json` + HACS validation workflow.
- [x] `hassfest` workflow.
- [x] `ruff` + `mypy --strict` configured in `pyproject.toml` and `ci.yml`.
- [x] LICENSE chosen (Apache-2.0).
- [x] `requirements-dev.txt` + Python 3.13 venv reproducible locally.

---

## M1 — Pairing & telemetry over the wire ✅

**Outcome:** A pair of glasses can be paired to HA. The integration shows online/battery/RSSI for the device. No audio yet.

**Integration side:**

- [x] `protocol.py` — `hassglass/1` wire format (binary audio framing + JSON control plane).
- [x] `auth.py` — constant-time per-device token issuance + lookup.
- [x] `pairing.py` — 6-digit code broker with TTL + lockout-after-5-failures.
- [x] `pairing_view.py` — `POST /api/hassglass/pair` for Glass Agent claim.
- [x] `ws_server.py` — `/api/hassglass/ws/v1` with bearer-token auth and hello handshake.
- [x] `hub.py` — singleton runtime. Device records persisted via `homeassistant.helpers.storage.Store` (M4), with one-shot migration from legacy `entry.data["devices"]`.
- [x] `device.py` — `DeviceRecord`, `TelemetryState`, `GlassesRuntime`, `DeviceBus` with back-pressure-aware fan-out.
- [x] `config_flow.py` — singleton master setup + options flow.
- [x] `sensor.py` + `binary_sensor.py` — battery / signal / last_intent / current_card / worn / listening / connected.
- [x] `entity.py` — base class wiring dispatcher signal → `async_schedule_update_ha_state`.
- [x] `strings.json` + `translations/en.json`.

**Glass Agent side:**

- [x] Android/Kotlin Gradle skeleton with protocol codec unit tests and CI.
- [x] WS client core + OkHttp transport with reconnect, paired settings storage.
- [x] Pairing client core: code generation, transport, token persistence.
- [x] `MicSource` interface; `FakeMicSource` for tests; `AndroidMicSource` for stock Android capture; `CxrlMicSource` retained as the privileged follow-up path for Rokid AEC routing.
- [ ] Actual CXR-L AIDL `.aidl` file + binder code (one tiny class — gated on access to a real glasses unit + the Rokid SDK headers).
- [ ] 6-digit pairing flow rendered on the Rokid HUD via Caps primitives.

---

## M2 — Visual: HUD cards from HA ✅

**Outcome:** Any HA automation can push a card to the glasses HUD.

- [x] `cards.py` — `CardKind` enum + per-kind validation (`toast`, `icon_text`, `list`, `timer`, `alert`, `media`).
- [x] `hud.py` — `HudDispatcher` with per-device priority stack + TTL + wildcard dismiss.
- [x] `services.py` + `services.yaml` — `hassglass.notify`, `hassglass.dismiss`, `hassglass.identify`.
- [x] `button.py` — Dismiss top HUD card + Identify per device.
- [x] `__init__.py` wired with full `HassGlassRuntimeData(hub, pairing_broker, hud, bridge, tts_relay, options_snapshot)`.
- [x] `notify.py` — modern `NotifyEntity` platform (replaced the M2 legacy-platform rollback).
- [x] Card priority stack + TTL on the **Glass Agent** (`CardStack` + `HudController` with a `LoggingHudRenderer`).
- [x] **Caps renderer scaffold** — `CapsCommand` ADT + `CapsCommandTranslator` (per-kind layout logic, fully unit-tested) + `CapsHudRenderer` adapter.
- [x] **Current-device HUD adapter** — `AndroidCapsSurface` renders the command stream onto an Android `Canvas` today; a Rokid-native Caps SDK adapter can still replace the sink later without changing the renderer contract.
- [x] Starter blueprints: `doorbell_to_hud`, `timer_to_hud`, `motion_to_hud`, `alarm_to_hud`.

---

## M3 — Voice: full Assist pipeline integration ✅ (modulo CXR-L AIDL mic + AudioTrack sink)

**Outcome:** "Hey Rokid, turn on the kitchen lights" works end-to-end with a listening/thinking/done HUD state.

**Integration side:**

- [x] `audio.py` — adapt inbound mic frames into an async byte generator and feed `assist_pipeline.async_pipeline_from_audio_stream`. Refuses to start when `listening_enabled` is False (mic-privacy cut at the integration boundary).
- [x] `pipeline_bridge.py` — translate Assist pipeline events into `pipeline.event` messages; auto-render `tts-end` replies via `card_mapping`.
- [x] `tts_relay.py` — fetch TTS media and chunk + send on channel 0x02.
- [x] `card_mapping.py` — heuristic intent-reply → card-kind table (weather, list, music, generic toast).
- [x] `select.py` — per-device Assist pipeline override.
- [x] `switch.py` — `wake_word` + `listening_enabled` toggles persisted on the DeviceRecord.
- [x] `media_player.py` — TTS / announce target per device; resolves media_source / local /media paths.
- [x] Documented decision: `assist_pipeline`/`tts`/`stt` stay in `after_dependencies`. Lazy `import_module` in `audio.py` makes the runtime cost zero; promoting to hard `dependencies` would force `pyspeex_noise` (native build), `pymicro_vad`, ffmpeg binary, and `mutagen` onto every CI runner, AND hit `pytest-homeassistant-custom-component`'s missing `homeassistant.exposed_entities` init. See ARCHITECTURE §7.bis.

**Glass Agent side:**

- [x] Wake-trigger + push-to-talk WS bridge contracts.
- [x] `MicFrameSender` packages PCM as channel-0x01 binary frames.
- [x] **`MicSession`** — orchestrator that brackets a capture with `audio.start { trigger }` / `audio.stop` and pipes the MicSource through MicFrameSender. Idempotent start/stop; same path for wake-word and button triggers.
- [x] **`TtsPlayer`** — channel-0x02 decode path with sequence ordering, late-frame dropping, gap reporting, and end-of-utterance flush. `TtsPlaybackSink` interface so AudioTrack vs in-memory is one swap.
- [x] `WsListener.onBinary` → `TtsPlayer.accept` wired through the service-owned runtime via `WsClient` inbound callbacks. Unit-tested at the transport and runtime layers.
- [ ] Production `AudioTrackPlaybackSink` (16k mono PCM via Android `AudioTrack`).
- [ ] Production `CxrlMicSource` (CXR-L AIDL binding).

---

## M4 — Polish, gestures, blueprints ✅

**Outcome:** Daily-driver quality. Gestures trigger automations. Useful starter blueprints. Settings are discoverable.

- [x] `event.py` — touchpad gesture + side-button event entities per device. Whitelist: `swipe_{forward,back,up,down}`, `tap`, `double_tap`, `long_press`, `head_nod`, `head_shake` for gestures; `side_press`, `side_long_press`, `side_double_press`, `side_release` for buttons.
- [x] **IMU head-nod / head-shake detection** — `HeadPoseInterpreter` runs a windowed zero-crossing detector with a refractory window; `HeadPoseGestureBridge` emits `input.gesture` frames with `kind: head_nod | head_shake` and `axis: pitch | yaw`. Routed through the same gesture event entity on the integration side.
- [x] `notify.<device>` re-implemented as `NotifyEntity`.
- [x] Persistent device storage on `homeassistant.helpers.storage.Store`.
- [x] **Options-flow auto-reload listener** — captures an options snapshot during setup; the listener compares it against `entry.options` and skips the reload if nothing actually changed. The Store migration unblocked this by ensuring `entry.data` writes don't pretend to be options changes.
- [ ] Translations: English plus a community-contributed second locale. Post-1.0 (no point picking a second locale without a native speaker).
- [x] End-user `PAIRING.md` walkthrough with troubleshooting matrix.
- [x] Blueprints shipped: doorbell, timer, motion, alarm.

---

## M5 — HACS default repo submission ⬜

**Outcome:** Available in HACS without adding a custom repository.

- [ ] All hassfest + HACS validation clean for ≥4 weeks.
- [ ] ≥2 external testers with non-developer-mode glasses.
- [ ] Stable `hassglass/1` protocol with a version compatibility test matrix.
- [ ] Release `v1.0.0` with signed APK.
- [ ] Open HACS default-repo PR.

---

## Cumulative status

| Surface | M0 | M1 | M2 | M3 | M4 | M5 |
|---|---|---|---|---|---|---|
| Docs + scaffolding | ✅ | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Integration code (Python) | ✅ | ✅ | ✅ | ✅ | ✅ | ⬜ |
| Glass Agent code (Kotlin) | — | 🟡 | 🟡 | 🟡 | ✅ | ⬜ |
| iOS Companion (Swift) | — | — | — | — | ⬜ | ⬜ |
| CI green | ✅ | ✅ | ✅ | ✅ | ✅ | ⬜ |

Snapshot at end of last work session:

- **119 Python tests passing**, **ruff clean**, **mypy --strict clean** on 26 Python source files.
- **Android `gradle :apps:glass_agent:testDebugUnitTest`** passing across 14 test classes (covering protocol codec, WS client, pairing client/flow, settings store, HUD card stack/controller, agent controller, wake-trigger, mic session, mic frame sender, TTS player, Caps command translator, and head-pose interpreter).
- Nine HA platforms ship per paired device: `sensor`, `binary_sensor`, `button`, `select`, `switch`, `media_player`, `event`, `notify`. (`event` carries gestures + buttons + head-pose.)
- Three services exposed: `hassglass.notify`, `hassglass.dismiss`, `hassglass.identify`.
- Four blueprints in `custom_components/hassglass/blueprints/`: doorbell, timer, motion, alarm.

**What's left before M5:**

The runtime contracts are now implemented for the non-privileged Android path:

1. `AndroidMicSource` captures 16 kHz mono PCM through `AudioRecord` and feeds the existing `MicSession`.
2. `AudioTrackPlaybackSink` + `AndroidAudioTrackWriter` play 16 kHz mono PCM through Android `AudioTrack`, including WAV-header stripping on the first chunk.
3. `AndroidCapsSurface` executes `CapsCommand` onto an Android `Canvas` so the HUD path is live without depending on the Rokid SDK headers.

The only adapter-level follow-up before a fully privileged Rokid build is the CXR-L mic path:

1. `CxrlMicSource` — bind to the CXR-L AIDL service, request the AEC mic path, and swap it in where the app currently uses `AndroidMicSource`.

The remaining runtime follow-up is visual HUD hosting: the service currently composes the inbound HUD path through the runtime but still uses `LoggingHudRenderer` until a real `SurfaceHolder` host exists for `AndroidCapsSurface`.

---

## Post-1.0 candidates (v2+)

Not a commitment list — just a parking lot for the obvious next questions:

- Android Companion app (iOS is the v1 companion; Android is a post-1.0 add).
- Wyoming-protocol compatibility mode so glasses appear in HA's Wyoming device list.
- Camera capture + on-device frame analysis (carefully — needs a privacy story).
- Turn-by-turn `nav` card kind powered by HA's `mobile_app` location service.
- Multi-wearer identification via IMU gait fingerprinting.
- Spatial-audio output if Rokid ships stereo hardware.
- Replacement of single WS channel with a Wyoming-style multi-stream design once HA's voice subsystem stabilizes.
- Second-locale translations (waiting on a native speaker).
- Hard-dep promotion of `assist_pipeline`/`tts`/`stt` once the upstream test harness no longer needs `homeassistant.exposed_entities` pre-init.
