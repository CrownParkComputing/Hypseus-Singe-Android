# Hypseus Singe Android

This repository builds Android APKs for Hypseus Singe and publishes them through GitHub Actions.

## CI Releases and Versioning

- Every successful build on `main` or `master` creates or updates a GitHub Release.
- Release tag format is `v0.1.<run_number>`.
- APK files are uploaded with versioned names, for example:
  - `app-hypseus-debug-v0.1.123.apk`
  - `app-spaceace-debug-v0.1.123.apk`

## Runtime File Model (Android)

The app does not require SAe content to be stored in one fixed folder tree.

- SAe assets can live on external storage in any location.
- The APK deploys required runtime/support files into the app's own files area.
- You do not need to manually pre-create one strict `patched_singe/SAe/...` layout on external storage.

App-private files area (seeded by app/APK at runtime):

```
/sdcard/Android/data/<package>/files/
```

External content location:

- User-managed and flexible (for example, a Retroid SAe folder).
- Path can vary per device/setup.

Notes:

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
