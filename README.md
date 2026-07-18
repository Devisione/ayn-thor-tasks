# Thor Display Swapper

**Android app for AYN Thor** — swap and move apps between the top and bottom screens without restarting them.

> Keywords: AYN Thor · AYN Odin · dual screen · dual display · handheld · portable console · display swap · task swap · accessibility service · wireless ADB · clamshell lid

## What it is

On dual-screen Android devices, an app opened on one display usually cannot be moved to the other without closing it. Thor Display Swapper **moves live tasks between displays** over wireless ADB, keeping session state.

Built for **AYN Thor** (two physical screens), and usable on other multi-display Android 11+ devices (API 30+).

## Features

- **Swap / move apps** — double Back; a single app can still be transferred
- **Push active app** — long Back (1 second)
- **All-apps list** — long AYN
- **Minimize all displays** — long Home (gamepad)
- **Short AYN** — native AYN menu via gpio replay (requires wireless ADB)
- **Wireless ADB** — pair without USB; identity backup survives uninstall
- **Gesture settings** — customize double/long actions for Back / Home / AYN
- **Logic checks** — `./gradlew test` (JVM simulation, no device required)

## Requirements

| Item | Value |
|------|--------|
| Device | AYN Thor (or any device with 2+ displays) |
| Android | 11+ (API 30), 13+ recommended |
| Debugging | Wireless debugging (Wireless ADB) |
| Permissions | Accessibility service, battery optimization exemption |

## Quick setup

1. Install the APK from [Releases](https://github.com/Devisione/ayn-thor-tasks/releases) (`ThorDisplaySwapper-vX.Y.Z.apk`)
2. Enable **Developer options** → **Wireless debugging**
3. In the app: **pair** (pairing port + 6-digit code)
4. **Connect** using the connection port
5. Enable the **Accessibility** service “Thor Display Swapper”
6. Disable **battery optimization** for the app

After setup, double Back swaps apps; long Back pushes the active app to the other screen.

## Build from source

```powershell
.\gradlew.bat assembleRelease
```

Unit tests (no device):

```powershell
.\test.ps1
```

APK output: `app/build/outputs/apk/release/ThorDisplaySwapper-v*.apk`

### Stack

- Kotlin + Jetpack Compose (Material 3)
- Accessibility Service (Back / Home / AYN gestures)
- [kadb](https://github.com/flyfishxu/kadb) — on-device wireless ADB
- `am stack move-task` / `am start` — move tasks between displays

## How it works

```
┌─────────────────┐     long Back / double Back    ┌──────────────────┐
│ Accessibility   │ ─────────────────────────────► │  DisplaySwapper  │
│ Service         │                                │                  │
└────────┬────────┘                                └────────┬─────────┘
         │ tracks foreground apps                           │
         │ per display                                      │ dumpsys + am stack
         ▼                                                  ▼
┌─────────────────┐                                ┌──────────────────┐
│ Display 0: App A│ ◄──────── swap tasks ────────► │ Display 1: App B │
└─────────────────┘                                └──────────────────┘
```

1. The accessibility service tracks which app is on which display
2. On swap/push, wireless ADB reads tasks (`dumpsys activity`)
3. Tasks are moved with `am stack move-task` / related shell commands
4. Apps keep running on the new display without a cold restart

Short AYN consumes the physical gpio press (so firmware does not blank the bottom screen on hold), then replays one gpio tap over ADB so the vendor AYN menu still opens. After closing/opening the lid, the app reconnects wireless ADB automatically so short AYN keeps working.

## Search keywords

`AYN Thor`, `AYN Odin`, `Thor Display Swapper`, `dual screen swap`, `dual display android`, `display swapper`, `task swap`, `clamshell handheld`, `portable android console`, `wireless adb`, `accessibility service`, `am stack move-task`, `gpio-keys`, `AYN button`

## License

Open source. Use at your own risk.

## Author

[Devisione](https://github.com/Devisione)
