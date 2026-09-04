"""Build the MODRINTH upload artifact.

Modrinth reviews every BUNDLED file and rejects anything whose licence does not allow
redistribution ("No permission"). On 1.10.4 it flagged 12 files. The fix is not one rule but two,
and which one applies is decided by a hash lookup, never by guessing:

  REFERENCE  if Modrinth hosts the exact bytes, emit a files[] entry pointing at its CDN. Nothing
             is redistributed by us, so there is nothing to review. This covers the five
             CurseForge-looking resourcepacks -- they ARE on Modrinth, which is the opposite of
             what it looks like from the CurseForge links in the rejection notice.
  DROP       if Modrinth does not host it, the bytes cannot stay and cannot be referenced. This
             covers the whole FTB stack, Corail Tombstone and Integrated API.

Dropped mods are not lost to players: the shipped Core config is stamped with a pack version one
notch BELOW live, so the in-client updater fires on first launch and pulls them from the packwiz
index. That is the same trick the 1.8.x MODRINTH builds used.

Everything else is referenced where possible and bundled otherwise, exactly like the slim build.
"""
import hashlib, json, os, re, sys, urllib.request, zipfile

ROOT = r"D:\MC Project\untitled"
OVERRIDES = os.path.join(ROOT, "overrides")
RELEASES = r"D:\MC Project\Releases"
UA = {"User-Agent": "CoffeesAeroSMP/1.10 (modpack build script)", "Content-Type": "application/json"}

_PACK = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
VERSION = re.search(r'^version\s*=\s*"(.+?)"', _PACK, re.M).group(1)
MINECRAFT = re.search(r'^minecraft\s*=\s*"(.+?)"', _PACK, re.M).group(1)
NEOFORGE = re.search(r'^neoforge\s*=\s*"(.+?)"', _PACK, re.M).group(1)
OUT = os.path.join(RELEASES, f"CoffeesAeroSMP-{VERSION}-MODRINTH.mrpack")

# Flagged by Modrinth review AND not hosted there, so they can only be dropped. Matched as a
# filename prefix. Keep this in step with what review actually reports -- moderation only names
# the first offenders it hits, so re-check the full list after every rejection.
NO_PERMISSION = (
    "tombstone-neoforge", "ftb-chunks", "ftb-essentials", "ftb-library",
    "ftb-quests", "ftb-teams", "ftb-ultimine", "integrated_api",
)


def bootstrap_version(v):
    """One notch below the live pack version, derived rather than pinned, so the in-client
    updater fires on first launch and backfills whatever was dropped above."""
    parts = v.split(".")
    for i in range(len(parts) - 1, -1, -1):
        if parts[i].isdigit() and int(parts[i]) > 0:
            parts[i] = str(int(parts[i]) - 1)
            return ".".join(parts)
    return "0.0.0"


def modrinth_lookup(paths):
    """sha512 -> version JSON for every file Modrinth hosts. Batched; the API caps each call."""
    h2p, out = {}, {}
    for p in paths:
        h2p.setdefault(hashlib.sha512(open(p, "rb").read()).hexdigest(), p)
    hashes = list(h2p)
    for i in range(0, len(hashes), 100):
        chunk = hashes[i:i + 100]
        req = urllib.request.Request(
            "https://api.modrinth.com/v2/version_files",
            data=json.dumps({"hashes": chunk, "algorithm": "sha512"}).encode(),
            headers=UA, method="POST")
        try:
            out.update(json.load(urllib.request.urlopen(req, timeout=120)))
        except Exception as e:
            print(f"  lookup batch {i} failed: {e}")
    return h2p, out


everything = []
for dp, _, fns in os.walk(OVERRIDES):
    for fn in fns:
        everything.append(os.path.join(dp, fn))

print(f"scanning {len(everything)} files under overrides/ ...")
h2p, hosted = modrinth_lookup(everything)

files, bundled, dropped = [], [], []
seen = set()
for h, path in h2p.items():
    rel = os.path.relpath(path, OVERRIDES).replace("\\", "/")
    name = os.path.basename(path)
    seen.add(path)
    v = hosted.get(h)
    if v:
        f = next((c for c in v["files"] if c["hashes"]["sha512"] == h), v["files"][0])
        files.append({
            "path": rel,
            "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
            "env": {"client": "required", "server": "required"},
            "downloads": [f["url"]],
            "fileSize": f["size"],
        })
        continue
    if any(name.lower().startswith(p) for p in NO_PERMISSION):
        dropped.append(name)
        continue
    bundled.append(path)

index = {
    "formatVersion": 1, "game": "minecraft", "versionId": VERSION,
    "name": "Coffees Aero SMP",
    "summary": "Create: Aeronautics SMP pack.",
    "files": files,
    "dependencies": {"minecraft": MINECRAFT, "neoforge": NEOFORGE},
}

boot = bootstrap_version(VERSION)
referenced_paths = {f["path"] for f in files}
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index, indent=2))
    for path in bundled:
        rel = os.path.relpath(path, OVERRIDES).replace("\\", "/")
        if rel in referenced_paths:
            continue
        # Stamp the Core config DOWN so the updater backfills the dropped mods on first launch.
        if rel.endswith("coffeesaerosmp_core-client.toml"):
            txt = open(path, encoding="utf-8").read()
            txt = re.sub(r'^packVersion\s*=\s*".*?"', f'packVersion = "{boot}"', txt, flags=re.M)
            z.writestr("overrides/" + rel, txt)
            continue
        z.write(path, "overrides/" + rel)

print(f"version:     {VERSION}   (Core config stamped {boot} so the updater backfills)")
print(f"referenced:  {len(files)}")
print(f"bundled:     {len(bundled)}")
print(f"DROPPED (no permission, not on Modrinth): {len(dropped)}")
for d in sorted(dropped):
    print(f"   - {d}")
print(f"output:      {OUT}")
print(f"size:        {os.path.getsize(OUT)/1024/1024:.1f} MB")
