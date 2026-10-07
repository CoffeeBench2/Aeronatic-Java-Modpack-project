#!/usr/bin/env python3
r"""
Season 3 CurseForge-format zip for GitHub distribution (NOT uploaded to CurseForge).

    py scripts/build_cf_s3_github.py --version 3.0.1

Same idea as build_cf_discord.py for Season 2: FULLY SELF-CONTAINED, `files: []`, every jar bundled
from the S3 Modrinth App profile, so the bytes in the instance are exactly the bytes the s3/ packwiz
index hashes and the in-client updater can apply later releases as small deltas. CurseForge
*references* would install CurseForge's bytes instead and make nearly every jar look stale to the
updater (see memory cf-discord-selfupdating-channel). The FULL Core (with updater) ships, configured
for the s3/ channel.

Run build_s3_channel.py first: the Core config comes from s3/overrides and must carry this version.
Output: D:\MC Project\Releases\CoffeesAeroSMP-S3-<ver>-CURSEFORGE-GITHUB.zip
"""
import argparse, json, os, re, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE = r"D:\MC Project\S3-pack-source"  # see build_s3_channel.py
RELEASES = r"D:\MC Project\Releases"
CORE_JAR_DIR = os.path.join(ROOT, "src", "AeroCore", "build", "libs")
S3_OVERRIDES = os.path.join(ROOT, "s3", "overrides")
MC, LOADER = "1.21.1", "neoforge-21.1.251"
NAME, AUTHOR = "Coffee's AeroSMP Season 3", "MrCoffeeBench"
CONTENT_DIRS = ["mods", "resourcepacks", "shaderpacks"]
OVERRIDE_DIRS = ["config", "defaultconfigs"]
OVERRIDE_FILES = ["options.txt"]
# Per-install generated password / orphan of a mod not in the pack. servers.dat stays home: the
# Core's own Join button is the way in (gate), the profile's entry points at the raw backend.
OVERRIDE_EXCLUDE = {"config/resourceful-config-web.json", "config/ias.json"}
RENAME = {"reforgedplaymod-1.21.1-0.3 (1).jar": "reforgedplaymod-1.21.1-0.3.jar"}
UPDATER_CLASS = "com/coffeesaerosmp/core/version/VersionCheck.class"
MUST_HAVE = ("be_quiet_negotiator-", "CoffeesAeroSkins-")


def fail(out, msg):
    if out and os.path.exists(out):
        os.remove(out)
    sys.exit("ERROR: " + msg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--core", default="1.3.54")
    a = ap.parse_args()

    core = os.path.join(CORE_JAR_DIR, "CoffeesAeroCore-%s.jar" % a.core)
    if not os.path.isfile(core):
        fail(None, "full Core jar missing: " + core)
    if UPDATER_CLASS not in zipfile.ZipFile(core).namelist():
        fail(None, "Core has no updater classes (a -cf build?): " + core)

    cfg_path = os.path.join(S3_OVERRIDES, "config", "coffeesaerosmp_core-client.toml")
    cfg = open(cfg_path, encoding="utf-8").read()
    if not re.search(r'(?m)^packVersion\s*=\s*"%s"' % re.escape(a.version), cfg):
        fail(None, "s3 Core config is not packVersion %s -- run build_s3_channel.py first" % a.version)
    if "/s3/pack.toml" not in cfg or re.search(r"(?m)^manualUpdateOnly\s*=\s*true", cfg):
        fail(None, "s3 Core config does not point at the s3 channel with updates on")

    iris = os.path.join(PROFILE, "config", "iris.properties")
    if "enableShaders=false" not in open(iris, encoding="utf-8").read():
        fail(None, "shaders must ship OFF (config/iris.properties enableShaders=false)")
    if open(os.path.join(PROFILE, "options.txt"), "rb").read(3) == b"\xef\xbb\xbf":
        fail(None, "options.txt has a UTF-8 BOM (Minecraft would ignore the whole file)")

    out = os.path.join(RELEASES, "CoffeesAeroSMP-S3-%s-CURSEFORGE-GITHUB.zip" % a.version)
    jars = []
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for d in CONTENT_DIRS:
            src = os.path.join(PROFILE, d)
            for n in sorted(os.listdir(src)) if os.path.isdir(src) else []:
                p = os.path.join(src, n)
                if not os.path.isfile(p) or n.lower().endswith((".disabled", ".txt")):
                    continue
                if d == "mods" and n.startswith("CoffeesAeroCore-"):
                    continue                      # replaced by the full Core below
                name = RENAME.get(n, n)
                z.write(p, "overrides/%s/%s" % (d, name), compress_type=zipfile.ZIP_STORED)
                if d == "mods":
                    jars.append(name)
        z.write(core, "overrides/mods/" + os.path.basename(core), compress_type=zipfile.ZIP_STORED)
        jars.append(os.path.basename(core))

        cfg_rel = "config/coffeesaerosmp_core-client.toml"
        n_cfg = 0
        for d in OVERRIDE_DIRS:
            base = os.path.join(PROFILE, d)
            for dp, _, fs in os.walk(base):
                for f in fs:
                    rel = os.path.relpath(os.path.join(dp, f), PROFILE).replace("\\", "/")
                    if rel in OVERRIDE_EXCLUDE or rel == cfg_rel or rel.endswith(".bak"):
                        continue
                    z.write(os.path.join(dp, f), "overrides/" + rel)
                    n_cfg += 1
        for f in OVERRIDE_FILES:
            z.write(os.path.join(PROFILE, f), "overrides/" + f)
        # The channel's own files win over the profile's copies: Core config (s3 channel, this
        # version) + news.
        for dp, _, fs in os.walk(S3_OVERRIDES):
            for f in fs:
                rel = os.path.relpath(os.path.join(dp, f), S3_OVERRIDES).replace("\\", "/")
                z.write(os.path.join(dp, f), "overrides/" + rel)

        z.writestr("manifest.json", json.dumps({
            "minecraft": {"version": MC, "modLoaders": [{"id": LOADER, "primary": True}]},
            "manifestType": "minecraftModpack", "manifestVersion": 1,
            "name": NAME, "version": a.version, "author": AUTHOR,
            "files": [], "overrides": "overrides"}, indent=2))

    names = zipfile.ZipFile(out).namelist()
    if len(set(names)) != len(names):
        fail(out, "duplicate paths in the zip")
    for prefix in MUST_HAVE:
        if not any(j.startswith(prefix) for j in jars):
            fail(out, "required jar missing: " + prefix + "*")
    cores = [j for j in jars if j.startswith("CoffeesAeroCore-")]
    if cores != [os.path.basename(core)]:
        fail(out, "expected exactly the full Core, got %s" % cores)
    print("wrote %s  %.1f MB  (%d jars bundled, %d config files, 0 references)"
          % (out, os.path.getsize(out) / 1e6, len(jars), n_cfg))


if __name__ == "__main__":
    main()
