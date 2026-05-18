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

## M1 — Pairing & telemetry over the wire 🟡 (integration side complete; Glass Agent pending hardware)

**Outcome:** A pair of glasses can be paired to HA. The integration shows online/battery/RSSI for the device. No audio yet.

**Integration side — complete:**

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

**Glass Agent side — pending hardware:**

- [x] Android/Kotlin Gradle skeleton with protocol codec unit tests and CI.
- [x] WS client core + OkHttp transport with reconnect, paired settings storage.
- [x] Pairing client core: code generation, transport, token persistence.
- [ ] CXR-L AIDL binding for device identity, telemetry, mic/HUD ownership, native wake-word callback.
- [ ] "Hi Rokid" wake word → `audio.start { trigger: "wake_word" }`.
- [ ] 6-digit pairing flow rendered on the Rokid HUD via Caps primitives.

---

## M2 — Visual: HUD cards from HA ✅ (integration side; Glass Agent rendering pending hardware)

**Outcome:** Any HA automation can push a card to the glasses HUD.

- [x] `cards.py` — `CardKind` enum + per-kind validation (`toast`, `icon_text`, `list`, `timer`, `alert`, `media`).
- [x] `hud.py` — `HudDispatcher` with per-device priority stack + TTL + wildcard dismiss.
- [x] `services.py` + `services.yaml` — `hassglass.notify`, `hassglass.dismiss`, `hassglass.identify`.
- [x] `button.py` — Dismiss top HUD card + Identify per device.
- [x] `__init__.py` wired with `HassGlassRuntimeData(hub, pairing_broker, hud, bridge, tts_relay)`.
- [x] `notify.<device>` re-implemented as `NotifyEntity` in M4 (after the legacy-platform attempt was rolled back in M2).
- [x] Card priority stack + TTL on the **Glass Agent** (`CardStack` + `HudController`; real Caps renderer pending hardware).
- [x] Starter blueprint shipped: `doorbell_to_hud.yaml` (and 3 more — see M4).

---

## M3 — Voice: full Assist pipeline integration 🟡 (integration side complete; Glass Agent mic/playback pending hardware)

**Outcome:** "Hey Rokid, turn on the kitchen lights" works end-to-end with a listening/thinking/done HUD state.

- [x] `audio.py` — adapt inbound mic frames into an async byte generator and feed `assist_pipeline.async_pipeline_from_audio_stream`. Refuses to start when `listening_enabled` is False (mic-privacy cut at the integration boundary).
- [x] `pipeline_bridge.py` — translate Assist pipeline events into `pipeline.event` messages; auto-render `tts-end` replies via `card_mapping`.
- [x] `tts_relay.py` — fetch TTS media and chunk + send on channel 0x02.
- [x] `card_mapping.py` — heuristic intent-reply → card-kind table (weather, list, music, generic toast).
- [x] `select.py` — per-device Assist pipeline override.
- [x] `switch.py` — `wake_word` + `listening_enabled` toggles persisted on the DeviceRecord.
- [x] `media_player.py` — TTS / announce target per device; resolves media_source / local /media paths.
- [ ] Promote `assist_pipeline`, `tts`, `stt` from `after_dependencies` to hard `dependencies`. **Deferred:** `pytest-homeassistant-custom-component` doesn't initialize `homeassistant.exposed_entities`, which breaks `conversation` setup whenever assist_pipeline is a hard dep. Lazy `import_module` in `audio.py` already keeps Assist optional at HassGlass import time.
- [x] Glass Agent: wake-trigger + push-to-talk bridge contracts (CXR-L mic-path binding still hardware-gated).
- [x] Glass Agent: mic frame sender — packages PCM as channel-0x01 binary frames.
- [ ] Glass Agent: TTS playback from inbound channel-0x02 frames.

---

## M4 — Polish, gestures, blueprints ✅ (integration side; Glass Agent stays at M2/M3 contracts)

**Outcome:** Daily-driver quality. Gestures trigger automations. Useful starter blueprints. Settings are discoverable.

- [x] `event.py` — touchpad gesture + side-button event entities per device. Allowed event_types: `swipe_{forward,back,up,down}` / `tap` / `double_tap` / `long_press` for gestures; `side_press` / `side_long_press` / `side_double_press` / `side_release` for buttons. `ws_server` fans `input.*` frames out via dispatcher AND via `hass.bus` so both event-entity triggers and raw `event:` YAML triggers work.
- [ ] IMU-based head-nod / shake events (opt-in). Carried over to post-1.0.
- [x] `notify.<device>` re-implemented as `NotifyEntity` (toast for plain message, `icon_text` when a title is provided).
- [x] Persistent device storage moved to `homeassistant.helpers.storage.Store`; `add_device` and the toggle setters no longer touch `entry.data`, which clears the way for an options-reload listener.
- [ ] Options-flow auto-reload listener that distinguishes data vs options changes. Now blocked-only by API ergonomics — the Store work removed the underlying conflict. Post-1.0.
- [ ] Translations: English plus a community-contributed second locale. Post-1.0.
- [x] End-user `PAIRING.md` walkthrough with troubleshooting matrix.
- [x] Blueprints shipped: `doorbell_to_hud`, `timer_to_hud`, `motion_to_hud`, `alarm_to_hud`.

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
| Glass Agent code (Kotlin) | — | 🟡 | 🟡 | 🟡 | — | ⬜ |
| iOS Companion (Swift) | — | — | — | — | ⬜ | ⬜ |
| CI green | ✅ | ✅ | ✅ | ✅ | ✅ | ⬜ |

Snapshot at end of last work session:

- **116 Python tests passing**, **ruff clean**, **mypy --strict clean** on 26 Python source files.
- Android `testDebugUnitTest` + `assembleDebug` continues to pass against M1–M3 contracts.
- Eight HA platforms now ship per paired device: `sensor`, `binary_sensor`, `button`, `select`, `switch`, `media_player`, `event`, `notify`.
- Three services exposed: `hassglass.notify`, `hassglass.dismiss`, `hassglass.identify`.
- Four blueprints in `custom_components/hassglass/blueprints/`: doorbell, timer, motion, alarm.
- Device records persist via `homeassistant.helpers.storage.Store` with one-shot legacy-`entry.data` migration.
- The only remaining integration-side work is the options-reload listener (now unblocked by the Store migration) and the assist_pipeline hard-dep promotion (blocked by the test harness, not the runtime).
- No real glasses connected yet — Glass Agent mic capture + TTS playback are the remaining hardware-gated items before "Hey Rokid → kitchen lights" works end-to-end.

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
- IMU head-nod / shake events (carried over from M4).
- Second-locale translations (carried over from M4).
- Options-flow auto-reload listener (carried over from M4; now unblocked by Store migration).
