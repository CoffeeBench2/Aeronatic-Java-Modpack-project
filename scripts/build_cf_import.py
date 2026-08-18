"""
Build the LIGHT CurseForge import zip handed to players directly (CF app -> Import).

Starts from the fingerprinted store zip, which is already mostly CF *references* rather than bundled
jars (521 MB -> ~93 MB), then makes the three changes that turn a store build into an import build:

  1. FULL Core instead of the -cf one. The store jar has the updater compiled out (CF policy);
     an import zip is not moderated, so it ships the real thing and the instance self-heals.
  2. Our own mods are force-bundled. They are not CF projects, and a fingerprint match on them
     would point players at somebody else's file.
  3. packVersion is stamped BEHIND the live pack, so VersionCheck reports OUTDATED on first launch
     and the player is prompted to Update. That update is what pulls everything this zip
     deliberately leaves out -- Analog Audio and anything else not on CF -- straight from the
     packwiz index. Bundling those here would defeat the point of a light zip.

Dependency-critical jars (Connector, FFAPI, KotlinForForge, KubeJS, Rhino, GlitchCore...) stay
bundled by the fingerprint pass, so the profile BOOTS as imported. That matters: the updater runs
in-game, so a profile that cannot reach the title screen can never repair itself.
"""
import json, os, re, shutil, sys, tempfile, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES = r"D:\MC Project\Releases"
OVERRIDES = os.path.join(ROOT, "overrides")
KEY_FILE = os.path.join(ROOT, ".cf-key")

def _pack_version():
    t = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
    m = re.search(r'(?m)^version\s*=\s*"([^"]+)"', t)
    return m.group(1) if m else "0.0.0"


# Derived, never pinned. This was hardcoded to "1.8.4" and quietly kept building an import zip
# five pack versions stale, which is what left CurseForge players unable to update (2026-08-17).
VERSION = _pack_version()

# Stamped to MATCH live, not behind it. The old build stamped a fake older version so the
# in-client updater would fire on first launch; that made a CF importer sync a several-hundred-
# file delta through GitHub origin (every request cache-busted), which answers 429 part-way and
# fails silently. This zip already IS the current pack, so there is nothing to fetch.
IMPORT_STAMP = VERSION

SRC = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CURSEFORGE.zip" % VERSION)
OUT = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CF-IMPORT.zip" % VERSION)

# Ours. Never CF-referenced, always bundled.
def _ours():
    """Newest build of each of our mods actually present in overrides/mods.

    Pinned filenames rotted here too: CoffeesAeroCore-1.3.23.jar no longer exists, so this
    script would sys.exit on every run until somebody noticed.
    """
    import glob as _g
    out = []
    for stem in ("CoffeesAeroCore", "CoffeesAeroSkins", "CoffeesAeroTweaks"):
        search = [os.path.join(OVERRIDES, "mods", stem + "-*.jar")]
        if stem == "CoffeesAeroCore":
            # Prefer the freshest FULL build wherever it was staged.
            search.insert(0, os.path.join(ROOT, "src", "AeroCore", "build", "libs",
                                          stem + "-*.jar"))
        hits = []
        for pat in search:
            hits += [h for h in _g.glob(pat) if not h.endswith("-cf.jar")]
        if hits:
            out.append(max(hits, key=os.path.getmtime))   # full path, not just a name
    return out


OURS = _ours()

def cf_names(mod_ids):
    """Bulk-resolve CF project ids -> names, to catch a fingerprint matching one of our mods."""
    try:
        key = open(KEY_FILE).read().strip()
    except OSError:
        return {}
    out = {}
    for i in range(0, len(mod_ids), 200):
        chunk = mod_ids[i:i + 200]
        req = urllib.request.Request(
            "https://api.curseforge.com/v1/mods",
            data=json.dumps({"modIds": chunk}).encode(),
            headers={"x-api-key": key, "Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                for m in json.loads(r.read())["data"]:
                    out[m["id"]] = m["name"]
        except Exception as e:
            print("  (CF name lookup failed: %s)" % e)
            return out
    return out

if not os.path.isfile(SRC):
    sys.exit("missing %s -- run build_cf.py then cf_fingerprint.py first" % SRC)

work = tempfile.mkdtemp(prefix="cf_import_", dir=RELEASES)
try:
    with zipfile.ZipFile(SRC) as z:
        z.extractall(work)

    manifest = json.load(open(os.path.join(work, "manifest.json"), encoding="utf8"))

    # --- 1. sanity-check the references against our own mod names ----------------
    ids = [f["projectID"] for f in manifest["files"]]
    names = cf_names(ids)
    suspicious = [(i, n) for i, n in names.items()
                  if re.search(r"coffee|aero\s*smp|aeroskins|aerotweaks", n, re.I)]
    if suspicious:
        print("  !! CF references that look like OUR mods -- dropping them:")
        for i, n in suspicious:
            print("       %s (project %d)" % (n, i))
        drop = {i for i, _ in suspicious}
        manifest["files"] = [f for f in manifest["files"] if f["projectID"] not in drop]
    else:
        print("  reference sanity check: no CF ref resolves to one of our mods")

    # --- 2. force-bundle our mods, drop the -cf Core -----------------------------
    mods_dir = os.path.join(work, "overrides", "mods")
    os.makedirs(mods_dir, exist_ok=True)
    for stale in os.listdir(mods_dir):
        if stale.lower().startswith("coffeesaero"):
            os.remove(os.path.join(mods_dir, stale))
    for src in OURS:
        if not os.path.isfile(src):
            sys.exit("missing %s" % src)
        shutil.copyfile(src, os.path.join(mods_dir, os.path.basename(src)))

    # Mods build_cf.py strips for CurseForge moderation but the SERVER still loads. The import
    # channel is unmoderated and no longer runs the updater, so these must ride along or the
    # profile is missing a side=BOTH mod the moment a player connects.
    MUST_BUNDLE = ["Analog-Audio-"]
    for prefix in MUST_BUNDLE:
        import glob as _g2
        found = _g2.glob(os.path.join(OVERRIDES, "mods", prefix + "*.jar"))
        if not found:
            sys.exit("missing a MUST_BUNDLE mod matching %s* in overrides/mods" % prefix)
        for src in found:
            name = os.path.basename(src)
            if not os.path.isfile(os.path.join(mods_dir, name)):
                shutil.copyfile(src, os.path.join(mods_dir, name))
                print("  force-bundled (side=BOTH, stripped by build_cf): %s" % name)
        # And drop any CF manifest reference to it, or the launcher installs it twice.
        manifest["files"] = [f for f in manifest.get("files", [])
                             if not str(f.get("__name", "")).startswith(prefix)]

    # --- 2b. strip files the pack has retired ------------------------------------
    # The store zip is produced from a packwiz export that may predate a removal, so a dropped
    # file can still be sitting in overrides/ here. Shipping it would hand every importer content
    # the pack no longer wants, and they would only lose it later when the updater pruned it.
    # Keep this in sync with InClientUpdater.RETIRED_FILES.
    RETIRED = [
        "overrides/resourcepacks/Visual Effects+.zip",
        "overrides/kubejs/client_scripts/cdr_contractor_ponder.js",
        "overrides/kubejs/client_scripts/cdr_market_ponder.js",
        "overrides/kubejs/client_scripts/cdr_p2p_ponder.js",
    ]
    for rel in RETIRED:
        p = os.path.join(work, rel.replace("/", os.sep))
        if os.path.isfile(p):
            os.remove(p)
            print("  stripped retired file: %s" % rel)

    # --- 3. stamp the version behind live so the updater fires -------------------
    cfg = os.path.join(work, "overrides", "config", "coffeesaerosmp_core-client.toml")
    if os.path.isfile(cfg):
        t = open(cfg, encoding="utf8").read()
        t2 = re.sub(r'^packVersion\s*=\s*"[^"]*"', 'packVersion = "%s"' % IMPORT_STAMP, t, flags=re.M)
        # Both URLs must be live, or the Update button is disabled and nothing self-heals.
        if "packTomlUrl" not in t2:
            t2 += ('\npackTomlUrl = "https://raw.githubusercontent.com/CoffeeBench2/'
                   'Aeronatic-Java-Modpack-project/main/pack.toml"\n')
        # Detect updates, but never download them on this channel. See StoreUpdateScreen: a
        # CF-import install cannot complete a large packwiz delta without GitHub rate-limiting
        # it, and the failure is silent. Point at the store instead.
        if re.search(r"(?m)^manualUpdateOnly\s*=", t2):
            t2 = re.sub(r"(?m)^manualUpdateOnly\s*=.*$", "manualUpdateOnly = true", t2)
        else:
            t2 += "\nmanualUpdateOnly = true\n"
        open(cfg, "w", encoding="utf8", newline="\n").write(t2)
        print("  packVersion stamped %s (matches live) + manualUpdateOnly=true"
              % IMPORT_STAMP)

    manifest["name"] = "Coffees Aero SMP"
    manifest["version"] = VERSION
    json.dump(manifest, open(os.path.join(work, "manifest.json"), "w", encoding="utf8"), indent=2)

    if os.path.exists(OUT):
        os.remove(OUT)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, _, files in os.walk(work):
            for fn in files:
                full = os.path.join(root, fn)
                z.write(full, os.path.relpath(full, work).replace("\\", "/"))

    bundled = sorted(os.listdir(mods_dir))
    print()
    print("manifest references : %d" % len(manifest["files"]))
    print("bundled mods        : %d" % len(bundled))
    print("output              : %s" % OUT)
    print("size                : %.1f MB" % (os.path.getsize(OUT) / 1024 / 1024))
    print()
    for j in OURS:
        n = os.path.basename(j)
        print("  %-34s %s" % (n, "bundled" if n in bundled else "*** MISSING ***"))
    print("  %-34s %s" % ("Analog Audio",
          "not bundled - updater fetches it" if not any("analog" in b.lower() for b in bundled)
          else "bundled"))
finally:
    shutil.rmtree(work, ignore_errors=True)
