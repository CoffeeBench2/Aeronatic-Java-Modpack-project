#!/usr/bin/env python3
r"""
Coffees Aero SMP -- SLIM CurseForge-format pack for the DISCORD channel (still self-updating).

`build_cf_discord.py` bundles everything (562 MB) because bundling the bytes our packwiz index
hashes is what lets the in-client updater work. This build gets the size down WITHOUT giving that up.

THE CONSTRAINT THAT SHAPES THIS FILE
------------------------------------
A CurseForge manifest can only reference CurseForge files -- there is no URL field, unlike a slim
mrpack (`build_slim_mrpack.py`) which can point at cdn.modrinth.com. So a referenced mod arrives as
CURSEFORGE's bytes. If those differ from the bytes our index hashes, `InClientUpdater` sees the jar
as stale and re-downloads it. Reference the wrong things and the first update becomes a
several-hundred-file pull from GitHub raw, which 429s part-way and fails silently. That is exactly
how the old `build_cf_import.py` ended up with `manualUpdateOnly = true`.

So the rule here is narrow and testable:

    Reference a file ONLY when CurseForge's copy is BYTE-IDENTICAL to ours AND CF stores it
    under the SAME FILENAME. Otherwise bundle it.

The filename half is not pedantry. CF serves a file under the name its author uploaded, so a
referenced mod can land as `Terralith_1.21.x_v2.6.2.jar` while our index expects
`Terralith_1.21.1_v2.6.2_Neoforge.jar`. The updater then fetches a second copy under the expected
name and the instance has two jars declaring one modId -- which stops the client from starting, and
a client that cannot start never runs the StaleMods sweep that would clean it up.

Byte identity is settled from CF's own metadata, for free: `/v1/mods/files` returns each file's
sha1, so nothing has to be downloaded to prove it. A CF *fingerprint* match alone is NOT proof --
the fingerprint is murmur2 over whitespace-stripped bytes (9/10/13/32), so two files differing only
in whitespace collide. Measured on this pack the two agree perfectly (233 fingerprint matches, 232
byte-identical, 1 merely unavailable), but the sha1 check is what makes that a fact rather than a
hope, and it costs one extra API call.

MEASURED ON 1.10.12: 257 local files -> 226 referenced, 31 bundled (~78 MB zip vs the fat build's
562 MB), and the updater still sees a clean install. Of the 31: 25 have no byte-identical copy on
CF, 5 are byte-identical but renamed by CF, 1 (GlitchCore) is force-bundled as boot-critical.

The first-launch delta is the SAME 2 files as the fat build (GlitchCore + SereneSeasons, whose
metafiles record the upstream hash while overrides/ ships CF's bytes). Referencing them changes
nothing -- the player gets CF's bytes either way.

NOT FOR UPLOAD TO CURSEFORGE. The ~25 bundled jars include ones CF hosts under a restricted licence,
which is a moderation rejection (see the `cf-bundling-rejection-rule` note). Discord only.

Run: `py scripts/build_cf_discord_slim.py`
"""
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cf_fingerprint import cf_fingerprint
# Shared with the fat build so the two channels cannot drift in what they ship or how they stamp
# the client config. If you change one, you change both.
from build_cf_discord import (INCLUDE_DIRS, INCLUDE_FILES, CORE_CFG_REL,
                              read_pack_toml, find_full_core, core_has_updater,
                              core_missing_updater, UPDATER_CLASSES, patch_core_config)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OVERRIDES = os.path.join(ROOT, "overrides")
RELEASES = r"D:\MC Project\Releases"
KEY_FILE = os.path.join(ROOT, ".cf-key")

# Never referenced, however byte-identical CF's copy is.
#
# The 1.9.4.1 import zip shipped with its whole boot chain converted to references and nothing
# complained -- the build just printed a smaller size. A profile missing its language provider or
# Fabric-compat layer cannot reach the title screen, and the in-client updater only runs AFTER the
# title screen, so such a profile can never repair itself. Bundling these is cheap insurance
# (~22 MB) against an unbootable instance.
#
# As it happens all of them except GlitchCore fail the byte-identity test anyway and would stay
# bundled regardless -- but that is a coincidence of what the authors uploaded where, not a
# guarantee, and it is exactly the kind of coincidence that quietly stopped being true last time.
MUST_BUNDLE = [
    "Analog-Audio-",              # not a CF project at all; CF will not carry it in any form
    "connector-",                 # Fabric-compat layer; mods classload through it
    "forgified-fabric-api-",      # matched pair with Connector -- never ship one without the other
    "kotlinforforge-",            # language provider
    "kubejs-neoforge-",           # runs scripts during load
    "rhino-",                     # KubeJS's script engine; useless apart
    "GlitchCore-neoforge-",       # hard dep of the worldgen stack
]


def post(url, body, key):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"x-api-key": key, "Content-Type": "application/json",
                                          "Accept": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=90))


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def resolve_references(paths, key):
    """{path: (projectID, fileID)} for files CF hosts BYTE-IDENTICALLY and can serve.

    Three gates, all required:
      fingerprint match  -- CF knows the file
      isAvailable        -- CF will actually serve it (an unavailable fileID makes the CF app fail
                            the install, and makes a store manifest auto-reject)
      sha1 equality      -- CF's bytes ARE our bytes, so the updater will not see it as stale
    """
    fp_of = {p: cf_fingerprint(p) for p in paths}
    fps = sorted({v for v in fp_of.values()})

    matched = {}
    for i in range(0, len(fps), 100):
        d = post("https://api.curseforge.com/v1/fingerprints", {"fingerprints": fps[i:i + 100]}, key)
        for em in d.get("data", {}).get("exactMatches", []):
            fl = em.get("file", {})
            if fl.get("fileFingerprint") and fl.get("id") and fl.get("modId"):
                matched[fl["fileFingerprint"]] = fl

    fids = [f["id"] for f in matched.values()]
    meta = {}
    for i in range(0, len(fids), 200):
        d = post("https://api.curseforge.com/v1/mods/files", {"fileIds": fids[i:i + 200]}, key)
        for f in d.get("data", []):
            meta[f["id"]] = f

    refs, rejected = {}, []
    for p, fp in fp_of.items():
        fl = matched.get(fp)
        if not fl:
            # NOT "not on CF". A fingerprint miss only proves CF has no copy with OUR bytes.
            # Most of these ARE CurseForge projects whose CF build differs from the Modrinth
            # build we ship -- FallingTree, Connector, FFAPI, KotlinForForge, KubeJS, Rhino,
            # sound-physics, Sophisticated*, Vista, create_connected, aeroworks are all in
            # cf_fingerprint.MANUAL_REFS for exactly that reason. Saying "not on CF" here would
            # be the same false-safe that cost a moderation round in 2026-08: absence of a match
            # is a WEAKER claim than absence of the project, and conflating them is what makes a
            # bundled restricted jar look fine.
            rejected.append((os.path.basename(p), "no byte-identical copy on CF"))
            continue
        m = meta.get(fl["id"], fl)
        if not m.get("isAvailable", True):
            rejected.append((os.path.basename(p), "CF file not downloadable"))
            continue
        cf_sha1 = next((h["value"].lower() for h in m.get("hashes", []) if h.get("algo") == 1), None)
        if cf_sha1 is None:
            rejected.append((os.path.basename(p), "CF exposes no sha1 -- cannot prove identity"))
        elif cf_sha1 != sha1_of(p):
            rejected.append((os.path.basename(p), "CF bytes differ"))
        elif m.get("fileName") != os.path.basename(p):
            # 🔴 IDENTICAL BYTES ARE NOT ENOUGH -- THE NAME HAS TO MATCH TOO.
            #
            # CurseForge stores a file under the name its author uploaded, which routinely differs
            # from the name in our index for the very same bytes:
            #   Terralith_1.21.1_v2.6.2_Neoforge.jar  ->  Terralith_1.21.x_v2.6.2.jar
            #   vistaallthetapes-1.2.0-neoforge-...   ->  vistaallthetapes - 1.2.0 - neoforge - ...
            #
            # Referencing one of those installs the mod under CF's name. The packwiz index still
            # expects OUR name, so on the next update the updater downloads a second copy under the
            # expected name and the instance ends up with two jars declaring one modId -- which does
            # not merely warn, it stops the client from starting. And per StaleMods' docstring that
            # is the unrecoverable shape: a client that cannot start never runs the cleanup that
            # would fix it (LongerChatHistory / More Armor Trims / Simulated Coasters, 2026-09-03).
            #
            # 5 files on 1.10.12. Bundling them costs a few MB and removes the failure entirely.
            rejected.append((os.path.basename(p),
                             "CF renames it to %s -- would collide on update" % m["fileName"]))
        else:
            refs[p] = (m["modId"], m["id"])
    return refs, rejected


def main():
    if not os.path.isfile(KEY_FILE):
        sys.exit(".cf-key not found -- needed to resolve CurseForge references")
    key = open(KEY_FILE).read().strip()

    version, mc_version, neoforge = read_pack_toml()
    out = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CF-DISCORD-SLIM.zip" % version)
    os.makedirs(RELEASES, exist_ok=True)

    core = find_full_core()
    missing = core_missing_updater(core)
    if missing:
        sys.exit("REFUSING TO SHIP: %s cannot self-update -- missing %s. A jar missing only "
                 "VersionCheck or UpdateScreen still falls back to the store prompt, silently."
                 % (os.path.basename(core), ", ".join(c.rsplit("/", 1)[-1] for c in missing)))
    n_updater = core_has_updater(core)

    # Candidates for referencing: the redistributable content dirs only. config/, options.txt and
    # .analogaudio are always bundled -- they are not CF projects.
    candidates = []
    for sub in ("mods", "resourcepacks", "shaderpacks"):
        d = os.path.join(OVERRIDES, sub)
        if os.path.isdir(d):
            candidates += [os.path.join(d, n) for n in sorted(os.listdir(d))
                           if n.lower().endswith((".jar", ".zip"))]

    print("pack      : %s (MC %s / NeoForge %s)" % (version, mc_version, neoforge))
    print("core      : %s  (%d/%d runtime updater classes present)"
          % (os.path.basename(core), n_updater, len(UPDATER_CLASSES)))
    print("resolving %d files against CurseForge ..." % len(candidates))
    refs, rejected = resolve_references(candidates, key)

    # Force-bundle the boot chain regardless of what CF hosts.
    forced = []
    for p in list(refs):
        base = os.path.basename(p)
        if any(base.startswith(pre) for pre in MUST_BUNDLE):
            del refs[p]
            forced.append(base)
    # The full Core must never be referenced -- CF hosts only the `-cf` (no-updater) build.
    if core in refs:
        del refs[core]
        forced.append(os.path.basename(core))

    bundled_paths = [p for p in candidates if p not in refs]
    print()
    print("referenced (byte-identical on CF) : %d" % len(refs))
    print("bundled                           : %d" % len(bundled_paths))
    if forced:
        print("force-bundled (boot chain / Core) : %s" % ", ".join(sorted(forced)))

    manifest = {
        "minecraft": {"version": mc_version,
                      "modLoaders": [{"id": "neoforge-%s" % neoforge, "primary": True}]},
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": "Coffees Aero SMP",
        "version": version,
        "author": "MrCoffeeBench",
        "files": [{"projectID": pid, "fileID": fid, "required": True}
                  for (pid, fid) in sorted(set(refs.values()))],
        "overrides": "overrides",
    }

    skip = set(refs)
    if os.path.exists(out):
        os.remove(out)
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for top in sorted(INCLUDE_DIRS):
            base = os.path.join(OVERRIDES, top)
            if not os.path.isdir(base):
                continue
            for dirpath, _dirs, files in os.walk(base):
                for fn in sorted(files):
                    full = os.path.join(dirpath, fn)
                    if full in skip:
                        continue                     # CurseForge downloads this one
                    rel = os.path.relpath(full, OVERRIDES).replace("\\", "/")
                    if rel.replace("/", os.sep) == CORE_CFG_REL:
                        z.writestr("overrides/" + rel,
                                   patch_core_config(open(full, encoding="utf-8").read(), version))
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
        jars = sorted(f for f in os.listdir(os.path.join(OVERRIDES, "mods")) if f.endswith(".jar"))
        z.writestr("modlist.html", "<ul>\n%s\n</ul>\n" % "\n".join("<li>%s</li>" % j for j in jars))

    # ---- gates ------------------------------------------------------------------
    problems = []
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
        m = json.loads(z.read("manifest.json"))

        cfg_name = "overrides/" + CORE_CFG_REL.replace("\\", "/")
        if cfg_name not in names:
            problems.append("client config missing -- nothing configures the updater")
        else:
            cfg = z.read(cfg_name).decode("utf-8")
            for pat, msg in (
                (r'(?m)^manualUpdateOnly\s*=\s*false\s*$',
                 "manualUpdateOnly is not false -- players get sent to a store page"),
                (r'(?m)^packVersion\s*=\s*"%s"\s*$' % version.replace(".", r"\."),
                 "packVersion not stamped %s" % version),
                (r'(?m)^packTomlUrl\s*=\s*"http',
                 "packTomlUrl missing -- the Update button has nowhere to point"),
            ):
                import re as _re
                if not _re.search(pat, cfg):
                    problems.append(msg)

        # COVERAGE: every local mod arrives exactly once -- bundled XOR referenced.
        zipped = {n.rsplit("/", 1)[-1] for n in names
                  if n.startswith("overrides/mods/") and n.endswith(".jar")}
        expect_bundled = {os.path.basename(p) for p in bundled_paths
                          if os.path.basename(p).endswith(".jar")}
        if zipped != expect_bundled:
            problems.append("bundled mods != planned: missing %s / extra %s"
                            % (sorted(expect_bundled - zipped)[:5], sorted(zipped - expect_bundled)[:5]))
        if zipped & {os.path.basename(p) for p in refs}:
            problems.append("a mod is BOTH bundled and referenced -- the instance would get it twice")
        if len(m["files"]) != len(set((f["projectID"], f["fileID"]) for f in m["files"])):
            problems.append("duplicate references in the manifest")

        # The boot chain and the full Core must be in the zip, not on CF's side.
        for pre in MUST_BUNDLE:
            if not any(b.startswith(pre) for b in zipped):
                problems.append("boot-critical jar missing from the bundle: %s*" % pre)
        if not any(n.endswith("/" + os.path.basename(core)) for n in names):
            problems.append("the full Core is not in the zip")

    print()
    print("files bundled       : %d" % count)
    print("mods bundled        : %d" % len(zipped))
    print("manifest references : %d" % len(m["files"]))
    print("output              : %s" % out)
    print("size                : %.1f MB" % (os.path.getsize(out) / 1024 / 1024))

    if problems:
        os.remove(out)
        sys.exit("\nREFUSING TO SHIP (output deleted):\n  - " + "\n  - ".join(problems))

    print()
    print("gates passed:")
    print("  every reference byte-identical to our index bytes (sha1-verified, isAvailable)")
    print("  boot chain + full Core bundled, never referenced")
    print("  manualUpdateOnly=false, packVersion=%s, packTomlUrl set" % version)
    print("  bundled XOR referenced -- nothing missing, nothing installed twice")
    print()
    print("Not sha1-referenceable, so bundled (%d):" % len(rejected))
    for n, why in sorted(rejected)[:30]:
        print("   %-52s %s" % (n[:52], why))
    print()
    print("  NB 'no byte-identical copy on CF' does NOT mean the mod is absent from CurseForge --")
    print("  many of the above are CF projects whose CF build differs from the Modrinth build we")
    print("  ship. That is fine here (Discord, unmoderated) and a rejection on the store channel.")
    print()
    print("Discord -> CF app -> Create Custom Profile -> Import. Updates in-game from then on.")
    print("Do NOT upload to CurseForge (bundled jars = moderation rejection).")


if __name__ == "__main__":
    main()
