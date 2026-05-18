# Pairing a pair of Rokid Glasses

This is the end-user walkthrough for getting a fresh pair of glasses talking to your Home Assistant install. If you're a developer setting up a build environment, see [DEVELOPING.md](DEVELOPING.md) instead.

## What you need

- A pair of Rokid Glasses with the **HassGlass Glass Agent** APK sideloaded (see [Installing the Glass Agent](#installing-the-glass-agent) below).
- Home Assistant **2024.12.0** or newer, reachable from the glasses' Wi-Fi network.
- HA served over **HTTPS** — the integration refuses to issue pairing tokens to a plaintext HA base URL. If you're on `http://homeassistant.local:8123`, set up Nabu Casa Remote UI or a reverse proxy with a real TLS cert first.

## One-time setup in HA

1. In Home Assistant, go to **Settings → Devices & Services → Add Integration**.
2. Search for **HassGlass** and pick it.
3. The first screen sets *master defaults* that apply to all paired glasses:
   - **Default Assist pipeline ID** — leave blank to use the HA "preferred" pipeline. Override per-device later via the Devices page.
   - **Enable on-glass wake word by default** — keep on if you want hands-free "Hi Rokid".
   - **Default HUD card lifetime** — milliseconds a `notify` card stays on the HUD before auto-dismiss.
   - **Fallback media_player entity ID** — used when the glasses aren't connected and an automation tries to announce something. Optional.
4. Click **Submit**. You'll land back at *Devices & Services* with a new **HassGlass** card.

Only one HassGlass *hub* is supported per HA install. All glasses get added as devices under this one entry.

## Pairing a device

1. Open the HassGlass card in *Devices & Services* and click **Add device**.
2. Power on the glasses with the Glass Agent installed. On first boot it will join the configured Wi-Fi, advertise itself as `_hassglass._tcp` on the LAN, and show a **6-digit pairing code** on the HUD.
3. Type that 6-digit code into the HA pairing dialog within ~2 minutes.
4. On submit, HA issues a per-device long-lived token. The Glass Agent persists it and opens its WebSocket session to HA.
5. Within a second or two, a new device appears under **HassGlass** with entities:
   - `sensor.<name>_battery` / `_signal` / `_last_intent` / `_current_card`
   - `binary_sensor.<name>_worn` / `_listening` / `_connected`
   - `select.<name>_pipeline` — per-device Assist pipeline override
   - `switch.<name>_wake_word` / `_listening_enabled`
   - `button.<name>_dismiss_top_card` / `_identify`
   - `event.<name>_gesture` / `_button`
   - `media_player.<name>` — TTS / announce target
   - `notify.<name>` — `notify.send_message` target

If anything goes wrong, the dialog reports the specific failure (unknown code, expired code, locked out, base URL not HTTPS, etc.). Codes live for **120 seconds**; after 5 wrong attempts the pairing endpoint locks itself for 60 seconds before accepting any code.

## Renaming a device

The default name comes from the Glass Agent itself. Rename freely in HA's **Devices** page — the device_id (used by services like `hassglass.notify`) stays stable.

## Removing / re-pairing

1. *Devices & Services → HassGlass → click the device → Delete*.
2. HA forgets the token. The glasses will keep retrying with their stored token, get rejected, and eventually return to pairing-code mode on the HUD.

If you want to forcibly rotate the token without removing the device, just delete-and-re-pair.

## Installing the Glass Agent

The Glass Agent is a small Android app that runs on YodaOS (Rokid's Android-derived OS). You install it via `adb` once; after that the app self-updates on subsequent connects (planned for M5).

1. Enable developer mode on the glasses (varies by firmware — see your Rokid docs).
2. Connect over Wi-Fi Direct (`adb connect <glasses-ip>:5555`) or USB if your unit supports it.
3. Download the latest `hassglass-agent.apk` from the [Releases page](https://github.com/teaganglenn/HassGlass/releases).
4. `adb install hassglass-agent.apk`.
5. Boot the glasses. The pairing code appears on the HUD within ~10 seconds of first launch.

If the glasses are off your home Wi-Fi during setup, the optional **HassGlass Companion** iOS app can relay the pairing over BLE — that path is more involved and documented in the Companion app's README.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Pairing dialog says "unknown or expired pairing code" | Code TTL is 120s | Restart pairing — *Add device* again to get a fresh code |
| "pairing temporarily locked due to failed attempts" | 5+ wrong codes in a row | Wait 60s and try again |
| Device shows `connected = off` minutes after pair | Glass Agent can't reach HA URL | Check HA reverse-proxy TLS cert + that the glasses Wi-Fi can resolve the configured `external_url` |
| No HUD card appears when calling `hassglass.notify` | `listening_enabled` is off OR the device isn't connected | Toggle the privacy switch on; check `binary_sensor.<name>_connected` |
| Voice never triggers | Wake-word switch off, OR pipeline_id points at a pipeline that no longer exists | Toggle `switch.<name>_wake_word` on; check `select.<name>_pipeline` |

See the [Architecture](ARCHITECTURE.md) doc for what each subsystem does and the [Roadmap](ROADMAP.md) for what's still hardware-gated.
