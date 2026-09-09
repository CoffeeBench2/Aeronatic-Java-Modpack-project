#!/usr/bin/env python3
r"""
Coffees Aero SMP -- CurseForge-format pack for the DISCORD channel (self-updating).

WHY THIS EXISTS
---------------
We already had two CurseForge-shaped zips and NEITHER of them updates itself:

  * `build_cf.py`        -> the STORE zip. Ships the `-cf` Core with the updater classes compiled
                            out entirely (CF policy forbids fetching files from outside the pack).
                            It literally cannot update; the CF app is the updater there.
  * `build_cf_import.py` -> the light import zip. Ships the FULL Core, but then writes
                            `manualUpdateOnly = true` into the client config, which makes
                            AeroTitleScreen.openUpdateTarget() route "you are outdated" to
                            StoreUpdateScreen -- i.e. "go re-download from CurseForge/Modrinth".

That second one was a REASONABLE decision at the time and it is worth writing down why, because the
same trap is waiting for anyone who just flips the flag back:

    The import zip is ~240 CurseForge *references*. The CF launcher downloads CurseForge's copy of
    each mod. Our packwiz index hashes MODRINTH's copy. Same mod version, different bytes. The
    updater hash-checks every local file against the index (InClientUpdater.java:275), so on a
    CF-reference install almost every jar looks "wrong" and the first update tries to pull several
    hundred files from GitHub raw -- every request cache-busted, so every request hits origin.
    GitHub answers 429 part-way through and the update fails SILENTLY.

So `manualUpdateOnly = true` was not the disease, it was a splint on the reference model.

THE FIX
-------
Drop the references. This build is fully self-contained: `files: []` in the manifest, every jar
bundled straight out of `overrides/` -- the exact bytes the packwiz index hashes. The instance the
player ends up with is byte-identical to the mrpack, so:

  * first launch: the updater finds 2 stale jars (GlitchCore + SereneSeasons, whose metafiles point
    at CurseForge's copy while overrides/ holds Modrinth's) and quietly fixes them. Not 240.
  * every later pack release: a normal small delta, exactly like a Modrinth/GitHub player gets.
  * the player is NEVER sent to a store page. That is the whole point of this channel.

The price is size -- this zip is the mrpack's ~585 MB, not the import zip's 34 MB. That is not a
regression to fix; bundling the index-matching bytes IS the mechanism that makes updating work.

NOT FOR UPLOAD TO CURSEFORGE. Bundling jars CF hosts is exactly what got us rejected on 2026-08-19
(see the `cf-bundling-rejection-rule` note). This zip is handed to players in Discord and imported
via CF app -> Create Custom Profile -> Import. Keep using `build_cf.py` + `cf_fingerprint.py` for
the store.

Run from anywhere: `py scripts/build_cf_discord.py`
"""
import glob
import hashlib
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OVERRIDES = os.path.join(ROOT, "overrides")
RELEASES = r"D:\MC Project\Releases"
CORE_CFG_REL = os.path.join("config", "coffeesaerosmp_core-client.toml")

# Mirrors build_mrpack.py exactly. This channel must ship the SAME content as the mrpack or the
# updater's first delta stops being small, which is the one thing this build exists to guarantee.
# `datapacks/` is world-scoped and is excluded there too -- keep them in lockstep.
INCLUDE_DIRS = {"config", "mods", "resourcepacks", "shaderpacks", ".analogaudio"}
INCLUDE_FILES = {"options.txt"}


def read_pack_toml():
    t = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
    ver = re.search(r'(?m)^version\s*=\s*"([^"]+)"', t)
    mc = re.search(r'(?m)^minecraft\s*=\s*"([^"]+)"', t)
    nf = re.search(r'(?m)^neoforge\s*=\s*"([^"]+)"', t)
    if not (ver and mc and nf):
        sys.exit("could not read version / minecraft / neoforge out of pack.toml")
    return ver.group(1), mc.group(1), nf.group(1)


def find_full_core():
    """The FULL Core out of overrides/mods -- never the `-cf` one, never a stray build/libs jar.

    Taking it from overrides/ (rather than src/AeroCore/build/libs, which is what
    build_cf_import.py does) matters: overrides/ holds the bytes the packwiz index hashes. A
    fresher dev build from build/libs would hash-mismatch and the updater would re-download the
    Core on the player's first launch.
    """
    hits = [h for h in glob.glob(os.path.join(OVERRIDES, "mods", "CoffeesAeroCore-*.jar"))
            if not h.endswith("-cf.jar")]
    if len(hits) != 1:
        sys.exit("expected exactly ONE full CoffeesAeroCore jar in overrides/mods, found %d: %s"
                 % (len(hits), [os.path.basename(h) for h in hits]))
    return hits[0]


# The classes that decide, at RUNTIME, whether this install can update itself.
#
# Counting `update/**` is NOT the right test, which is easy to get wrong: UpdaterBridge decides
# `PRESENT` by reflecting on `version.VersionCheck` (startAsync/isOutdated/latestVersion/downloadUrl)
# and resolves the screen separately via `screen.UpdateScreen`. A jar carrying the whole `update/**`
# package but missing either of those initialises the bridge to PRESENT=false -- or returns null
# from openUpdateScreen -- and AeroTitleScreen.openUpdateTarget() then falls back to
# StoreUpdateScreen. That is the "go re-download from CurseForge/Modrinth" prompt this whole channel
# exists to avoid, and it would appear with `manualUpdateOnly = false` set and no error anywhere.
UPDATER_CLASSES = [
    "com/coffeesaerosmp/core/UpdaterBridge.class",
    "com/coffeesaerosmp/core/version/VersionCheck.class",   # decides UpdaterBridge.PRESENT
    "com/coffeesaerosmp/core/screen/UpdateScreen.class",    # openUpdateScreen() returns null without it
    "com/coffeesaerosmp/core/screen/UpdatingScreen.class",
    "com/coffeesaerosmp/core/update/InClientUpdater.class",
    "com/coffeesaerosmp/core/update/Applier.class",
]


def core_missing_updater(jar):
    """Which runtime-critical updater classes this Core jar lacks ([] means it can self-update).

    A version string cannot tell the builds apart -- `-PnoUpdater` produces the SAME version with
    the classes simply absent. Ask the jar what is inside it.
    """
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
    return [c for c in UPDATER_CLASSES if c not in names]


def core_has_updater(jar):
    """Count of updater classes present, for reporting. Use core_missing_updater() to gate."""
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
    return sum(1 for c in UPDATER_CLASSES if c in names)


def patch_core_config(text, version):
    """Stamp the client config for a SELF-UPDATING install.

    Three things must all be true or the player still gets sent to a store page:
      packVersion      -- matches live, so nothing is fetched on first launch
      manualUpdateOnly -- FALSE, so openUpdateTarget() opens the in-client updater
      packTomlUrl      -- present, or the Update button has nowhere to point
    """
    def upsert(t, key, value):
        line = '%s = %s' % (key, value)
        if re.search(r'(?m)^%s\s*=' % re.escape(key), t):
            return re.sub(r'(?m)^%s\s*=.*$' % re.escape(key), line, t)
        return t.rstrip("\n") + "\n" + line + "\n"

    text = upsert(text, "packVersion", '"%s"' % version)
    # Explicit rather than relying on the `false` default in AeroConfig: this file is the record of
    # what this channel is FOR, and a silent default is what made the import zip's behaviour so hard
    # to see from the outside.
    text = upsert(text, "manualUpdateOnly", "false")
    text = upsert(text, "packTomlUrl",
                  '"https://raw.githubusercontent.com/CoffeeBench2/'
                  'Aeronatic-Java-Modpack-project/main/pack.toml"')
    return text


def main():
    version, mc_version, neoforge = read_pack_toml()
    out = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CF-DISCORD.zip" % version)
    os.makedirs(RELEASES, exist_ok=True)

    core = find_full_core()
    missing = core_missing_updater(core)
    if missing:
        sys.exit("REFUSING TO SHIP: %s cannot self-update -- missing %s.\n"
                 "A jar with no updater at all is the `-cf` store build; a jar missing only "
                 "VersionCheck or UpdateScreen is worse, because it silently falls back to the "
                 "store prompt with no error."
                 % (os.path.basename(core), ", ".join(c.rsplit("/", 1)[-1] for c in missing)))
    n_updater = core_has_updater(core)

    manifest = {
        "minecraft": {
            "version": mc_version,
            "modLoaders": [{"id": "neoforge-%s" % neoforge, "primary": True}],
        },
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": "Coffees Aero SMP",
        "version": version,
        "author": "MrCoffeeBench",
        # Empty ON PURPOSE. Every reference here would be CurseForge's bytes instead of the index's,
        # and that is what breaks updating. See the module docstring.
        "files": [],
        "overrides": "overrides",
    }

    print("pack     : %s (MC %s / NeoForge %s)" % (version, mc_version, neoforge))
    print("core     : %s  (%d/%d runtime updater classes present)"
          % (os.path.basename(core), n_updater, len(UPDATER_CLASSES)))
    print()

    staged_cfg = None
    count = 0
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))

        for top in sorted(INCLUDE_DIRS):
            base = os.path.join(OVERRIDES, top)
            if not os.path.isdir(base):
                continue
            for dirpath, _dirs, files in os.walk(base):
                for fn in sorted(files):
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, OVERRIDES).replace("\\", "/")
                    if rel.replace("/", os.sep) == CORE_CFG_REL:
                        staged_cfg = patch_core_config(
                            open(full, encoding="utf-8").read(), version)
                        z.writestr("overrides/" + rel, staged_cfg)
                    else:
                        with open(full, "rb") as fh:
                            z.writestr("overrides/" + rel, fh.read())
                    count += 1

        for fn in sorted(INCLUDE_FILES):
            full = os.path.join(OVERRIDES, fn)
            if os.path.isfile(full):
                with open(full, "rb") as fh:
                    z.writestr("overrides/" + fn, fh.read())
                count += 1

        # CF app shows this in the profile. Cosmetic, but a profile with no modlist looks broken.
        jars = sorted(f for f in os.listdir(os.path.join(OVERRIDES, "mods"))
                      if f.endswith(".jar"))
        z.writestr("modlist.html",
                   "<ul>\n%s\n</ul>\n" % "\n".join("<li>%s</li>" % j for j in jars))

    # ---- gates: prove the shipped zip is actually self-updating -------------------
    problems = []
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
        m = json.loads(z.read("manifest.json"))
        if m["files"]:
            problems.append("manifest has %d CF reference(s); must be 0" % len(m["files"]))

        cfg_name = "overrides/" + CORE_CFG_REL.replace("\\", "/")
        if cfg_name not in names:
            problems.append("client config missing from the zip -- nothing configures the updater")
        else:
            cfg = z.read(cfg_name).decode("utf-8")
            if not re.search(r'(?m)^manualUpdateOnly\s*=\s*false\s*$', cfg):
                problems.append("manualUpdateOnly is not false -- players get sent to a store page")
            if not re.search(r'(?m)^packVersion\s*=\s*"%s"\s*$' % re.escape(version), cfg):
                problems.append("packVersion not stamped %s" % version)
            if not re.search(r'(?m)^packTomlUrl\s*=\s*"http', cfg):
                problems.append("packTomlUrl missing -- the Update button has nowhere to point")

        zipped_jars = {n.rsplit("/", 1)[-1] for n in names
                       if n.startswith("overrides/mods/") and n.endswith(".jar")}
        disk_jars = {f for f in os.listdir(os.path.join(OVERRIDES, "mods"))
                     if f.endswith(".jar")}
        if zipped_jars != disk_jars:
            problems.append("bundled mods differ from overrides/mods: missing %s"
                            % sorted(disk_jars - zipped_jars)[:5])
        if not any(n.endswith("/" + os.path.basename(core)) for n in names):
            problems.append("the full Core is not in the zip")

    print("files bundled       : %d" % count)
    print("mods bundled        : %d" % len(zipped_jars))
    print("manifest references : %d (self-contained)" % len(m["files"]))
    print("output              : %s" % out)
    print("size                : %.1f MB" % (os.path.getsize(out) / 1024 / 1024))
    print()

    if problems:
        os.remove(out)
        sys.exit("REFUSING TO SHIP (output deleted):\n  - " + "\n  - ".join(problems))

    print("gates passed:")
    print("  manifest references     0  (CF downloads nothing; index bytes preserved)")
    print("  full Core               %s" % os.path.basename(core))
    print("  manualUpdateOnly        false  (in-client updater handles updates)")
    print("  packVersion             %s  (matches live -- no first-launch delta)" % version)
    print()
    print("Hand this to players in Discord: CF app -> Create Custom Profile -> Import -> this zip.")
    print("They update in-game from then on. Do NOT upload it to CurseForge (bundled jars).")


if __name__ == "__main__":
    main()
