# Hypseus Singe Android

This repository builds Android APKs for Hypseus Singe and publishes them through GitHub Actions.

## CI Releases and Versioning

- Every successful build on `main` or `master` creates or updates a GitHub Release.
- Release tag format is `v0.1.<run_number>`.
- APK files are uploaded with versioned names, for example:
  - `app-hypseus-debug-v0.1.123.apk`
  - `app-spaceace-debug-v0.1.123.apk`

## Exact Runtime File Layout Required

All game data must be placed under Android app files storage:

```
/sdcard/Android/data/<package>/files/
```

For the multi-game package (`org.hypseus.singe`), required Singe layout:

```
files/
  patched_singe/
    singe/
      Framework/
        globals.singe
    SAe/
      sae.singe
```

General per-game rule:

```
files/
  patched_singe/
    <GameFolder>/
      <main_script>.singe
```

The main script is expected to load framework globals from:

```
BASEDIR .. "/Framework/globals.singe"
```

so `patched_singe/singe/Framework/globals.singe` must exist.

## Package and Launcher Mapping

- `org.hypseus.singe`: multi-game launcher (`hypseus` flavor)
- `org.hypseus.singe.spaceace`: Space Ace locked launcher (`spaceace` flavor)

## Optional Dragon's Lair Test Asset Layout

When using the Android Dragon's Lair test flow:

```
files/
  vldp/
    lair/
      lair.txt
  roms/
    lair.zip
```

## Related Documentation

- `doc/SingeGameLayout.md`
- `android-app/TEST_DRAGONS_LAIR.md`
