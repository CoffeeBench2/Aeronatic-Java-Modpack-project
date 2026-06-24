"""Write packwiz url-metadata for the 14 files not on Modrinth (our custom/hand-modified
jars + CurseForge-only mods/packs), pointing at the v1.3.0 GitHub Release assets with a
sha256 hash. Deterministic + exact-version. Pulls the real (URL-encoded) asset URLs from gh.

NOTE for public launch: the community CurseForge mods are self-hosted here as a closed-test
shortcut. To respect redistribution at public launch, switch those to `packwiz cf add`.
Run `packwiz refresh` afterwards.
"""
import os, hashlib, json, subprocess, re

ROOT = r"D:\MC Project\untitled"
REPO = "CoffeeBench2/Aeronatic-Java-Modpack-project"
TAG  = "v1.3.0"

TARGETS = {
    "mods": [
        "CameraOverhaul-v2.0.4-neoforge-clothfix.jar",
        "create_connected-1.1.16-mc1.21.1.jar",
        "extra_create_recipes_1.21.1_v1.jar",
        "integrated_api-1.7.4+1.21.1-neoforge.jar",
        "ftb-chunks-neoforge-2101.1.19.jar",
        "ftb-library-neoforge-2101.1.32.jar",
        "ftb-quests-neoforge-2101.1.27.jar",
        "ftb-teams-neoforge-2101.1.10.jar",
        "CoffeesAeroAuth-1.0.0.jar",
        "CoffeesAeroCore-1.0.0.jar",
    ],
    "resourcepacks": [
        "3d-mace.zip",
        "Actually 3D Stuff.zip",
        "Better Trident v2.zip",
        "FA+Details-v2.3.zip",
    ],
}

out = subprocess.run(
    ["gh", "release", "view", TAG, "--repo", REPO, "--json", "assets"],
    capture_output=True, text=True, cwd=ROOT, shell=True)
assets = {a["name"]: a["url"] for a in json.loads(out.stdout)["assets"]}

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()

def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", os.path.splitext(s)[0].lower()).strip("-")

written = 0
for cat, files in TARGETS.items():
    src  = os.path.join(ROOT, "overrides", cat)
    meta = os.path.join(ROOT, cat)
    for fn in files:
        p = os.path.join(src, fn)
        if not os.path.exists(p):
            print("MISSING local:", fn); continue
        asset_name = fn.replace(" ", ".")                 # GitHub renames spaces -> dots
        url = assets.get(asset_name) or assets.get(fn)
        if not url:
            print("NO RELEASE ASSET for:", fn); continue
        body = (
            f'name = "{os.path.splitext(fn)[0]}"\n'
            f'filename = "{fn}"\n'
            f'side = "both"\n\n'
            f'[download]\n'
            f'url = "{url}"\n'
            f'hash-format = "sha256"\n'
            f'hash = "{sha256(p)}"\n'
        )
        with open(os.path.join(meta, slug(fn) + ".pw.toml"), "w", encoding="utf-8") as f:
            f.write(body)
        written += 1
        print("  +", fn)

print(f"\nwrote {written} url-hosted .pw.toml")
