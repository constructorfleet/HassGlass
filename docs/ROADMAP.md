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

## M1 — Pairing & telemetry over the wire 🟡 (integration side complete; Glass Agent pending)

**Outcome (target):** A pair of glasses can be paired to HA. The integration shows online/battery/RSSI for the device. No audio yet.

**Integration side — complete:**

- [x] `protocol.py` — `hassglass/1` wire format (binary audio framing + JSON control plane), `MessageType` enum, validators for `hello` / `telemetry` / `hud.show`. 32 unit tests.
- [x] `auth.py` — constant-time per-device token issuance + lookup.
- [x] `pairing.py` — 6-digit code broker with TTL + lockout-after-5-failures; lazy `asyncio.Future` so the broker is testable without an event loop.
- [x] `pairing_view.py` — `POST /api/hassglass/pair` for the Glass Agent to claim a code.
- [x] `ws_server.py` — `/api/hassglass/ws/v1` with bearer-token auth, hello handshake, reader loop fanning frames to a `DeviceBus`.
- [x] `hub.py` — `HassGlassHub` singleton: persistent device records + live runtimes + master/per-device option merging.
- [x] `device.py` — `DeviceRecord`, `TelemetryState`, `GlassesRuntime`, `DeviceBus` (back-pressure-aware fan-out).
- [x] `config_flow.py` — singleton master setup + options flow. 3 tests.
- [x] `sensor.py` — `battery`, `signal`, `last_intent`, `current_card`.
- [x] `binary_sensor.py` — `worn`, `listening`, `connected`.
- [x] `entity.py` — base class wiring dispatcher signal → `async_schedule_update_ha_state`.
- [x] `strings.json` + `translations/en.json`.

**Glass Agent side — pending hardware:**

- [x] Android/Kotlin Gradle skeleton with protocol codec unit tests and CI workflow.
- [x] WS client core + OkHttp transport: bearer-token auth, `hello`, initial `telemetry`, ping/pong, bounded reconnect, paired settings storage, and service startup wiring.
- [x] Pairing client core: 6-digit code generation, pairing-code renderer interface, `/api/hassglass/pair` claim transport, returned token persistence.
- [ ] Foreground service binds to CXR-L for device identity, telemetry, mic/HUD ownership, and the native on-device wake-word callback.
- [ ] Glass Agent: hook the built-in **"Hi Rokid"** on-device wake word so a wake event sends `audio.start { trigger: "wake_word", phrase: "hi rokid" }`, opens mic streaming on channel `0x01`, and starts the HA Assist pipeline at STT.
- [ ] 6-digit pairing flow rendered on the Rokid HUD via Caps primitives.

---

## M2 — Visual: HUD cards from HA ✅ (integration side; Glass Agent rendering pending hardware)

**Outcome:** Any HA automation can push a card to the glasses HUD.

- [x] `cards.py` — `CardKind` enum + per-kind validation (`toast`, `icon_text`, `list`, `timer`, `alert`, `media`). 14 tests.
- [x] `hud.py` — `HudDispatcher` with per-device priority stack, TTL tracking, `hud.show` / `hud.dismiss` ("*" wildcard supported), shadow state for `sensor.<glasses>_current_card`. 8 tests.
- [x] `services.py` + `services.yaml` — `hassglass.notify`, `hassglass.dismiss`, `hassglass.identify`. 4 integration tests through a full HA test harness.
- [x] `button.py` — `Dismiss top HUD card`, `Identify` buttons per device.
- [x] `__init__.py` wired with `HassGlassRuntimeData(hub, pairing_broker, hud)`; services registered on entry setup, unregistered on unload.
- [ ] `notify.<device>` platform — prototyped and removed; legacy notify platform doesn't fit the device-multiplex model. Re-added as a `NotifyEntity` in M4.
- [x] Card priority stack + TTL on the **Glass Agent** (`CardStack` + `HudController`; real Caps renderer pending hardware).
- [ ] Blueprint: doorbell → HUD alert (queued for M4 alongside the other starter blueprints).

**Test totals at end of M2:** 74 passing, ruff clean, mypy strict clean.

---

## M3 — Voice: full Assist pipeline integration ⏳

**Outcome:** "Hey Rokid, turn on the kitchen lights" works end-to-end, with a "listening / thinking / done" HUD state.

- [ ] Re-add `assist_pipeline`, `tts`, `stt` to `manifest.json` (currently in `after_dependencies` so M0–M2 tests pass without `ha-ffmpeg`).
- [x] `audio.py` — adapt inbound binary frames on channel `0x01` into an async byte generator; feed `assist_pipeline.async_pipeline_from_audio_stream` from `audio.start`.
- [x] `pipeline_bridge.py` — translate Assist pipeline events into abstract `pipeline.event` messages; auto-render `tts-end` replies as cards via `card_mapping.py`; wired into the audio pipeline runner.
- [x] `tts_relay.py` — fetch TTS media and chunk + send on channel `0x02`. Codec negotiation/transcoding is still pending.
- [x] `card_mapping.py` — heuristic intent-reply → card-kind table (weather, list, music, generic toast).
- [x] `select.py` — per-device Assist pipeline override.
- [ ] `switch.py` — `wake_word`, `listening_enabled` (mic privacy cut).
- [ ] `media_player.py` — TTS / announce target.
- [x] Glass Agent: "Hi Rokid" wake trigger bridge contract sends `audio.start { trigger: "wake_word", phrase: "hi rokid" }`.
- [x] Glass Agent: side-button push-to-talk path sends `audio.start { trigger: "button" }` and uses the same mic streaming path as the "Hi Rokid" wake trigger.
- [x] Glass Agent: mic frame sender packages PCM chunks as channel `0x01` binary WS frames. Actual `AudioRecord`/CXR-L mic capture is still hardware-gated.
- [ ] Glass Agent: TTS playback from inbound binary frames.

---

## M4 — Polish, gestures, blueprints ⬜

**Outcome:** Daily-driver quality. Gestures trigger automations. Useful starter blueprints. Settings are discoverable.

- [ ] `event.py` — touchpad gestures + side-button events → HA event entities.
- [ ] IMU-based head-nod / shake events (opt-in).
- [ ] `notify.<device>` re-added as a `NotifyEntity` (modern platform).
- [ ] Per-options master config reload listener (deferred from M2 — needs to distinguish data vs options changes; current behavior requires HA restart after options edits).
- [ ] Persistent device storage moved to `homeassistant.helpers.storage.Store` so `add_device` no longer touches `entry.data` (cleans up the M2 reload-listener workaround).
- [ ] Translations: English plus a community-contributed second locale.
- [ ] End-user docs: `PAIRING.md`, troubleshooting guide.
- [ ] Blueprints shipped: doorbell-to-HUD, timer-to-HUD, alarm-to-HUD, motion-alert-to-HUD.

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
| Docs + scaffolding | ✅ | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Integration code (Python) | ✅ | ✅ | ✅ | ⏳ | ⬜ | ⬜ |
| Glass Agent code (Kotlin) | — | ⏳ | ⬜ | ⬜ | ⬜ | ⬜ |
| iOS Companion (Swift) | — | — | — | ⬜ | ⬜ | ⬜ |
| CI green | ✅ | ✅ | ✅ | ⬜ | ⬜ | ⬜ |

Snapshot at end of last work session:

- **95 Python tests passing**, Android `testDebugUnitTest` + `assembleDebug` passing, **ruff clean**, **mypy --strict clean** on 22 Python source files.
- Integration loads in a `pytest-homeassistant-custom-component` HA harness; sensor/binary_sensor/button platforms instantiate per device; services round-trip through to `runtime.send_message`.
- No real glasses connected yet — everything tested via mocks of `GlassesRuntime`.

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
