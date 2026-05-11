# Singe Game File Layout and Launcher Model

This document defines the expected file layout for each Singe game profile and explains why separate Android launchers (application IDs) are used.

## Required Runtime Layout

At runtime, each game profile should have its own directory under the app-internal patched Singe root:

```
files/
  patched_singe/
    <GameFolder>/
      <main_script>.singe
```

Example for Space Ace:

```
files/
  patched_singe/
    SAe/
      sae.singe
```

## Script Include Expectations

Each main game script is expected to load framework globals from the Singe runtime root:

```
dofile(BASEDIR .. "/Framework/globals.singe")
```

So the runtime also needs:

```
files/
  patched_singe/
    singe/
      Framework/
        globals.singe
```

## Per-Game Launcher Requirements

Each launcher flavor should map to one game profile and one package ID.

Current Android flavors:

- `hypseus` (multi-game): application ID `org.hypseus.singe`
- `spaceace` (single-game): application ID `org.hypseus.singe.spaceace`

## Why Separate Launchers Are Used

Separate launchers are intentional for stability on low-memory devices:

- Different package IDs isolate each game into a separate app process and separate app data sandbox.
- Native memory (SDL surfaces, decoder buffers, audio buffers, texture caches) can remain resident after heavy gameplay and increase pressure when switching titles.
- A per-game launcher allows Android to treat each title as an independent task/process, reducing cross-game memory fragmentation and stale state carryover.
- Single-game lock mode (`LOCK_GAME_SELECTION=true`) prevents loading another profile from the same process lifetime.

Practical outcome: once one heavy title has run, launching another from the same process can fail or degrade due to memory pressure; separate launchers avoid that by design.

## Validation Checklist Per Game

For each new Singe game flavor:

1. Confirm a unique `applicationId` in `android-app/app/build.gradle.kts`.
2. Confirm correct `GAME_PROFILE` and optional lock settings in flavor `buildConfigField` values.
3. Confirm runtime script path exists under `files/patched_singe/<GameFolder>/`.
4. Confirm main script can resolve `singe/Framework/globals.singe`.
5. Smoke test cold launch, gameplay, return to launcher, and relaunch.
