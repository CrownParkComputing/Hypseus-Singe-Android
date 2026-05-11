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

## Upstream Sync Pipeline (Deterministic)

Game framework/script assets bundled in APKs are synced from upstream with pinned refs.

Source:

- `https://github.com/DirtBagXon/hypseus_singe_data`
- Paths used:
  - `00-singe2/Framework` (or fallback `FrameworkKimmy`)
  - `00-singe2/SAe/SAe.singe`
  - `00-singe2/DLe/DLe.singe`
  - `00-singe2/DL2e/DL2e.singe`

Synced outputs in this repo:

- `android-app/app/src/main/assets/runtime/singe/Framework/`
- `android-app/app/src/main/assets/runtime/templates/spaceace/SAe.singe`
- `android-app/app/src/main/assets/runtime/templates/dle/DLe.singe`
- `android-app/app/src/main/assets/runtime/templates/dl2e/DL2e.singe`
- Lock manifests:
  - `android-app/upstream/spaceace-sync-lock.json`
  - `android-app/upstream/dle-sync-lock.json`
  - `android-app/upstream/dl2e-sync-lock.json`

### Local sync command

Run from repository root:

```powershell
./android-app/scripts/sync_spaceace_upstream.ps1 -SingeDataRef master
./android-app/scripts/sync_dle_upstream.ps1 -SingeDataRef master
./android-app/scripts/sync_dl2e_upstream.ps1 -SingeDataRef master
```

You can also pin a tag or commit SHA:

```powershell
./android-app/scripts/sync_spaceace_upstream.ps1 -SingeDataRef <tag-or-sha>
```

### GitHub manual workflow

Use workflow:

- `.github/workflows/sync-spaceace-upstream.yml`
- `.github/workflows/sync-dle-upstream.yml`
- `.github/workflows/sync-dl2e-upstream.yml`

Inputs:

- `singe_data_ref`: branch/tag/SHA to pin
- `commit_message`: commit message for generated sync commit

These workflows update bundled runtime files and commit only when changes are detected.

## Related Documentation

- `doc/SingeGameLayout.md`
