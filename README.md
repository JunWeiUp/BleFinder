# BleFinder 🔎

[![Android CI](https://github.com/JunWeiUp/BleFinder/actions/workflows/android.yml/badge.svg)](https://github.com/JunWeiUp/BleFinder/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-21%2B-green.svg)](app/build.gradle.kts)

**English** | [简体中文](README.zh-CN.md)

Find lost Bluetooth devices by tracking their signal strength (RSSI) in real time — like a metal detector for your earbuds, smartwatch, or tracker. The lost device does **not** need to make any sound; it only needs to be powered on.

## Features

- **Dual-channel scanning** — BLE advertisements (`BluetoothLeScanner`, low-latency mode) plus classic Bluetooth discovery (auto-restarting loop), covering earbuds, wearables, trackers and legacy devices.
- **Device list sorted by signal strength** with color-coded zones (green = near, red = far); devices not seen for 20 s drop off automatically.
- **Custom device names** — long-press a device (or tap the ✏️ icon) to rename it; names are remembered by MAC address.
- **Tracking mode**:
  - Large, smoothed live RSSI reading (exponential moving average)
  - Signal strength bar and a rough distance estimate (log-distance model)
  - **Per-device 1-meter calibration** — sample the target device once at 1 m; distance estimates for that device become far more accurate than the generic default
  - Trend arrow: ↑ getting closer / ↓ moving away / → steady (last 2 s vs. 4–8 s ago)
  - Live signal-history chart
  - **Beep that speeds up as you approach** — no need to watch the screen while crawling under the bed
  - Vibration on approach; screen stays on while tracking
- **🧭 Direction compass (body-blocking)** — hold the phone flat against your chest, screen facing out, and turn a full circle in place. Your body blocks the signal from behind you, so the polar plot's strongest sector (green arrow) points toward the device. A white triangle tracks the direction you are facing; the confidence drops when reflections create multiple strong sectors.
- **🗺 Signal trail heatmap** — walk around holding the phone upright as usual; your path is drawn and colored by signal strength (green = closest). Look back and head straight for the greenest stretch.
- Bilingual UI (English / 简体中文), dark theme, works from Android 5.0 (API 21) and adapts to the Android 12+ Bluetooth permission model.

## Getting the app

Grab the `app-debug-apk` artifact from the latest [Actions run](https://github.com/JunWeiUp/BleFinder/actions/workflows/android.yml), or build it yourself:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Note: `settings.gradle.kts` puts Aliyun/Tencent Maven mirrors first with the official Google/Maven Central repos as fallback, so the build works both inside and outside mainland China.

## How to find your device

1. Make sure the lost device is **powered on**. A device that is off does not advertise anything and cannot be found by any app.
2. Open the app, grant the Bluetooth permissions (on Android 11 and below the system also requires the location service to be on — the app never reads your location).
3. Identify your device in the list by name/MAC (it is usually among the strongest signals). Rename it if you like.
4. Tap it to enter tracking mode, then **move slowly** — half a meter at a time, pausing a couple of seconds:
   - Every **+6 dBm** roughly **halves the distance**.
   - Rotate the phone slowly; one orientation will show a clearly stronger signal (antenna directionality).
   - Walls, metal and your own body attenuate the signal a lot.
5. The beep speeds up as you get closer. Above roughly **-55 dBm** the device is generally within one meter — start looking around.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+) | Scan and read device names |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` (Android 11-) | Legacy scan support |
| `ACCESS_FINE_LOCATION` (Android 11-) | System requirement for Bluetooth scanning; never used to read your location, and the scan is flagged `neverForLocation` on Android 12+ |
| `VIBRATE` | Haptic feedback when approaching |

## Limitations

- RSSI fluctuates heavily with reflections and obstructions; the distance estimate is only approximate — trust the **trend arrow and color zones**.
- High-frequency scanning drains battery; exit tracking once you have found the device.
- On some phones, running BLE scanning and classic discovery in parallel may slow down classic results.

## Project structure

```
app/src/main/java/com/blefinder/app/
  MainActivity.kt       # Permission flow, scan list, tracking UI, beep/vibration feedback
  ScanManager.kt        # Dual-channel BLE + classic discovery scanner
  DeviceListAdapter.kt  # Device list adapter
  DeviceNameStore.kt    # Custom device names, persisted per MAC address
  SparkView.kt          # Signal history chart (custom view)
```

## Contributing

Issues and pull requests are welcome — especially tuning of the RSSI smoothing, trend thresholds and proximity zones on different phone models.

## License

[MIT](LICENSE)
