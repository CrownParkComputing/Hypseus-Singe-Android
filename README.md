# Hypseus Singe Android

This repository builds Android APKs for Hypseus Singe and publishes them through GitHub Actions.

## CI Releases and Versioning

- Every successful build on `main` or `master` creates or updates a GitHub Release.
- Release tag format is `v0.1.<run_number>`.
- Only the Space Ace APK is built and uploaded.
- APK file is uploaded with a versioned name, for example:
  - `app-spaceace-debug-v0.1.123.apk`

## Runtime File Model (Android)

Use one Space Ace content folder on external storage, then select that folder in the app.

Example external base path used in testing:

```
/storage/FEDD-B1FF/Hypseus/SAe/
```

Exact external layout for Space Ace:

```
SAe/
  SAe.txt
  sae.singe
  Video/
    ... laserdisc video/audio files referenced by SAe.txt ...
  roms/
    ace.zip
    # or extracted equivalent:
    # ace/
    #   sa_*.bin
```

Runtime behavior with this layout:

- `-framefile` uses `SAe/SAe.txt`.
- `-romdir` uses `SAe/roms`.
- `sae.singe` is read from `SAe/sae.singe` and patched into app-private storage.

App-private files area (seeded by app/APK at runtime):

```
/sdcard/Android/data/<package>/files/
```

Notes:

- `org.hypseus.singe.spaceace`: Space Ace locked launcher (`spaceace` flavor)

## Related Documentation

- `doc/SingeGameLayout.md`
