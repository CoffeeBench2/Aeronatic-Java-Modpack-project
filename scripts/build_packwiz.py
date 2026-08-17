"""Regenerate the packwiz index from the CURRENT pack contents, pinning every community
mod to the EXACT version we ship by looking it up on Modrinth by file hash. This guarantees
the packwiz/bootstrap pack never drifts from the bundled .mrpack (or the server).

- Modrinth hit  -> writes a metadata .pw.toml (player downloads from Modrinth's CDN).
- Modrinth miss -> reported for manual handling (CurseForge-only mods, or our custom/
  hand-modified jars which we host on the GitHub Release via `packwiz url add`).

Run `packwiz refresh` afterwards to rebuild index.toml + the pack hash.
"""
import os, hashlib, json, urllib.request, urllib.error, re, sys

ROOT = r"D:\MC Project\untitled"
OVERRIDES = os.path.join(ROOT, "overrides")
SOURCES = [
    ("mods",          os.path.join(OVERRIDES, "mods"),          (".jar",)),
    ("resourcepacks", os.path.join(OVERRIDES, "resourcepacks"), (".zip",)),
]
UA = {"User-Agent": "CoffeeBench2/Aeronatic-Java-Modpack-project packwiz-gen"}

def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()

def modrinth_by_hash(h):
    url = f"https://api.modrinth.com/v2/version_file/{h}?algorithm=sha1"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        print(f"  ! HTTP {e.code} for {h}")
        return None
    except Exception as e:
        print(f"  ! {e}")
        return None

def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")

def write_pwtoml(meta_dir, fname, title, jar, sha1hex, ver):
    # pick the file in the version that matches our hash (the primary/our exact file)
    file_obj = None
    for fo in ver.get("files", []):
        if fo.get("hashes", {}).get("sha1") == sha1hex:
            file_obj = fo
            break
    if file_obj is None:
        file_obj = next((f for f in ver.get("files", []) if f.get("primary")), None) or ver.get("files", [None])[0]
    if not file_obj:
        return False
    sha512 = file_obj.get("hashes", {}).get("sha512", "")
    body = (
        f'name = "{title}"\n'
        f'filename = "{jar}"\n'
        f'side = "both"\n\n'
        f'[download]\n'
        f'url = "{file_obj["url"]}"\n'
        f'hash-format = "sha512"\n'
        f'hash = "{sha512}"\n\n'
        f'[update]\n'
        f'[update.modrinth]\n'
        f'mod-id = "{ver["project_id"]}"\n'
        f'version = "{ver["id"]}"\n'
    )
    with open(os.path.join(meta_dir, slug(os.path.splitext(jar)[0]) + ".pw.toml"), "w", encoding="utf-8") as fh:
        fh.write(body)
    return True

found, missing = [], []
for cat, folder, exts in SOURCES:
    meta_dir = os.path.join(ROOT, cat)
    os.makedirs(meta_dir, exist_ok=True)
    # Metadata is PRUNED AT THE END, never up front — see the prune block after the loop.
    #
    # 2026-08-17: this used to delete every .pw.toml before regenerating and only write back the
    # Modrinth hits. That silently destroyed metadata for ~20 self-hosted mods (AeroCore,
    # CoffeesAeroSkins/Tweaks, the whole FTB suite, Tombstone, SereneSeasons, GlitchCore...).
    # Pushing it would have made every client's updater DELETE those mods on next launch; it was
    # caught by hand minutes before the 1.9.4 push.
    #
    # The invariant now is simply: AN ENTRY IS ONLY REMOVED IF THE FILE IT NAMES IS NO LONGER IN
    # THE PACK, or a freshly written entry already covers that same file. Anything still shipping
    # keeps its metadata even when Modrinth cannot resolve it (self-hosted jars, and mods whose
    # exact build has since been pulled from Modrinth — three of ours are in that state).
    # Do not "simplify" this back into an up-front wipe.
    if not os.path.isdir(folder):
        continue
    for jar in sorted(os.listdir(folder)):
        if not jar.lower().endswith(exts):
            continue
        h = sha1(os.path.join(folder, jar))
        ver = modrinth_by_hash(h)
        if ver:
            title = ver.get("name", os.path.splitext(jar)[0])
            ok = write_pwtoml(meta_dir, cat, title, jar, h, ver)
            (found if ok else missing).append(jar)
            print(f"  + {jar}")
        else:
            missing.append(jar)
            print(f"  - (not on Modrinth) {jar}")

# ── Prune ────────────────────────────────────────────────────────────────────────────────────
# Runs LAST, and only ever removes an entry when one of two things is true:
#   1. the file it names is no longer in the pack   -> genuinely superseded or removed, or
#   2. another entry already covers that same file  -> a duplicate from a slug rename.
# An entry for a file that still ships is never touched, whatever its source. That is what keeps
# self-hosted mods (and mods whose exact build has vanished from Modrinth) in the index.
pruned, dupes = 0, 0
for cat, folder, exts in SOURCES:
    meta_dir = os.path.join(ROOT, cat)
    present = set(os.listdir(folder)) if os.path.isdir(folder) else set()
    seen = {}
    entries = []
    for f in sorted(os.listdir(meta_dir)):
        if not f.endswith(".pw.toml"):
            continue
        p = os.path.join(meta_dir, f)
        with open(p, encoding="utf-8") as fh:
            body = fh.read()
        m = re.search(r'^filename\s*=\s*"(.+?)"', body, re.M)
        entries.append((p, f, m.group(1) if m else None, "[update.modrinth]" in body))

    for p, f, fname, tracked in entries:
        if fname is None or fname not in present:
            os.remove(p)
            print(f"  - pruned (file no longer in pack) {f}")
            pruned += 1
            continue
        if fname in seen:
            # Two entries for one file. Keep the Modrinth-tracked one, since packwiz can update it.
            keep_new = tracked and not seen[fname][1]
            drop = p if not keep_new else seen[fname][0]
            os.remove(drop)
            print(f"  - pruned (duplicate of {fname}) {os.path.basename(drop)}")
            dupes += 1
            if keep_new:
                seen[fname] = (p, tracked)
        else:
            seen[fname] = (p, tracked)

    orphans = sorted(x for x in present if x.lower().endswith(exts) and x not in seen)
    for o in orphans:
        print(f"  !! NO METADATA for {o} — run add_hosted_mods.py")

print(f"\n=== Modrinth-pinned: {len(found)}  |  needs CF/self-host: {len(missing)} ===")
print(f"=== pruned {pruned} stale, {dupes} duplicate ===")
print("--- not on Modrinth (handle via `add_hosted_mods.py` from the GitHub Release, or CurseForge): ---")
for m in missing:
    print("   ", m)
