#!/usr/bin/env python3
r"""
Coffees Aero SMP — CurseForge build (fully self-contained; no Git dependency, no in-client updater).

The Modrinth / GitHub channel ships the FULL CoffeesAeroCore (with the in-client packwiz updater).
CurseForge forbids mods that fetch files from outside the pack, and on CF the CF app is the updater
anyway — so the CF pack must ship the `-cf` Core variant (built with `./gradlew jar -PnoUpdater`,
updater classes ABSENT) and must not reference the pack's GitHub repo at runtime.

`packwiz curseforge export` bundles self-hosted mods (Core is delivered via a metafile whose
[download] url is a GitHub release) into the zip's overrides/mods/ — so the export is already
self-contained, but it bundles the FULL Core fetched from GitHub. This script runs the export, then
post-processes the zip:
  1. replaces the bundled full Core jar with the local -cf jar (no updater, no GitHub fetch),
  2. strips any version.json the updater would have read,
  3. writes the result as CoffeesAeroSMP-<ver>-CURSEFORGE.zip.

Run from the project root (where pack.toml lives). Output goes to D:\MC Project\Releases\.
"""
import glob
import io
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES = r"D:\MC Project\Releases"
# The no-updater Core jar built by `gradlew jar -PnoUpdater` (staged copy is the source of truth).
CF_CORE = os.path.join(RELEASES, "cf-core-noupdater", "CoffeesAeroCore-1.3.13-cf.jar")


def pack_version():
    toml = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
    m = re.search(r'(?m)^version\s*=\s*"([^"]+)"', toml)
    return m.group(1) if m else "0.0.0"


def run_export():
    print("Running packwiz curseforge export ...")
    subprocess.run(["packwiz", "curseforge", "export"], cwd=ROOT, check=True)
    zips = sorted(glob.glob(os.path.join(ROOT, "*.zip")), key=os.path.getmtime)
    if not zips:
        sys.exit("ERROR: packwiz produced no .zip — is packwiz on PATH and pack.toml valid?")
    return zips[-1]


def rewrite_zip(src_zip, out_zip):
    if not os.path.isfile(CF_CORE):
        sys.exit(f"ERROR: CF Core jar not found: {CF_CORE}\n"
                 f"Build it first: cd src/AeroCore && ./gradlew jar -PnoUpdater")
    cf_core_bytes = open(CF_CORE, "rb").read()
    cf_core_name = os.path.basename(CF_CORE)

    swapped_core = False
    dropped_version_json = False
    with zipfile.ZipFile(src_zip) as zin, \
         zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            low = name.lower()
            # Drop the FULL Core jar bundled by packwiz (any CoffeesAeroCore*.jar under overrides/mods).
            if low.startswith("overrides/mods/") and "coffeesaerocore" in low and low.endswith(".jar"):
                swapped_core = True
                continue
            # Strip the updater's version manifest if it rode along — nothing on CF reads it.
            if os.path.basename(low) == "version.json":
                dropped_version_json = True
                continue
            # Strip Analog-Audio's pre-seeded lavaplayer runtime cache: analogplayer-*.jar shades
            # lavaplayer's YouTube source, which is on CF's class BLACKLIST (rejected 2026-07-20,
            # YoutubeAccessTokenTracker). The mod re-downloads its player runtime on first launch,
            # so CF installs lose only the pre-seed, not the feature.
            if low.startswith("overrides/.analogaudio/"):
                continue
            zout.writestr(item, zin.read(name))
        # Add the -cf Core (no updater) into overrides/mods.
        zout.writestr("overrides/mods/" + cf_core_name, cf_core_bytes)

    if not swapped_core:
        print("WARNING: no CoffeesAeroCore jar found in the export to replace — "
              "check the pack's Core metafile. The -cf jar was still added.")
    print(f"  Core -> {cf_core_name} (no in-client updater){' ; version.json stripped' if dropped_version_json else ''}")


def main():
    ver = pack_version()
    raw = run_export()
    out = os.path.join(RELEASES, f"CoffeesAeroSMP-{ver}-CURSEFORGE.zip")
    os.makedirs(RELEASES, exist_ok=True)
    rewrite_zip(raw, out)
    os.remove(raw)  # discard the raw packwiz export; keep only the finished CF zip
    print(f"\nDONE: {out}")
    print("This zip is fully self-contained (all mods bundled or CF-referenced), ships the")
    print("no-updater Core, and does NOT fetch anything from the pack's GitHub at runtime.")
    print("Upload it at https://www.curseforge.com/minecraft/modpacks (new file).")


if __name__ == "__main__":
    main()
