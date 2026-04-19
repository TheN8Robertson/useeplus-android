# USEEPLUS Endoscope for Android

A minimal Android app that talks to USEEPLUS / "Geek szitman supercamera" USB endoscopes directly, without the vendor app or Play Services. Designed for GrapheneOS but works on any stock Android 10+.

## Why

USEEPLUS endoscopes (USB IDs `2ce3:3828` and `0329:2022`) use a proprietary USB protocol, not USB Video Class (UVC). That's why generic Android webcam apps can't see them — on any Android, not just GrapheneOS. This app ports the reverse-engineered Linux driver into the NDK so the endoscope works on-device.

## Status

**v0.1** — scaffolded, not yet tested on a real device.

| Feature | Status |
| --- | --- |
| USB host permission + device enumeration | done |
| Native driver (FD-injected libusb) | done |
| Live preview (JPEG decode loop) | done |
| Snapshot to `Pictures/USEEPLUS` | done |
| "Record" → JPEG sequence in `Pictures/USEEPLUS/rec_<timestamp>/` | done (convert offline with ffmpeg) |
| On-device H.264 MP4 encoding | **planned** (needs GL input-surface encoder) |
| Physical button → snapshot | done |
| Multi-device picker | done |
| LED brightness slider | **stub** — protocol not yet reverse-engineered |
| Dual-cam (left/right) split view | single-cam only in v0.1 |

## Build

You do **not** need Android Studio. Every push to `main` builds a debug APK in GitHub Actions:

1. Push to `main` (or open a PR).
2. Open the run under **Actions → Build APK**.
3. Download the `useeplus-endoscope-debug-<sha>` artifact — that's the APK.
4. Install via Obtainium, `adb install`, or sideload directly.

To build locally anyway: install Android Studio + NDK `27.2.12479018`, then `./gradlew assembleDebug`.

## Install on GrapheneOS

1. Enable **Settings → Apps → Install unknown apps** for your chosen file manager.
2. Open the downloaded `.apk`.
3. On first plug-in of the endoscope, GrapheneOS will prompt for USB permission — grant it to the app.

Apk is debug-signed (ephemeral keystore per CI run). That means each CI build has a different signing certificate, so upgrading requires uninstalling first. Wire up a release keystore in GitHub Secrets if you want stable upgrades — see *Release signing* below.

## Architecture

```
app/src/main/
├── cpp/
│   ├── supercamera_core.{hpp,cpp}   Ported from jmz3 — protocol parser + libusb control
│   ├── jni_bridge.cpp               C++ ↔ Kotlin glue, worker thread, frame queue
│   └── CMakeLists.txt               Fetches libusb-cmake, builds libuseeplus.so
└── kotlin/com/naterobertson/useeplus/
    ├── MainActivity.kt              UI + permission flow + poll loop
    ├── NativeBridge.kt              JNI declarations
    ├── UsbDeviceFinder.kt           Enumerate USEEPLUS VID/PID matches
    ├── UsbPermissionHelper.kt       BroadcastReceiver permission dance
    ├── PreviewView.kt               Custom view rendering latest JPEG
    ├── VideoRecorder.kt             Writes JPEG sequence for offline ffmpeg conversion
    └── GallerySaver.kt              MediaStore snapshot save
```

Frames flow: `USB bulk read → UPPCameraParser (accumulates JPEG chunks by frame_id) → AndroidCapture latest-frame slot → Kotlin poll loop → PreviewView.pushJpeg() + optional VideoRecorder.pushJpeg()`.

## Attribution

This port is built on reverse-engineering work by others:

- [hbens/geek-szitman-supercamera](https://github.com/hbens/geek-szitman-supercamera) — original protocol reverse-engineering, CC0-1.0.
- [jmz3/EndoscopeCamera](https://github.com/jmz3/EndoscopeCamera) — refactor into a reusable C++ core, CC0-1.0. Our `supercamera_core.cpp` is a direct port of theirs with Android FD-injection.
- [ollyoid/useeplus-linux-v4l2-driver](https://github.com/ollyoid/useeplus-linux-v4l2-driver) — GPL Linux kernel V4L2 driver (referenced for cross-check; no code copied).

## Converting a recording to MP4

After hitting "Record" → "Stop Recording", the app saves JPEGs to
`Pictures/USEEPLUS/rec_<yyyymmdd_hhmmss>/`. Pull them to a computer (MTP,
`adb pull`, whatever) and run:

```bash
ffmpeg -framerate 30 -pattern_type glob -i 'frame_*.jpg' \
       -c:v libx264 -pix_fmt yuv420p out.mp4
```

This deliberate punt means v0.1 ships without the ~300 lines of EGL/GL
plumbing required to feed a MediaCodec input surface. Planned for v0.2.

## Known open questions

- **LED brightness / control commands** — none of the three reference repos have reverse-engineered control commands. The slider is a visible stub. If you capture the vendor app's USB traffic (`usbmon` on Linux while sliding the in-app brightness), that would fill this in.
- **Higher resolution** — the camera ships 640×480 despite advertised higher resolutions; may or may not be unlockable via a control command.
- **Init sequence variance** — hbens + jmz3 send `FF 55 FF 55 EE 10` to endpoint 2 on interface 0 *and* `BB AA 05 00 00` to endpoint 1 on interface 1. ollyoid only sends the second. If the full sequence fails on your firmware variant, try commenting out the EP2 write in `supercamera_core.cpp`.

## Release signing

To get stable-upgrade signing:

1. Locally: `keytool -genkeypair -v -keystore release.jks -alias useeplus -keyalg RSA -keysize 4096 -validity 10000`
2. Base64 encode: `base64 release.jks > release.jks.b64`
3. Add four GitHub Actions secrets: `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
4. Adjust `app/build.gradle.kts` and `.github/workflows/build.yml` to decode the keystore and run `assembleRelease` with that signing config. (Not wired up in v0.1 — keeps the debug path simple.)

## License

Code in this repo is CC0-1.0 (public domain dedication), matching the upstream reverse-engineering work. See `LICENSE` for the full text.
