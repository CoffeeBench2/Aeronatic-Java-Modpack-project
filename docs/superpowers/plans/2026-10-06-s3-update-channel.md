# S3 Self-Updating Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S3 Modrinth instances (3.0.0+) update from an `s3/` packwiz channel on `main`; S2 instances never see S3 content and, at launch, are pointed at Modrinth.

**Architecture:** A generator script turns the S3 Modrinth App profile into a packwiz pack under `s3/` (Modrinth-hosted files as Modrinth metafiles, everything else as GitHub-release-hosted metafiles on a PRE-release tag so `/releases/latest` never moves). A second script builds the Modrinth upload mrpack from the same inputs. A third, run only at S3 launch, retires the S2 root channel (1.11.5 config flip + version.json → 3.0.0). No Core code change: `InClientUpdater` already resolves files relative to `packTomlUrl`'s directory.

**Tech Stack:** Python 3.12 scripts, packwiz CLI (`C:\tools\packwiz\packwiz`), `gh` CLI, Gradle (AeroCore), Modrinth v2 API.

**Spec:** `docs/superpowers/specs/2026-10-06-s3-channel-live-counts-mail-handbook-design.md` §1

---

## File structure

| File | Responsibility |
|---|---|
| `scripts/build_s3_channel.py` (new) | Profile → `s3/` packwiz tree; uploads non-Modrinth jars to release `s3-v<ver>` (pre-release); runs `packwiz refresh` in `s3/`; all guards |
| `scripts/s3-overrides/config/coffeesaerosmp_core-client.toml` (new) | S3 Modrinth/updater Core config (s3 URLs, packVersion, manualMods = []) |
| `scripts/s3-overrides/config/coffees_aero_announcements.json` (copied) | S3 news shipped with the pack |
| `scripts/build_s3_mrpack.py` (new) | Modrinth upload mrpack: reference Modrinth files, bundle our/permitted jars, DROP FTB, stamp bootstrap packVersion |
| `scripts/retire_s2.py` (new) | Launch-day only: root channel → 1.11.5 with `manualUpdateOnly = true`; `version.json` → 3.0.0 + Modrinth URL |
| `.packwizignore` (modify) | add `s3/` so the root (S2) index can never include S3 |
| `s3/**` (generated, committed) | the S3 channel |

## Task 1: Full Core 1.3.54 jar
- [ ] `cd src/AeroCore && ./gradlew build` (no `-PnoUpdater`) → `build/libs/CoffeesAeroCore-1.3.54.jar`.
- [ ] Verify the updater is present: `unzip -l` lists `com/coffeesaerosmp/core/version/VersionCheck.class` and `com/coffeesaerosmp/core/update/InClientUpdater.class`. Expected: both present.

## Task 2: S3 Core config
- [ ] Create `scripts/s3-overrides/config/coffeesaerosmp_core-client.toml` with: `packVersion = "3.0.0"`,
  `versionCheckUrl = ".../main/s3/version.json"`, `packTomlUrl = ".../main/s3/pack.toml"`, `manualUpdateOnly = false`,
  `newsUrl = ""`, `manualMods = []` (SSRD ships from Modrinth in this channel), `serverIp = "managed"`, `adminUsername`.
- [ ] Copy the announcements JSON from `scripts/s3-cf-overrides/config/`.

## Task 3: Root ignore
- [ ] Add `s3/` to `.packwizignore`. Run `packwiz refresh` at root; `git diff --stat index.toml pack.toml` must be EMPTY (S2 untouched).

## Task 4: `build_s3_channel.py`
Inputs: profile (`mods`, `resourcepacks`, `shaderpacks`, `config`, `defaultconfigs`, `options.txt`), full Core jar, `scripts/s3-overrides/`.
- [ ] Modrinth `POST /v2/version_files` (sha1) → for each hit write `s3/<dir>/<slug>.pw.toml` (`name`, `filename`, `side = "both"`, `[download] url + hash-format sha512 + hash`, `[update.modrinth] mod-id + version`).
- [ ] Each miss + the Core → asset on release `s3-v<ver>` (create with `gh release create --prerelease` if absent, `gh release upload --clobber`), metafile with sha256 + `releases/download/s3-v<ver>/<file>` URL; verify each asset URL answers 200 (HEAD, follow redirects) or exit 1.
- [ ] Overrides: copy profile `config/`, `defaultconfigs/`, `options.txt` to `s3/overrides/` EXCLUDING `config/resourceful-config-web.json`, `config/ias.json`; then the S3 Core config + announcements on top. Strip any old Core jar from the profile (S3 profile has none today; guard anyway).
- [ ] Write `s3/pack.toml` (name "Coffees Aero SMP Season 3", version, minecraft 1.21.1, neoforge from profile = 21.1.251), `s3/.packwizignore` (nothing but itself), `s3/version.json` (`version`, `url` = Modrinth project, `neoforge`, `minecraft`, `notes`).
- [ ] `packwiz refresh` with cwd `s3/`.
- [ ] Guards (exit 1): root `.packwizignore` lacks `s3/`; S3 config URLs not containing `/main/s3/`; Core jar missing `VersionCheck.class`; `options.txt` has a BOM; `iris.properties` `enableShaders` not false; `s3/pack.toml [index] hash != sha256(s3/index.toml)`; file count in index != profile files + overrides.
- [ ] Run it: `py scripts/build_s3_channel.py --version 3.0.0`. Expected: `s3/` written, guards pass.

## Task 5: `build_s3_mrpack.py`
- [ ] Read `s3/` metafiles: Modrinth-hosted → `files[]` (`path`, `hashes` sha1+sha512, `downloads`, `fileSize`, `env`).
- [ ] Release-hosted: FTB prefixes → DROP (Modrinth "no permission"); Core, aerowarptics, reforgedplaymod → bundle in `overrides/mods/` (same as the published S2 Modrinth builds).
- [ ] Overrides = `s3/overrides/**`, with `packVersion` re-stamped one notch below (`2.99.99`) so the first launch runs the updater and backfills FTB.
- [ ] Output `D:\MC Project\Releases\CoffeesAeroSMP-S3-3.0.0-MODRINTH.mrpack`; print counts.

## Task 6: `retire_s2.py` (built now, RUN AT S3 LAUNCH ONLY)
- [ ] Set root `overrides/config/coffeesaerosmp_core-client.toml` `manualUpdateOnly = true`, `packVersion = "1.11.5"`; `pack.toml` version 1.11.5; `scripts/build_mrpack.py` VERSION 1.11.5; `version.json` → `"version": "3.0.0"`, `url` = Modrinth project, notes = S3 message; `packwiz refresh`; print the push commands. Core metafile URL stays `v1.11.4` (that asset still exists; the Core does not change).
- [ ] `--dry-run` prints the diff without writing. Run the dry run only.

## Task 7: Verify + commit
- [ ] Every URL in `s3/**/*.pw.toml` answers 200. Root `index.toml` unchanged.
- [ ] Commit scripts + `s3/` + `.packwizignore`. Do NOT push `main` without the owner (public repo, and pushing makes the channel live).
- [ ] Owner test (needs a game client): import the mrpack in Modrinth App → first launch updates to 3.0.0 and pulls FTB; bump a dummy file in `s3/` → updater sees it.
