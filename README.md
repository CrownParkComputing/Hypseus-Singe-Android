# Hypseus Singe Android

This repository builds Android APKs for Hypseus Singe and publishes them through GitHub Actions.

## CI Releases and Versioning

- Every successful build on `main` or `master` creates or updates a GitHub Release.
- Release tag format is `v0.1.<run_number>`.
- Five APK flavors are built and uploaded per run.
- APK files are uploaded with versioned names, for example:
  - `app-hypseus-debug-v0.1.123.apk`
  - `app-spaceace-debug-v0.1.123.apk`
  - `app-dle-debug-v0.1.123.apk`
  - `app-dl2e-debug-v0.1.123.apk`
  - `app-dlclassic-debug-v0.1.123.apk`

## Runtime File Model (Android)

All Android flavors now expect users to provide their own game folders on external storage.
Pick the game folder inside the app; the launcher detects the framefile, Singe script, and ROM folder from that base directory.

Example external root used during testing:

```
/storage/FEDD-B1FF/Hypseus/
```

### Folder layouts by APK

#### `app-spaceace-*.apk`

Select the `SAe` folder.

Preferred layout that matches current device testing:

```
SAe/
  SAe/
    SAe.txt
    SAe.singe
    Roms/
      spaceace.zip
      # or ace.zip / sae.zip
  Video/
    ... laserdisc video/audio files referenced by SAe.txt ...
```

Also accepted:

```
SAe/
  SAe.txt
  sae.singe
  roms/
    ace.zip
    # or extracted ace/ or SAe/ ROM folder
  Video/
    ...
```

Notes:

- The launcher accepts `SAe.txt`, `sae.txt`, or `ace.txt` in the selected folder or nested `SAe/` folder.
- The launcher accepts `SAe.singe` or `sae.singe` in `SAe/`, the selected root, or `spaceace/`.
- ROM detection accepts `spaceace.zip`, `ace.zip`, `sae.zip`, or extracted `ace/` / `SAe/` ROM folders.
- If this pack does not include its own `Framework/`, the app can reuse a sibling `Framework/` from another Singe game folder such as `DL2e/`.

#### `app-dle-*.apk`

Select the `DLe` folder.

```
DLe/
  DLe.txt
  DLe.singe
  Framework/
    globals.singe
    framework.singe
    main.singe
    hscore.singe
    service.singe
    toolbox.singe
    ... any other upstream framework files ...
  Fonts/
    ...
  Overlay/
    ...
  Script/
    ...
  Sounds/
    ...
  Video/
    ... laserdisc video/audio files referenced by DLe.txt ...
  roms/
    lair.zip
    # or extracted lair/
```

Notes:

- `DLe.singe` can be either `DLe/DLe.singe` or `DLe.singe` directly under the selected folder.
- `lair.zip` or extracted `lair/` is the expected ROM set for this APK.

#### `app-dl2e-*.apk`

Select the `DL2e` folder.

```
DL2e/
  DL2e.txt
  DL2e/
    DL2e.singe
  Framework/
    globals.singe
    framework.singe
    main.singe
    hscore.singe
    service.singe
    toolbox.singe
    ... any other upstream framework files ...
  Fonts/
    ...
  Overlay/
    ...
  Script/
    ...
  Sounds/
    ...
  Video/
    ... laserdisc video/audio files referenced by DL2e.txt ...
  roms/
    lair2.zip
    # or extracted lair2/
```

Notes:

- This flavor expects the nested script path `DL2e/DL2e.singe`.
- `Framework/` from this folder is also the preferred source when the app needs to seed internal Singe framework files.

#### `app-dlclassic-*.apk`

Select the `dragons_lair_classic` folder.

```
dragons_lair_classic/
  dragons_lair_classic.txt
  dragons_lair_classic.singe
  Structure/
    globals.singe
    framework.singe
    main.singe
    hscore.singe
    service.singe
    toolbox.singe
  Video/
    ... laserdisc video/audio files referenced by dragons_lair_classic.txt ...
  roms/
    lair.zip
    # or extracted lair/
```

Notes:

- The classic package is validated against the `Structure/` folder instead of a top-level `Framework/`.
- `dragons_lair_classic.txt` is the expected framefile name for this APK.

#### `app-hypseus-*.apk`

This is the unlocked launcher. It can run native Hypseus games or external Singe games, so the selected folder depends on the game.

Native Dragon's Lair / Space Ace style layout:

```
SomeGame/
  lair.txt           # or ace.txt, laireuro.txt, lair2.txt, tq.txt
  Video/
    ...
  roms/
    lair.zip         # or ace.zip, laireuro.zip, lair2.zip, tq.zip
```

Generic external Singe layout:

```
SomeSingeGame/
  SomeSingeGame.txt
  main.singe         # or amain.singe / <folder>.singe
  Framework/
    globals.singe
    framework.singe
    main.singe
    hscore.singe
    service.singe
    toolbox.singe
  Video/
    ...
  roms/
    <romset>.zip
```

Notes:

- The unlocked APK also auto-detects well-known layouts such as `DLe/`, `DL2e/`, `SAe/`, and `dragons_lair_classic/`.
- For native games, no `.singe` script is used.
- For Singe games, the selected folder must include a readable `.singe` script and a matching framefile.

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
