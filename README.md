# HassGlass

[![Open your Home Assistant instance and add this repository in HACS.](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=constructorfleet&repository=hassglass)
[![Open your Home Assistant instance and start setting up HassGlass.](https://my.home-assistant.io/badges/config_flow_start.svg)](https://my.home-assistant.io/redirect/config_flow_start/?domain=hassglass)

A Home Assistant HACS-installable custom integration that turns **Rokid AR Glasses** into a first-class voice + visual satellite for the Home Assistant **Assist** pipeline.

HassGlass is to head-worn AR glasses what [View Assist](https://github.com/dinki/view_assist_integration) is to tablets and ESPHome devices: it bridges Assist (speech-to-text, intent recognition, conversation, text-to-speech) with the device's native I/O — microphones, speaker, single-eye micro-LED display, IMU, and touch/button input — without depending on the vendor cloud.

> Status: **design / pre-implementation**. This repository currently contains the architecture, protocol, and project outline that the implementation will follow. See [docs/ROADMAP.md](docs/ROADMAP.md).

---

## What HassGlass gives you

- **Hands-free voice control** of Home Assistant from the glasses, routed through the Assist pipeline you already use (Whisper / OpenAI / Cloud / etc.).
- **Glanceable HUD cards** rendered on the right-eye micro-LED panel — weather, timers, doorbell alerts, scene confirmations, sensor readouts.
- **Push notifications** to the HUD from any HA automation via a `hassglass.notify` service.
- **Gesture / button events** (touchpad swipe, side button, head-nod from IMU) surfaced as HA `event` entities so they can trigger automations.
- **Per-glasses satellite entities** (status, battery, wearer presence, current card, last intent) so dashboards and automations can react to who's wearing what.
- **No vendor cloud required** — glasses talk directly to your HA instance over your LAN.

## How it compares to View Assist

| | View Assist | HassGlass |
|---|---|---|
| Target hardware | Android tablet, Wyoming sat, ESPHome, PC | Rokid AR Glasses |
| Visual surface | Full Lovelace dashboard via `browser_mod` / Fully Kiosk | Lightweight HUD cards on micro-LED display |
| Audio | Wyoming satellite / device mic | On-glass mic array with hardware AEC |
| Satellite concept | Yes (master + per-device overrides) | Yes (master + per-glasses overrides) |
| Pipeline | Home Assistant Assist | Home Assistant Assist |
| Cloud dep | None | None |

If you already run View Assist, the mental model is identical — HassGlass just swaps the visual surface from a tablet dashboard to a HUD overlay and adds glasses-specific input (gestures, head pose).

## Installation

1. Add this repo in HACS with the button above, or directly via [my.home-assistant.io](https://my.home-assistant.io/redirect/hacs_repository/?owner=constructorfleet&repository=hassglass).
2. Install **HassGlass** from HACS and restart Home Assistant.
3. Sideload the **HassGlass Glass Agent** APK onto the glasses (built from `apps/glass_agent/`, see [docs/ROKID_TRANSPORT.md](docs/ARCHITECTURE.md#rokid-transport-layer)).
4. Optional: install **HassGlass Companion** on your iPhone for first-time pairing and BLE fallback.
5. Start the integration flow with the button above, or directly via [my.home-assistant.io](https://my.home-assistant.io/redirect/config_flow_start/?domain=hassglass), then follow the discovery flow in Home Assistant.

## Documentation

- **[Architecture](docs/ARCHITECTURE.md)** — system design, Rokid transport layers, Assist pipeline integration, HUD card model.
- **[Project Structure](docs/PROJECT_STRUCTURE.md)** — directory layout and file-by-file responsibilities.
- **[Roadmap](docs/ROADMAP.md)** — milestones from M0 (skeleton) through M5 (stable HACS release).

## Hardware references

- Rokid Glasses developer docs: <https://github.com/buildwithfenna/rokid-docs>
- Rokid CXR SDKs: CXR-M (mobile companion), CXR-S (on-device messaging), CXR-L (standalone on-glass app via YodaOS AIDL).

## License

TBD — see [LICENSE](LICENSE) once added (recommendation: Apache-2.0 to match the Rokid docs and most HACS integrations).
