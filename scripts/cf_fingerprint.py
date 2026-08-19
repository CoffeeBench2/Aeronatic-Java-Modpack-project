#!/usr/bin/env python3
"""
CurseForge fingerprint pass — post-processes the CF zip produced by build_cf.py.

build_cf.py (packwiz curseforge export) only CF-references mods with a CF-source
metafile (3 here) and BUNDLES the rest — CF moderation rejects mass-bundling of
others' jars. This script fingerprints every bundled mod/resourcepack (CF's murmur2
over the file with whitespace bytes 9/10/13/32 stripped), asks CF which ones it hosts,
and REWRITES the pack so matches become manifest references and only genuinely-off-CF
files (our custom mods + any mod not on CF) stay bundled.

Reads the API key from .cf-key (gitignored). Output: same zip, healthy manifest.
"""
import json, os, sys, zipfile, shutil, tempfile, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES = r"D:\MC Project\Releases"
KEY_FILE = os.path.join(ROOT, ".cf-key")

# Filename -> (CurseForge projectID, fileID) for mods we pull from Modrinth whose bytes
# DON'T match CF's copy (so fingerprinting can't relocate them) but which ARE on CF with
# a redistribution-restricted license. CF moderation rejects the pack if these are bundled
# (e.g. Vista / Supplementaries License, Balm & Sophisticated = All Rights Reserved), so we
# reference the same version by explicit CF id instead. allowModDistribution=True verified.
# Add an entry whenever CF flags a bundled ARR/restricted jar; keep versions in sync with the pack.
#
# 🔴 `allowModDistribution` IS NOT THE TEST. GlitchCore reports allowModDistribution=True and CF
# moderation STILL rejected the pack for bundling it (2026-08-19). That flag only governs whether
# the API hands out a download URL; the licence is enforced separately by human/automated review.
# THE WORKING RULE: if CurseForge hosts a file with the EXACT same filename, REFERENCE it — never
# ship the bytes. Our jars come from Modrinth, so they fingerprint differently from CF's copy of the
# same version and the automatic pass cannot catch them. That is the entire reason this list exists.
#
# ⚠️ Finding these: CF's name search (`searchFilter`) MISSES projects — it did not surface Serene
# Seasons, which is demonstrably on CF. Look them up by `slug` instead; slug lookup is exact and does
# not depend on relevance ranking. Verify `isAvailable=True` before adding an entry.
MANUAL_REFS = {
    "vista-neoforge-1.21.1-4.3.1.jar":            (1368607, 8072358),
    "balm-neoforge-1.21.1-21.0.59.jar":           (531761,  8252588),
    "sophisticatedbackpacks-1.21.1-3.25.64.1919.jar": (422301, 8272377),
    "sophisticatedcore-1.21.1-1.4.59.2032.jar":   (618298,  8272330),
    # Named by CF moderation in the 1.9.4.1 rejection (2026-08-19).
    "GlitchCore-neoforge-1.21.1-2.1.0.2.jar":     (955399,  8109792),
    "SereneSeasons-neoforge-1.21.1-10.1.0.3.jar": (291874,  6182596),
    # Same trap, found by sweeping every remaining bundled jar for an exact CF filename match.
    # Moderation only reports the first offenders it hits, so fixing just the two it named would
    # have bought another rejection round.
    "FallingTree-1.21.1-1.21.1.11.jar":           (349559,  6835168),
    "aeroworks-1.3.0.jar":                        (1522473, 8409091),
    "connector-2.0.0-beta.14+1.21.1-full.jar":    (890127,  7634148),
    "create_connected-1.1.16-mc1.21.1.jar":       (947914,  8046148),
    "forgified-fabric-api-0.116.7+2.2.4+1.21.1.jar": (889079, 7658611),
    "kotlinforforge-5.12.0-all.jar":              (351264,  8335665),
    "kubejs-neoforge-2101.7.2-build.368.jar":     (238086,  8083208),
    "rhino-2101.2.7-build.85.jar":                (416294,  8218748),
    "sound-physics-remastered-neoforge-1.21.1-1.5.1.jar": (535489, 7032247),
    # Our own mods — now approved CF projects, so CF requires them REFERENCED not bundled
    # (modpack rejection 2026-07). Core = the -cf (no-updater) build build_cf injects.
    "CoffeesAeroSkins-1.1.0.jar":                  (1601639, 8486591),
}

# Same idea, but matched by PREFIX+SUFFIX instead of an exact filename.
#
# WHY: the Core's entry was pinned as "CoffeesAeroCore-1.3.15-cf.jar" and silently stopped matching
# the moment the Core was rebuilt — the jar then stayed BUNDLED while Skins and Tweaks were
# referenced, which is precisely the mix that got the pack rejected in 2026-07. An exact filename
# cannot survive a version bump, so the Core is matched by shape.
#
# The referenced file is whatever CURSEFORGE actually hosts, which is NOT necessarily the Core the
# build produced. CF has 1.3.26-cf (file 8670490, verified isAvailable=True / fileStatus=4 on
# 2026-08-19); the local build is newer. Store-channel players therefore get the CF version — that
# is the deliberate trade for not uploading a new Core each release. Bump the id below when a newer
# Core is uploaded to project 1601629, or the store pack keeps shipping the old one.
MANUAL_REF_PREFIXES = {
    ("CoffeesAeroCore-", "-cf.jar"): (1601629, 8670490),   # CF hosts 1.3.26-cf
}

def manual_ref_for(basename):
    """(projectID, fileID) for a bundled file we reference by hand, or None."""
    if basename in MANUAL_REFS:
        return MANUAL_REFS[basename]
    for (pre, suf), ids in MANUAL_REF_PREFIXES.items():
        if basename.startswith(pre) and basename.endswith(suf):
            return ids
    return None

def die(m): sys.exit("ERROR: " + m)

# --- CF murmur2 (seed=1) over whitespace-stripped bytes -----------------------
def cf_fingerprint(path):
    with open(path, "rb") as f:
        raw = f.read()
    data = bytes(b for b in raw if b not in (9, 10, 13, 32))
    m, r, seed = 0x5bd1e995, 24, 1
    length = len(data)
    h = (seed ^ length) & 0xffffffff
    i, rem = 0, length
    while rem >= 4:
        k = data[i] | (data[i+1] << 8) | (data[i+2] << 16) | (data[i+3] << 24)
        k = (k * m) & 0xffffffff
        k ^= k >> r
        k = (k * m) & 0xffffffff
        h = (h * m) & 0xffffffff
        h ^= k
        i += 4; rem -= 4
    if rem == 3: h ^= data[i+2] << 16
    if rem >= 2: h ^= data[i+1] << 8
    if rem >= 1:
        h ^= data[i]
        h = (h * m) & 0xffffffff
    h ^= h >> 13
    h = (h * m) & 0xffffffff
    h ^= h >> 15
    return h & 0xffffffff

def cf_match(fingerprints, key):
    """POST fingerprints -> {fingerprint: (modId, fileId)} for exact matches."""
    body = json.dumps({"fingerprints": fingerprints}).encode()
    req = urllib.request.Request("https://api.curseforge.com/v1/fingerprints",
                                 data=body, method="POST",
                                 headers={"x-api-key": key, "Content-Type": "application/json",
                                          "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.load(r)
    out = {}
    for em in d.get("data", {}).get("exactMatches", []):
        fl = em.get("file", {})
        fp = fl.get("fileFingerprint")          # the fingerprint lives here, NOT em["id"] (=modId)
        if fp and fl.get("modId") and fl.get("id"):
            out[fp] = (fl["modId"], fl["id"])
    return out

def _merge_into(src_dir, dst_dir):
    """Move everything from src_dir up into dst_dir, merging directories recursively."""
    os.makedirs(dst_dir, exist_ok=True)
    for name in os.listdir(src_dir):
        s, d = os.path.join(src_dir, name), os.path.join(dst_dir, name)
        if os.path.isdir(s) and os.path.isdir(d):
            _merge_into(s, d)
        else:
            if os.path.exists(d):
                if os.path.isdir(d): shutil.rmtree(d, ignore_errors=True)
                else: os.remove(d)
            shutil.move(s, d)


def sanitize_export(work):
    """Fix three structural faults in packwiz's CurseForge export before we re-zip:

    1) DOUBLED overrides. The repo keeps its pack files under a literal `overrides/` dir (for the
       self-contained mrpack), and packwiz wraps that whole tree inside CF's own `overrides/`, so
       configs export to `overrides/overrides/config/...`. After a CF launcher extracts `overrides/`
       into the instance, that becomes `.minecraft/overrides/config/` — which the game never reads, so
       EVERY bundled config (incl. coffeesaerosmp_core packVersion → "v0.0.0", iris.properties, etc.)
       is silently ignored. Flatten `overrides/overrides/*` up into `overrides/*` so config/ lands at
       the instance root, matching the working mrpack layout.
    2) SECRETS. `.cf-key` (the CurseForge API key) and any `.env` must never ride along into a
       published zip. Strip them wherever they appear.
    3) CF-BLACKLISTED lavaplayer. `overrides/.analogaudio/internal/analogplayer-*.jar` is Analog
       Audio's runtime audio player, pre-baked into the pack so limited-data players skip the
       first-run download. It shades lavaplayer INCLUDING the YouTube source — CF's automated scan
       blacklists `com/sedmelluq/.../source/youtube/*` classes and REJECTED the 1.8.1 zip for it
       (2026-07-20; the 1.8.0 zip predated that scan). Strip the whole `.analogaudio` dir from CF
       exports: the mod self-downloads the player on first launch (LavaplayerLoader + progress
       screen), so CF players just get a one-time download. mrpack/GitHub keeps the pre-baked copy.
    4) CF-BANNED Analog Audio MOD JAR. Beyond the pre-baked player (fault 3), a CF HUMAN moderator
       rejected the 1.8.1.1 file (2026-07-22) flagging the mod itself: "Please remove 'Analog Audio'
       ... it transfers user-selected files through the server. This is unsafe and a security risk."
       Analog Audio (PMOL, palm1) is NOT a CurseForge project, so it can't be manifest-referenced —
       CF will not carry it in ANY form. Strip `overrides/mods/Analog-Audio-*.jar` and its config
       from CF exports. The mod stays fully intact on Modrinth/GitHub/in-client-updater (self-hosted,
       allowed there). Known CF-only side effect: a CF-direct install lacks the mod's required
       channel and is refused by Apex until the player adds the jar (CF Core has no self-heal updater).
    """
    ov = os.path.join(work, "overrides")
    nested = os.path.join(ov, "overrides")
    if os.path.isdir(nested):
        _merge_into(nested, ov)
        shutil.rmtree(nested, ignore_errors=True)
        print("sanitize: flattened overrides/overrides -> overrides (configs now load)")

    stripped = []
    for root, _, files in os.walk(work):
        for fn in files:
            if fn == ".cf-key" or fn == ".env" or fn.endswith(".env"):
                os.remove(os.path.join(root, fn))
                stripped.append(fn)
    if stripped:
        print("sanitize: stripped secrets from export:", ", ".join(sorted(set(stripped))))

    aa = os.path.join(ov, ".analogaudio")
    if os.path.isdir(aa):
        shutil.rmtree(aa, ignore_errors=True)
        print("sanitize: stripped overrides/.analogaudio (CF-blacklisted lavaplayer/youtube; "
              "mod self-downloads its player on first run)")

    # Fault 4: strip the Analog Audio MOD JAR + config (CF human-rejected 2026-07-22; not on CF so
    # not referenceable). mrpack/GitHub keep it.
    mods_dir = os.path.join(ov, "mods")
    if os.path.isdir(mods_dir):
        for fn in os.listdir(mods_dir):
            low = fn.lower()
            if low.startswith("analog-audio") and low.endswith(".jar"):
                os.remove(os.path.join(mods_dir, fn))
                print(f"sanitize: stripped overrides/mods/{fn} (CF-banned mod; kept on Modrinth/GitHub)")
    aa_cfg = os.path.join(ov, "config", "analogaudio")
    if os.path.isdir(aa_cfg):
        shutil.rmtree(aa_cfg, ignore_errors=True)
        print("sanitize: stripped overrides/config/analogaudio (orphan config, mod removed from CF)")


def main():
    if not os.path.isfile(KEY_FILE): die(".cf-key not found")
    key = open(KEY_FILE).read().strip()

    zips = [f for f in os.listdir(RELEASES) if f.endswith("-CURSEFORGE.zip")]
    if not zips: die("no *-CURSEFORGE.zip in Releases — run build_cf.py first")
    src = os.path.join(RELEASES, sorted(zips)[-1])
    print("Post-processing:", src)

    # Extract on the same drive as Releases (D:, lots of free space) — C:'s temp fills up fast with
    # the ~500 MB zip and repeated runs, causing "No space left on device".
    work = tempfile.mkdtemp(prefix="cf_fp_", dir=RELEASES)
    with zipfile.ZipFile(src) as z:
        z.extractall(work)

    sanitize_export(work)

    manifest_path = os.path.join(work, "manifest.json")
    manifest = json.load(open(manifest_path))
    existing = {(f["projectID"], f["fileID"]) for f in manifest["files"]}

    # Fingerprint every bundled mod + resourcepack + shaderpack.
    targets = []
    for sub in ("mods", "resourcepacks", "shaderpacks"):
        d = os.path.join(work, "overrides", sub)
        if not os.path.isdir(d): continue
        for name in os.listdir(d):
            p = os.path.join(d, name)
            if os.path.isfile(p) and name.lower().endswith((".jar", ".zip")):
                targets.append(p)

    fp_of = {}
    for p in targets:
        fp_of[p] = cf_fingerprint(p)
    fps = list({v for v in fp_of.values()})
    print(f"Fingerprinting {len(targets)} bundled files ({len(fps)} unique)...")

    matched = {}
    for i in range(0, len(fps), 100):
        matched.update(cf_match(fps[i:i+100], key))

    # A fingerprint match is NOT enough — CF's hash index also returns files that exist but
    # cannot be downloaded (project unlisted/private, or the file still under review with
    # isAvailable=False). Referencing one of those makes CF auto-REJECT the upload with
    # "Invalid manifest.json file: References invalid fileIDs" — which is exactly what
    # happened to 1.8.2 on 2026-07-29 (create_parachute-1.0.4a.jar, project-1620507,
    # fileStatus=3 isAvailable=False). Verify every match and drop the unusable ones so
    # their jars stay BUNDLED instead.
    if matched:
        _ids = [fid for (_pid, fid) in matched.values()]
        _ok = set()
        for _i in range(0, len(_ids), 50):
            _body = json.dumps({"fileIds": _ids[_i:_i+50]}).encode()
            _req = urllib.request.Request(
                "https://api.curseforge.com/v1/mods/files", data=_body,
                headers={"x-api-key": key, "Accept": "application/json",
                         "Content-Type": "application/json"})
            try:
                for _d in json.load(urllib.request.urlopen(_req, timeout=60))["data"]:
                    if _d.get("isAvailable", True):
                        _ok.add(_d["id"])
            except Exception as _e:
                print("  reference validation batch failed:", str(_e)[:70])
                _ok.update(_ids[_i:_i+50])   # fail open — don't silently unbundle everything
        _drop = {fp for fp, (_p, fid) in matched.items() if fid not in _ok}
        for fp in _drop:
            del matched[fp]
        if _drop:
            print(f"dropped {len(_drop)} unusable CF match(es) (not publicly downloadable) "
                  f"— those stay bundled")
    print(f"CF hosts {len(matched)} of {len(fps)} — those become references.")

    # Move matches to manifest references; delete their bundled copies.
    added = 0
    for p, fp in fp_of.items():
        if fp in matched:
            pid, fid = matched[fp]
            if (pid, fid) not in existing:
                manifest["files"].append({"projectID": pid, "fileID": fid, "required": True})
                existing.add((pid, fid))
                added += 1
            os.remove(p)

    # Explicit references for CF-hosted restricted mods whose bytes don't fingerprint-match
    # (Modrinth build vs CF build). Reference the pinned CF file id and drop the bundled copy.
    manual = 0
    for p, fp in list(fp_of.items()):
        base = os.path.basename(p)
        ids = manual_ref_for(base)
        if fp not in matched and ids and os.path.exists(p):
            pid, fid = ids
            if (pid, fid) not in existing:
                manifest["files"].append({"projectID": pid, "fileID": fid, "required": True})
                existing.add((pid, fid))
            os.remove(p)
            manual += 1
            print("manual CF reference (restricted, un-bundled):", base)

    still_bundled = [os.path.basename(p) for p in targets
                     if fp_of[p] not in matched and not manual_ref_for(os.path.basename(p))]
    json.dump(manifest, open(manifest_path, "w"), indent=2)

    # packwiz curseforge export skips overrides/resourcepacks (.packwizignore excludes it), so
    # our own bundled packs (e.g. the Brass GUI) never make it into the CF zip. Copy any source
    # resourcepack that isn't CF-referenced and isn't already bundled — matches the mrpack bundler.
    src_rp = os.path.join(ROOT, "overrides", "resourcepacks")
    dst_rp = os.path.join(work, "overrides", "resourcepacks")
    if os.path.isdir(src_rp):
        os.makedirs(dst_rp, exist_ok=True)
        for name in os.listdir(src_rp):
            if name.lower().endswith(".zip") and not os.path.exists(os.path.join(dst_rp, name)):
                fp = cf_fingerprint(os.path.join(src_rp, name))
                if fp not in matched:  # not already CF-referenced
                    shutil.copy2(os.path.join(src_rp, name), os.path.join(dst_rp, name))
                    print("bundled missing resourcepack:", name)

    # Re-zip.
    out = src  # overwrite the same output name
    tmp_out = src + ".tmp"
    with zipfile.ZipFile(tmp_out, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(work):
            for fn in files:
                full = os.path.join(base, fn)
                z.write(full, os.path.relpath(full, work))
    shutil.move(tmp_out, out)
    shutil.rmtree(work, ignore_errors=True)

    print(f"\nDONE: {out}")
    print(f"manifest references: {len(manifest['files'])}  (+{added} matched, +{manual} manual)")
    print(f"still bundled ({len(still_bundled)}): {', '.join(sorted(still_bundled))}")

if __name__ == "__main__":
    main()
