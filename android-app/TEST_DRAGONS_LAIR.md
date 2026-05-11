# Dragon's Lair Android Test (Phase 1)

## What is wired already
- Native bridge entrypoint: `NativeBridge.nativeRun(args, homeDir, dataDir)`
- JNI implementation calls the emulator core runner and injects `-homedir`/`-datadir` defaults.
- Compose screen has a **Test Dragon's Lair** button.

## Current test assumptions
The test button currently launches with:
- game: `lair vldp`
- framefile: `<app files>/vldp/lair/lair.txt`
- rom zip expected at: `<app files>/roms/lair.zip`
- renderer preference: `-vulkan` (will fallback to GLES/software in native code)

`<app files>` corresponds to `getExternalFilesDir(null)` when available,
which maps to `/sdcard/Android/data/org.hypseus.singe/files` for adb push.

## Build and install
1. Open `android-app` in Android Studio (JDK 17, Android SDK 35, NDK r26+).
2. Build and install debug app.

### Native configure check (optional)
```powershell
./scripts/configure_native_android.ps1
```

If configure fails at `libtoolize` / `autoreconf` / `pkg-config`, install MSYS2 build tools and add them to PATH before configuring:
- `pacman -S --needed autoconf automake libtool pkgconf make`

## Push assets from Windows (ADB)
Use the helper script:

```powershell
./scripts/push_lair_assets.ps1 \
  -Package org.hypseus.singe \
  -LocalFramefile "C:\\path\\to\\lair.txt" \
  -LocalMpegFolder "C:\\path\\to\\lair\\mpeg" \
  -LocalRomZip "C:\\path\\to\\lair.zip"
```

## Run test
1. Launch app.
2. Tap **Test Dragon's Lair**.
3. If native run exits, app shows return code toast.

## Known blockers before production testing
- Android dependency provisioning for SDL2/SDL2_image/SDL2_ttf/SDL2_mixer/libzip/vorbis/ogg must be finalized in NDK build.
- Asset import UX (SAF picker) is not yet implemented.
- This phase uses a minimal launcher flow and does not yet embed gameplay surface into Compose.
