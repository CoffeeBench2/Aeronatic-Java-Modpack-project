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
MANUAL_REFS = {
    "vista-neoforge-1.21.1-4.3.1.jar":            (1368607, 8072358),
    "balm-neoforge-1.21.1-21.0.59.jar":           (531761,  8252588),
    "sophisticatedbackpacks-1.21.1-3.25.64.1919.jar": (422301, 8272377),
    "sophisticatedcore-1.21.1-1.4.59.2032.jar":   (618298,  8272330),
}

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
        if fp not in matched and base in MANUAL_REFS and os.path.exists(p):
            pid, fid = MANUAL_REFS[base]
            if (pid, fid) not in existing:
                manifest["files"].append({"projectID": pid, "fileID": fid, "required": True})
                existing.add((pid, fid))
            os.remove(p)
            manual += 1
            print("manual CF reference (restricted, un-bundled):", base)

    still_bundled = [os.path.basename(p) for p in targets
                     if fp_of[p] not in matched and os.path.basename(p) not in MANUAL_REFS]
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
