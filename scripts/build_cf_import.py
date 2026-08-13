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

VERSION = "1.8.4"
# Deliberately behind VERSION so the in-client updater fires on first launch.
IMPORT_STAMP = "1.8.3-import"

SRC = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CURSEFORGE.zip" % VERSION)
OUT = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CF-IMPORT.zip" % VERSION)

# Ours. Never CF-referenced, always bundled.
OURS = ["CoffeesAeroCore-1.3.21.jar", "CoffeesAeroSkins-1.1.0.jar", "CoffeesAeroTweaks-1.0.0.jar"]

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
    for jar in OURS:
        src = os.path.join(OVERRIDES, "mods", jar)
        if not os.path.isfile(src):
            sys.exit("missing %s in overrides/mods" % jar)
        shutil.copyfile(src, os.path.join(mods_dir, jar))

    # --- 3. stamp the version behind live so the updater fires -------------------
    cfg = os.path.join(work, "overrides", "config", "coffeesaerosmp_core-client.toml")
    if os.path.isfile(cfg):
        t = open(cfg, encoding="utf8").read()
        t2 = re.sub(r'^packVersion\s*=\s*"[^"]*"', 'packVersion = "%s"' % IMPORT_STAMP, t, flags=re.M)
        # Both URLs must be live, or the Update button is disabled and nothing self-heals.
        if "packTomlUrl" not in t2:
            t2 += ('\npackTomlUrl = "https://raw.githubusercontent.com/CoffeeBench2/'
                   'Aeronatic-Java-Modpack-project/main/pack.toml"\n')
        open(cfg, "w", encoding="utf8", newline="\n").write(t2)
        print("  packVersion stamped %s (live is %s) -> updater fires on first launch"
              % (IMPORT_STAMP, VERSION))

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
        print("  %-34s %s" % (j, "bundled" if j in bundled else "*** MISSING ***"))
    print("  %-34s %s" % ("Analog Audio",
          "not bundled - updater fetches it" if not any("analog" in b.lower() for b in bundled)
          else "bundled"))
finally:
    shutil.rmtree(work, ignore_errors=True)
