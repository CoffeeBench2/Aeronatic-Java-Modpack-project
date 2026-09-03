"""Build the low-end ("potato") client channels of the pack, as self-contained mrpacks.

    py scripts/build_potato_mrpack.py           # both tiers
    py scripts/build_potato_mrpack.py potato    # just the normal potato build
    py scripts/build_potato_mrpack.py min       # just the minimum build

Two tiers:
  potato — the six heavy client mods dropped (camera tilt, punchy, DH, both sound mods, particles).
  min    — potato plus the whole shader stack, ETF/EMF, and the remaining cosmetic renderers.

Same self-contained layout as build_mrpack.py (files:[], everything under overrides/), with four
deliberate differences:

  1. HEAVY CLIENT MODS DROPPED. Everything cut is client-only cosmetics/audio/rendering, and every
     cut was dependency-swept against all ~239 jars in the pack first. That sweep is not optional:
     sodiumdynamiclights LOOKS like an obvious potato cut and is REQUIRED by immersivelanterns, so
     dropping it would take the pack down on load. Content mods are never cut — the client would
     then be missing registry entries the server sends, and could not join at all.
  2. THE IN-CLIENT UPDATER IS STRIPPED. The full Core jar is swapped for the `-cf` build
     (`gradlew jar -PnoUpdater`), which ships without com/coffeesaerosmp/core/{update,version}.
     This is REQUIRED, not cosmetic: the updater syncs mods/ against the live packwiz index, so a
     potato client that ran it would re-download every mod stripped above and un-potato itself.
     versionCheckUrl is also blanked. Potato players get new packs by hand.
  3. A SMALL SET OF PERFORMANCE / NETWORK MODS IS ADDED (see EXTRA_MODS_DIR). Staged outside the
     repo like the -cf Core, because they are not part of the packwiz-indexed pack.
  4. POTATO DEFAULTS are written into options.txt (render distance, particles, clouds, shadows).

Analog Audio is deliberately KEPT in both tiers, including the bundled `.analogaudio` runtime, so
players never see its first-launch download prompt. Do not "optimise" that directory out.

NOTE: this script NEVER writes into overrides/. build_mrpack.py stamps packVersion in place; doing
that here would leave the main pack's config edited for a potato build. Everything is patched in
memory on the way into the zip instead, so the repo tree is untouched and the live pack cannot drift.
"""
import zipfile, json, os, re, glob, sys

ROOT = r"D:\MC Project\untitled"
OVERRIDES = os.path.join(ROOT, "overrides")
RELEASES = r"D:\MC Project\Releases"

BASE_VERSION = "1.9.4.3"

# Added client-only mods, both tiers. Verified on Modrinth for 1.21.1 + NeoForge, deps checked:
#   lithium      general game-logic optimizer (Sable already ships lithium compat)
#   BetterF3     readable FPS/memory HUD (needs cloth_config >=15 — pack ships 15.0.140)
#   fast-ip-ping faster server-list ping / address resolution, client-side
#   packetfixer  raises client packet-size limits — pure mixins, registers no channel, so it cannot
#                cause a join-time channel mismatch against the SMP
# More Culling was evaluated and REJECTED: its neoforge.mods.toml declares minecraft "[1.21,1.21.1)",
# which excludes 1.21.1 — FML would refuse to load it.
EXTRA_MODS_DIR = os.path.join(RELEASES, "potato-extra-mods")

# Jar prefixes dropped in EVERY potato tier. Prefix match, not exact filenames: a version bump in
# the main pack must not silently stop matching, because that fails open — the heavy mod would ship.
BASE_EXCLUDE = [
    # CameraOverhaul was dropped from the pack entirely on 2026-09-03, so it no longer needs
    # excluding. Kept in step with ClientMode.POTATO_EXCLUDE.
    "punchy-",                          # hit shake / punch effects
    "DistantHorizons-",                 # LOD renderer — the single biggest low-end cost
    "sound-physics-remastered-",        # raytraced audio (CPU)
    "sounds-",                          # Sounds (hibi) — UI/ambient audio
    "SubtleEffects-",                   # ambient particles
    "CoffeesAeroCore-",                 # replaced by the -cf (no updater) build
]

# Additional cuts for the `min` tier. All client-only renderers; all swept for reverse deps, which
# turned up nothing but OPTIONAL references (tombstone->iris, createrailgrinding->EMF).
MIN_EXCLUDE = [
    "iris-neoforge-",                   # shader pipeline — a potato will never run shaders
    "iris-flywheel-compat-",            # only meaningful with iris present
    "EuphoriaPatcher-",                 # patches the shaderpack at startup; pure cost without iris
    "entity_model_features-",           # EMF — custom entity models
    "entity_texture_features_",         # ETF — custom/random entity textures
    "explosiveenhancement-",            # client-side explosion particle overhaul
    "waveycapes-",                      # per-frame cape simulation
    "skinlayers3d-",                    # 3D skin layers on every player
    "notenoughanimations-",             # extra per-player animation work
]

# Config files that only exist to serve mods the `min` tier removes.
MIN_EXCLUDE_CONFIG = ["iris.properties", "entity_texture_features.json"]
MIN_EXCLUDE_CONFIG_DIRS = ["euphoria_patcher"]

INCLUDE_DIRS = {"config", "mods", "resourcepacks", "shaderpacks", ".analogaudio"}

# Vanilla options.txt keys. Sodium mirrors all of these, so tuning here covers both renderers.
# Keys absent from the shipped file are appended; present ones are rewritten.
POTATO_OPTIONS = {
    "renderDistance": "8",          # was 16 — with DH gone this is the whole draw distance
    "simulationDistance": "5",      # was 10 — 5 is the floor
    "graphicsMode": "0",            # fast
    "particles": "2",               # minimal
    "entityShadows": "false",
    "renderClouds": "false",
    "entityDistanceScaling": "0.75",
    "biomeBlendRadius": "0",
    "enableVsync": "false",         # raises the FPS ceiling on weak GPUs
    "prioritizeChunkUpdates": "0",  # threaded — no stall on block placement
}
MIN_OPTIONS = dict(POTATO_OPTIONS, renderDistance="6", entityDistanceScaling="0.5", mipmapLevels="2")

# The `min` tier ships only the GUI pack. The rest are 3D model packs (armour, arrows, mace,
# trident, bows, lanterns) — extra geometry on every item render, and ~7 MB of download for players
# on limited data. Listed by the exact name options.txt uses.
MIN_KEEP_RESOURCEPACKS = ["Coffees Aero Brass GUI.zip"]

TIERS = {
    "potato": {
        "suffix": "potato",
        "name": "Coffees Aero SMP (Potato)",
        "summary": "Low-end build — no DH/particles/audio-physics, no in-client updater",
        "exclude": BASE_EXCLUDE,
        "options": POTATO_OPTIONS,
        "strip_shaders": False,
    },
    "min": {
        "suffix": "potato-min",
        "name": "Coffees Aero SMP (Potato Min)",
        "summary": "Minimum build — potato cuts plus the shader stack, ETF/EMF and cosmetic renderers",
        "exclude": BASE_EXCLUDE + MIN_EXCLUDE,
        "options": MIN_OPTIONS,
        "strip_shaders": True,
    },
}


def newest_cf_core():
    cands = sorted(glob.glob(os.path.join(RELEASES, "cf-core-noupdater", "CoffeesAeroCore-*-cf.jar")),
                   key=os.path.getmtime)
    if not cands:
        raise SystemExit("no -cf Core jar staged. Build it: cd src/AeroCore && gradlew.bat jar -PnoUpdater\n"
                         "then copy build/libs/CoffeesAeroCore-*-cf.jar into Releases/cf-core-noupdater/")
    return cands[-1]


def patch_core_config(text, version):
    text = re.sub(r'(?m)^packVersion\s*=\s*".*"$', f'packVersion = "{version}"', text)
    # The -cf Core has no version-check class at all; blanking the URL keeps the config honest and
    # makes the intent survive a future swap back to the full jar.
    return re.sub(r'(?m)^versionCheckUrl\s*=\s*".*"$', 'versionCheckUrl = ""', text)


def patch_options(text, overrides, keep_packs=None):
    lines = text.splitlines()
    seen = set()
    for i, line in enumerate(lines):
        key = line.split(":", 1)[0]
        if key in overrides:
            lines[i] = f"{key}:{overrides[key]}"
            seen.add(key)
        elif key == "resourcePacks" and keep_packs is not None:
            kept = ["vanilla"] + [f"file/{p}" for p in keep_packs]
            lines[i] = "resourcePacks:" + json.dumps(kept)
    for key, val in overrides.items():
        if key not in seen:
            lines.append(f"{key}:{val}")
    return "\n".join(lines) + "\n"


def _add_bytes(zf, data, rel):
    zi = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zf.writestr(zi, data)


def _add(zf, full, rel):
    with open(full, "rb") as fh:
        _add_bytes(zf, fh.read(), rel)


def build(tier):
    cfg = TIERS[tier]
    version = f"{BASE_VERSION}-{cfg['suffix']}"
    out = os.path.join(RELEASES, f"CoffeesAeroSMP-{version}.mrpack")
    strip = cfg["strip_shaders"]

    manifest = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": cfg["name"],
        "summary": cfg["summary"],
        "files": [],
        "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.244"},
    }

    os.makedirs(os.path.dirname(out), exist_ok=True)
    z = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
    z.writestr("modrinth.index.json", json.dumps(manifest, indent=2))

    count, dropped = 0, []
    for top in sorted(INCLUDE_DIRS):
        base = os.path.join(OVERRIDES, top)
        if not os.path.isdir(base):
            continue
        if top == "shaderpacks" and strip:
            dropped += sorted(os.listdir(base))
            continue
        for dirpath, _dirs, files in os.walk(base):
            for fn in sorted(files):
                full = os.path.join(dirpath, fn)
                rel = "overrides/" + os.path.relpath(full, OVERRIDES).replace("\\", "/")
                if top == "mods" and any(fn.startswith(p) for p in cfg["exclude"]):
                    dropped.append(fn)
                    continue
                if top == "resourcepacks" and strip and fn not in MIN_KEEP_RESOURCEPACKS:
                    dropped.append(fn)
                    continue
                if top == "config" and strip and (fn in MIN_EXCLUDE_CONFIG
                                                  or any(f"/{d}/" in rel for d in MIN_EXCLUDE_CONFIG_DIRS)):
                    dropped.append(fn)
                    continue
                if fn == "coffeesaerosmp_core-client.toml":
                    with open(full, "r", encoding="utf-8") as fh:
                        _add_bytes(z, patch_core_config(fh.read(), version).encode("utf-8"), rel)
                    count += 1
                    continue
                _add(z, full, rel)
                count += 1

    # The no-updater Core, then the added performance/network mods.
    core = newest_cf_core()
    _add(z, core, f"overrides/mods/{os.path.basename(core)}")
    count += 1
    added = [os.path.basename(core)]
    for fn in sorted(os.listdir(EXTRA_MODS_DIR)):
        if not fn.endswith(".jar"):
            continue
        _add(z, os.path.join(EXTRA_MODS_DIR, fn), f"overrides/mods/{fn}")
        added.append(fn)
        count += 1

    # options.txt last: patched, never written back to overrides/. UTF-8 with NO BOM — a BOM makes
    # Minecraft silently discard the whole file and every shipped setting reverts to vanilla defaults.
    opts = os.path.join(OVERRIDES, "options.txt")
    if os.path.isfile(opts):
        with open(opts, "r", encoding="utf-8-sig") as fh:
            patched = patch_options(fh.read(), cfg["options"],
                                    MIN_KEEP_RESOURCEPACKS if strip else None)
        _add_bytes(z, patched.encode("utf-8"), "overrides/options.txt")
        count += 1

    z.close()
    print(f"\n=== {version} — {cfg['name']} ===")
    print(f"dropped ({len(dropped)}): " + ", ".join(dropped))
    print(f"added ({len(added)}): " + ", ".join(added))
    print(f"files bundled: {count}")
    print(f"output: {out}")
    print(f"size: {os.path.getsize(out)/1024/1024:.1f} MB")


for t in (sys.argv[1:] or list(TIERS)):
    if t not in TIERS:
        raise SystemExit(f"unknown tier {t!r} — choose from {list(TIERS)}")
    build(t)
