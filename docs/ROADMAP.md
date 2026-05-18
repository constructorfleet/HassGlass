# HassGlass Roadmap

Milestones are scoped so each one ends with something runnable and demoable. Dates are intentionally omitted — this is a hobby-scale community project and a milestone is "done" when it works, not when a date passes.

---

## M0 — Skeleton ✱ design only ✱

**Outcome:** repo bootstrapped, CI green on an empty integration.

- [ ] Architecture, project structure, roadmap docs (this set of files).
- [ ] `custom_components/hassglass/manifest.json` + `__init__.py` returning `True` from `async_setup`.
- [ ] `hacs.json` + passing HACS validation workflow.
- [ ] `hassfest` workflow green.
- [ ] `ruff` + `mypy --strict` pre-commit + CI.
- [ ] LICENSE chosen.

---

## M1 — Pairing & telemetry over the wire

**Outcome:** A pair of glasses can be paired to HA. The integration shows online/battery/RSSI for the device. No audio yet.

- [ ] Glass Agent: foreground service binds to CXR-L, opens WS to HA, sends `hello` + `telemetry`.
- [ ] Glass Agent: 6-digit pairing flow on the HUD.
- [ ] Integration: `config_flow.py` master setup + per-device pairing-code flow.
- [ ] Integration: `ws_server.py` + `protocol.py` (control plane only, no audio frames).
- [ ] Integration: `sensor.<glasses>_battery`, `sensor.<glasses>_signal`, `binary_sensor.<glasses>_worn`.
- [ ] Tests: protocol round-trip, config flow happy path.

---

## M2 — Visual: HUD cards from HA

**Outcome:** Any HA automation can push a card to the glasses HUD.

- [ ] Implement `hud.show` / `hud.update` / `hud.dismiss` end-to-end.
- [ ] Card kinds: `toast`, `icon_text`, `alert` (other kinds deferred to M3).
- [ ] `hassglass.notify` service + `notify.<device>` platform.
- [ ] `sensor.<glasses>_current_card`, `button.<glasses>_dismiss_card`.
- [ ] Card priority stack + TTL on the Glass Agent.
- [ ] Blueprint: doorbell → HUD alert.

---

## M3 — Voice: full Assist pipeline integration

**Outcome:** "Hey Rokid, turn on the kitchen lights" works end-to-end, with a "listening / thinking / done" HUD state.

- [ ] Glass Agent: always-on wake word + push-to-talk on side button.
- [ ] Glass Agent: mic capture → binary WS frames.
- [ ] Glass Agent: TTS playback from inbound binary frames.
- [ ] Integration: `audio.py` → `assist_pipeline.async_pipeline_from_audio_stream`.
- [ ] Integration: `tts_relay.py` transcodes TTS output and streams down to glasses.
- [ ] Integration: `pipeline_bridge.py` forwards pipeline events to the agent.
- [ ] `select.<glasses>_pipeline`, `switch.<glasses>_wake_word`, `binary_sensor.<glasses>_listening`.
- [ ] Card kinds added: `list`, `timer`, `media`.

---

## M4 — Polish, gestures, blueprints

**Outcome:** Daily-driver quality. Gestures trigger automations. Useful starter blueprints. Settings are discoverable.

- [ ] Touchpad gesture events + side-button events → HA event entities.
- [ ] IMU-based head-nod / shake events (opt-in).
- [ ] Auto-mapping from common intent replies → cards (weather, timer, list, music).
- [ ] Translations: English plus a community-contributed second locale.
- [ ] End-user docs: `PAIRING.md`, troubleshooting guide.
- [ ] Blueprints shipped: timer-to-HUD, alarm-to-HUD, motion-alert-to-HUD.

---

## M5 — HACS default repo submission

**Outcome:** Available in HACS without adding a custom repository.

- [ ] All hassfest + HACS validation clean for ≥4 weeks.
- [ ] ≥2 external testers with non-developer-mode glasses.
- [ ] Stable `hassglass/1` protocol with a version compatibility test matrix.
- [ ] Release `v1.0.0` with signed APK.
- [ ] Open HACS default-repo PR.

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
