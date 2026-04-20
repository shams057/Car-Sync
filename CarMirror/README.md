# CarMirror — Android Car Infotainment Screen Mirror

Mirror your iPhone or Android phone to your car's Android infotainment unit  
using scrcpy over USB or WiFi. Low latency, no subscriptions, fully offline.

---

## How It Works

```
[Phone] ──USB/WiFi──► [scrcpy protocol] ──► [Car Unit: CarMirror app]
                                                     │
                                            H.264 stream decoded
                                            via MediaCodec → SurfaceView
```

The car unit runs CarMirror as the **receiver**.  
Your phone is the **source** — it needs USB Debugging (Android) or Trust (iOS).

---

## Quick Start

### Android Phone → Car Unit

1. On your **phone**: Settings → Developer Options → Enable USB Debugging
2. Connect phone to car unit via USB cable
3. Open **CarMirror** on the car unit → tap **Android**
4. Tap **Connect USB** — accept the ADB prompt on your phone
5. Mirror starts automatically

**WiFi alternative:**
1. On your phone: `adb tcpip 5555` (once, via PC terminal)
2. In CarMirror → Android → enter your phone's IP → **Connect WiFi**

---

### iPhone / iPad → Car Unit

1. Connect iPhone to car unit via **USB (Lightning or USB-C)**
2. On your iPhone: tap **"Trust"** when prompted
3. Open **CarMirror** → tap **iOS**
4. Tap **Start Mirror**

> **Note:** iOS support requires scrcpy v3.1+ compiled with iOS/libimobiledevice support.  
> See "iOS Binary Setup" below.

---

## Project Structure

```
CarMirror/
├── app/src/main/
│   ├── java/com/carmirror/
│   │   ├── ui/
│   │   │   ├── MainActivity.java          ← Home screen (iOS / Android buttons)
│   │   │   ├── AndroidMirrorActivity.java ← Android mirror UI + connection
│   │   │   └── iOSMirrorActivity.java     ← iOS mirror UI + instructions
│   │   ├── util/
│   │   │   ├── ScrcpyBridge.java          ← Manages ADB + scrcpy server launch
│   │   │   ├── ScrcpyDecoder.java         ← H.264 MediaCodec decoder → Surface
│   │   │   ├── iOSBridge.java             ← iOS-specific scrcpy launcher
│   │   │   └── NetworkScanner.java        ← WiFi subnet scanner for ADB devices
│   │   └── service/
│   │       └── MirrorForegroundService.java ← Keeps mirror alive in background
│   └── res/
│       ├── layout/                        ← All screen layouts
│       ├── values/                        ← Colors, strings, themes
│       └── font/                          ← Display + mono fonts (add manually)
└── README.md
```

---

## Setup Steps in Android Studio

### 1. Open Project
```
File → Open → select the CarMirror/ folder
```

### 2. Add Fonts
Download free fonts from Google Fonts and place in `app/src/main/res/font/`:
- **Space Grotesk Bold** → rename to `display.ttf`
- **JetBrains Mono Regular** → rename to `mono.ttf`

### 3. Add scrcpy Server Binary
Download scrcpy from: https://github.com/Genymobile/scrcpy/releases

Place in `app/src/main/assets/`:
- `scrcpy-server-v3.1`   (the server JAR — runs on the phone)
- `scrcpy`               (native binary — compiled for your car unit's ABI)

**For the native binary**, you need the ARM64 build:
```bash
# Download the scrcpy Linux ARM64 release or build from source:
# https://github.com/Genymobile/scrcpy/blob/master/doc/build.md
```

If your car unit runs standard Android (not Linux), use the ADB-over-shell approach
(already implemented in ScrcpyBridge) — no native binary needed for Android-to-Android.

### 4. iOS Support (Optional)

iOS mirroring requires libimobiledevice on the car unit.

**Option A — Rooted car unit:**
```bash
# Install via Magisk or termux-extra repos:
pkg install libimobiledevice
```

**Option B — Companion Linux device (Raspberry Pi / PC):**
Run scrcpy with iOS on a Pi connected to the car unit's USB hub.
CarMirror connects to it over local network.

**Option C — Android 14+ car units with Termux:**
```bash
# In Termux:
pkg install libimobiledevice scrcpy
```

### 5. Build & Install
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## ADB Setup on Car Unit

Most Android car units support ADB. Enable it:

1. Settings → About → tap Build Number 7× to enable Developer Options
2. Settings → Developer Options → Enable USB Debugging
3. Settings → Developer Options → Enable **Wireless Debugging** (Android 11+)

For the car unit to be an ADB *host* (to control phones), you may need:
- Root access, OR
- A car unit ROM with ADB host mode enabled (many Qualcomm-based units support this)

---

## Supported Car Unit ROMs

| ROM / Unit | Android Ver | ADB Host | Notes |
|------------|-------------|----------|-------|
| Qualcomm SA8155P | Android 10-13 | ✓ Root | Common in premium cars |
| MTK-based generic | Android 9-11 | ✓ | Most aftermarket units |
| AAOS (Android Automotive) | Android 11+ | ✓ | Full support |
| RockChip PX6/PX5 | Android 9 | ✓ Root | Common aftermarket |

---

## Troubleshooting

**"No device found" via USB:**
- Enable USB Debugging on phone
- Use a data-capable USB cable (not charge-only)
- Accept the ADB prompt on your phone

**"ADB connect failed" via WiFi:**
- Phone must be on same WiFi network as car unit
- Run `adb tcpip 5555` once on the phone (requires USB first)
- Check firewall / router AP isolation

**iOS "No device detected":**
- Tap "Trust This Computer" on the iPhone when prompted
- Try a different USB cable
- Ensure libimobiledevice is installed on car unit

**Low FPS / laggy:**
- Use USB instead of WiFi for lowest latency
- Reduce phone screen resolution: `adb shell wm size 1280x720`
- Ensure car unit is not thermal-throttling

---

## Architecture Notes

### Why scrcpy?
- Open source, actively maintained
- Sub-100ms latency over USB
- No app needed on source phone (for Android)
- H.264/H.265 hardware encoding on phone → hardware decoding on car unit
- Works without root on source device

### Video Pipeline
```
Phone GPU → MediaCodec encoder (H.264) → scrcpy protocol
→ ADB forward (TCP socket)
→ Car unit: local socket → ScrcpyDecoder (MediaCodec) → SurfaceView
```

### Why not Miracast / WiDi?
Miracast requires kernel-level Wi-Fi Direct P2P — not accessible via Android SDK.
Available only on rooted devices or specific OEM implementations.

---

## License
MIT — use freely in your car builds.
scrcpy is Apache 2.0 (Genymobile).
