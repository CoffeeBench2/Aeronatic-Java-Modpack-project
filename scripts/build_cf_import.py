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
KEY = open(KEY_FILE).read().strip() if os.path.isfile(KEY_FILE) else ""

def _pack_version():
    t = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
    m = re.search(r'(?m)^version\s*=\s*"([^"]+)"', t)
    return m.group(1) if m else "0.0.0"


# Derived, never pinned. This was hardcoded to "1.8.4" and quietly kept building an import zip
# five pack versions stale, which is what left CurseForge players unable to update (2026-08-17).
VERSION = _pack_version()

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cf_fingerprint import cf_fingerprint  # single implementation, shared with the fingerprint pass

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
    #
    # 🔴 THE BOOT CHAIN BELOW IS NOT OPTIONAL, AND USED TO BE AN ASSUMPTION RATHER THAN A RULE.
    # This module's docstring says these "stay bundled by the fingerprint pass" — that was never
    # enforced anywhere, it just happened to be true because cf_fingerprint.py failed to match
    # them. On 2026-08-19 the fingerprint pass learned to match 12 more CF-hosted jars, every one
    # of these among them, and the 1.9.4.1 import zip shipped with its ENTIRE boot chain converted
    # to CF references: 21 bundled jars -> 11, 39.2 MB -> 13.5 MB.
    #
    # Why that is fatal specifically for the IMPORT zip: it is handed to a player and imported
    # directly, and the in-client updater only runs once the game reaches the title screen. A
    # profile missing its language provider / Fabric-compat layer cannot reach the title screen,
    # so it can never repair itself. A store install has CF resolving references for it; an import
    # install does not get that guarantee.
    #
    # Keep this list in sync with anything the game needs at CLASSLOAD time, not just at play time.
    MUST_BUNDLE = [
        "Analog-Audio-",              # side=BOTH, stripped by build_cf for CF moderation
        "connector-",                 # Fabric-compat layer; mods classload through it
        "forgified-fabric-api-",      # matched pair with Connector — never ship one without the other
        "kotlinforforge-",            # language provider
        "kubejs-neoforge-",           # runs scripts during load
        "rhino-",                     # KubeJS's script engine; useless apart
        "GlitchCore-neoforge-",       # hard dep of the worldgen stack
    ]
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

    # ── COVERAGE GATE ─────────────────────────────────────────────────────────
    # Every mod in the pack must arrive somehow: bundled here, or downloaded by CurseForge from a
    # reference. Nothing may silently vanish, and nothing may arrive twice.
    #
    # Counting is not proof. "239 references, 246 mods, 20 bundled" looks healthy and says nothing
    # about whether any PARTICULAR mod is covered, because the reference list also contains
    # resourcepacks. Check each mod individually.
    # Compare by CF FINGERPRINT, never by filename. CurseForge stores a file under the name the
    # author uploaded, which routinely differs from the name in the pack for the very same bytes --
    # "Terralith_1.21.x_v2.6.2.jar" vs "Terralith_1.21.1_v2.6.2_Neoforge.jar", and several mods whose
    # CF name uses spaces where ours uses hyphens. A name comparison reports those as uncovered; if
    # you then "fix" it by bundling them, CurseForge still downloads its own copy and the instance
    # ends up with the mod twice, which is a much worse failure than the one you were chasing.
    fps = {}
    for f in sorted(os.listdir(os.path.join(OVERRIDES, "mods"))):
        if f.endswith(".jar") and f not in bundled:
            fps[cf_fingerprint(os.path.join(OVERRIDES, "mods", f))] = f
    try:
        ids = [f["fileID"] for f in manifest["files"]]
        seen = set()
        fid_fp = {}
        for i in range(0, len(ids), 200):
            req = urllib.request.Request(
                "https://api.curseforge.com/v1/mods/files",
                data=json.dumps({"fileIds": ids[i:i + 200]}).encode(),
                headers={"x-api-key": KEY, "Content-Type": "application/json",
                         "Accept": "application/json"},
                method="POST")
            for f in json.load(urllib.request.urlopen(req, timeout=90)).get("data", []):
                seen.add(f.get("fileFingerprint"))
                fid_fp[f["id"]] = f.get("fileFingerprint")
    except Exception as e:
        sys.exit("REFUSING TO SHIP: could not resolve CF references (%s). "
                 "Without this the zip cannot be proven complete." % e)

    # Anything we bundled must NOT also be referenced, or CurseForge downloads its own copy
    # alongside ours. Today that survives only because CF happens to store GlitchCore under the same
    # filename, so the download overwrites the bundled jar -- a coincidence, not a design. If CF ever
    # renames it the instance gets the mod twice and will not boot. Drop the reference instead.
    bfp = {cf_fingerprint(os.path.join(mods_dir, b)): b for b in bundled}
    before = len(manifest["files"])
    keep, dropped = [], []
    for entry in manifest["files"]:
        fp = fid_fp.get(entry["fileID"])
        if fp in bfp:
            dropped.append(bfp[fp])
        else:
            keep.append(entry)
    if dropped:
        manifest["files"] = keep
        print()
        print("dedup: dropped %d reference(s) already bundled:" % len(dropped))
        for d in dropped:
            print("   -", d)
        with zipfile.ZipFile(OUT, "a", zipfile.ZIP_DEFLATED) as _z:
            pass
        with open(os.path.join(work, "manifest.json"), "w", encoding="utf-8") as fh:
            json.dump(manifest, fh, indent=2)
        rezip = True
    else:
        rezip = False

    uncovered = sorted(n for fp, n in fps.items() if fp not in seen)
    if uncovered or rezip:
        print()
        if uncovered:
            print("coverage gate: %d mod(s) are in NEITHER column -- bundling them:" % len(uncovered))
        for f in uncovered:
            print("   +", f)
            shutil.copy2(os.path.join(OVERRIDES, "mods", f), os.path.join(mods_dir, f))
        # Rebuild the zip so the additions are actually in it.
        with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
            for root, _, files in os.walk(work):
                for f in files:
                    full = os.path.join(root, f)
                    z.write(full, os.path.relpath(full, work).replace("\\", "/"))
        bundled = sorted(os.listdir(mods_dir))
        print("re-zipped: %d bundled, %.1f MB" % (len(bundled), os.path.getsize(OUT) / 1024 / 1024))

    # Hard gate. The 1.9.4.1 import zip shipped with its whole boot chain referenced instead of
    # bundled and NOTHING complained — the build printed a smaller size and looked fine. Size alone
    # is not a signal (a legitimately smaller zip is normal once mods move to CF references), so
    # assert the contents instead and refuse to produce an unbootable zip.
    missing = [p for p in MUST_BUNDLE
               if not any(b.lower().startswith(p.lower()) for b in bundled)]
    print()
    for p in MUST_BUNDLE:
        hit = next((b for b in bundled if b.lower().startswith(p.lower())), None)
        print("  MUST_BUNDLE %-26s %s" % (p, hit or "*** MISSING ***"))
    if missing:
        sys.exit("\nREFUSING TO SHIP: boot-critical jars are not bundled: %s\n"
                 "cf_fingerprint.py has converted them to CF references. An imported profile "
                 "cannot reach the title screen without these, and the in-client updater only "
                 "runs after the title screen — so it can never repair itself." % ", ".join(missing))
finally:
    shutil.rmtree(work, ignore_errors=True)
