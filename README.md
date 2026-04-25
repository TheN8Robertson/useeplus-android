# USEEPLUS Endoscope for Android

A minimal Android app that talks to USEEPLUS / "Geek szitman supercamera" USB endoscopes directly, without the vendor app or Play Services. Designed for GrapheneOS but works on any stock Android 10+.

## Why

USEEPLUS endoscopes (USB IDs `2ce3:3828` and `0329:2022`) use a proprietary USB protocol, not USB Video Class (UVC). That's why generic Android webcam apps can't see them — on any Android, not just GrapheneOS. This app ports the reverse-engineered Linux driver into the NDK so the endoscope works on-device.

## Status

| Feature | Status |
| --- | --- |
| USB host permission + device enumeration | done |
| Native driver (FD-injected libusb) | done |
| Live preview (JPEG decode loop) | done |
| Snapshot to `Pictures/USEEPLUS` | done |
| "Record" → JPEG sequence in `Pictures/USEEPLUS/rec_<timestamp>/` | done (convert offline with ffmpeg) |
| Physical button (short-press) → snapshot | done |
| Multi-device picker | done |
| Dual-lens (forward/side) | hardware-controlled — long-press the endoscope's button |
| LED brightness | hardware-controlled — physical dial on the plug |
| On-device H.264 MP4 encoding | planned (needs GL input-surface encoder) |

**Hardware controls live on the endoscope itself, not in the app.** The button on the plug short-presses to snapshot and long-presses to flip between forward and side lens; the LED ring brightness has its own dial. The vendor app's own dual-lens-switching command exists in the binary (`BB AA 0B 00 02 …`) but is dead code — the hardware does the routing internally and our app just streams whichever lens is currently active.

## Install

Every push to `main` cuts a signed GitHub release. The release keystore is stable (stored in repo secrets) so the APK signature stays consistent across builds — in-place upgrades work.

**Obtainium (recommended on GrapheneOS):**
1. Add app → paste the repo URL: `https://github.com/TheN8Robertson/useeplus-android`
2. Obtainium will poll **Releases** and install `useeplus-endoscope-vX.Y.Z.apk` when versions bump.

**Manual:**
1. Go to [**Releases**](https://github.com/TheN8Robertson/useeplus-android/releases) → latest → download the `.apk`.
2. Open it with a file manager / `adb install`.
3. On first plug-in of the endoscope, GrapheneOS prompts for USB permission — grant it.

Versioning: `versionCode` tracks `git rev-list --count HEAD`; `versionName` is `0.2.<count>`. Every push to `main` gets a new release tag.

## Build locally (optional)

You do **not** need Android Studio to get an APK — see *Install* above. To build locally anyway: install a JDK 17 + Android SDK + NDK `27.2.12479018`, then `./gradlew assembleRelease`. Without the release keystore env vars, the release build falls back to debug signing (fine for local testing, bad for Obtainium upgrades).

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

- **Higher resolution** — the camera ships 640×480 despite advertised higher resolutions; may or may not be unlockable via a control command.
- **Init sequence variance** — hbens + jmz3 send `FF 55 FF 55 EE 10` to endpoint 2 on interface 0 *and* `BB AA 05 00 00` to endpoint 1 on interface 1. ollyoid only sends the second. If the full sequence fails on your firmware variant, try commenting out the EP2 write in `supercamera_core.cpp`.

## Release signing

Release signing is wired up via four GitHub Actions secrets:

- `RELEASE_KEYSTORE_B64` — base64-encoded JKS keystore
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

To rotate the keystore:

```bash
keytool -genkeypair -v -keystore release.jks -alias useeplus \
        -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks | gh secret set RELEASE_KEYSTORE_B64 \
        --repo TheN8Robertson/useeplus-android
gh secret set RELEASE_KEYSTORE_PASSWORD --repo TheN8Robertson/useeplus-android
gh secret set RELEASE_KEY_ALIAS --repo TheN8Robertson/useeplus-android
gh secret set RELEASE_KEY_PASSWORD --repo TheN8Robertson/useeplus-android
```

Rotating the keystore **invalidates the existing install** — Obtainium users will need to uninstall and reinstall, since Android rejects signature changes.

## License

Code in this repo is CC0-1.0 (public domain dedication), matching the upstream reverse-engineering work. See `LICENSE` for the full text.
