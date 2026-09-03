"""Build a SLIM ("referenced") mrpack for testing — same pack, ~10% of the size.

    py scripts/build_slim_mrpack.py

The release mrpack from build_mrpack.py is deliberately SELF-CONTAINED: every jar is bundled and
`files` is empty, so a player on limited data downloads once and nothing is fetched at launch. That
is the right call for shipping and the wrong one for handing a build to testers over Discord.

This produces the normal Modrinth layout instead: every mod that lives on the Modrinth CDN becomes a
`files[]` entry the launcher downloads, and only what CANNOT be referenced stays bundled under
overrides/.

  bundled  the 16 self-hosted jars (our own mods + hand-modified/GitHub-hosted ones). CoffeesAeroCore
           in particular MUST stay bundled: its URL points at the v1.10.0 release tag, which does not
           exist until the release is cut, so a referenced Core would 404 for every tester.
  bundled  .analogaudio, resourcepacks, shaderpacks, config. The Analog Audio runtime is bundled on
           purpose so players never hit its first-launch download prompt — do not "optimise" it out.
  files[]  everything on cdn.modrinth.com, with sha1 + sha512 + size computed from the LOCAL jar we
           already ship, so the hashes describe the exact bytes this pack was verified against.

Requires Modrinth App / Prism / ATLauncher (anything that understands `files[]`). Not for the
CurseForge channel and not what the in-client updater consumes.
"""
import hashlib, json, os, re, zipfile

ROOT = r"D:\MC Project\untitled"
OVERRIDES = os.path.join(ROOT, "overrides")
MODS_INDEX = os.path.join(ROOT, "mods")
RELEASES = r"D:\MC Project\Releases"

# Kept in step with build_mrpack.py -- read from pack.toml so it cannot drift.
VERSION = re.search(r'^version\s*=\s*"(.+?)"',
                    open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read(),
                    re.M).group(1)
OUT = os.path.join(RELEASES, f"CoffeesAeroSMP-{VERSION}-SLIM.mrpack")


def hashes(path):
    s1, s5 = hashlib.sha1(), hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            s1.update(chunk)
            s5.update(chunk)
    return s1.hexdigest(), s5.hexdigest(), os.path.getsize(path)


files, bundled, modified = [], [], []
for name in sorted(os.listdir(MODS_INDEX)):
    if not name.endswith(".pw.toml"):
        continue
    txt = open(os.path.join(MODS_INDEX, name), encoding="utf-8").read()
    jar = re.search(r'^filename\s*=\s*"(.+?)"', txt, re.M).group(1)
    url = re.search(r'^url\s*=\s*"(.+?)"', txt, re.M)
    side = re.search(r'^side\s*=\s*"(.+?)"', txt, re.M)
    side = side.group(1) if side else "both"
    local = os.path.join(OVERRIDES, "mods", jar)
    if not os.path.exists(local):
        raise SystemExit(f"index lists {jar} but it is not bundled -- run packwiz refresh first")

    if not url or "cdn.modrinth.com" not in url.group(1):
        bundled.append(jar)                       # self-hosted: keep the bytes
        continue

    sha1, sha512, size = hashes(local)

    # 🔑 A Modrinth URL does NOT prove the bundled jar is the Modrinth jar. Two mods in this pack
    # (GlitchCore, Serene Seasons) are hand-modified: the index records the stock upstream hash while
    # overrides/ ships different bytes. Referencing those would tell the launcher to fetch the stock
    # file, and the tester would silently run a build this pack was never verified against — or fail
    # the hash check outright, since the hashes here are computed from the LOCAL file.
    # So: reference only when the local bytes really are the upstream bytes. Otherwise bundle.
    recorded = re.search(r'^hash\s*=\s*"(.+?)"', txt, re.M)
    fmt = re.search(r'^hash-format\s*=\s*"(.+?)"', txt, re.M)
    if recorded and fmt:
        local_hash = sha512 if fmt.group(1) == "sha512" else \
            hashlib.new(fmt.group(1), open(local, "rb").read()).hexdigest()
        if local_hash != recorded.group(1):
            modified.append(jar)
            bundled.append(jar)
            continue
    files.append({
        "path": f"mods/{jar}",
        "hashes": {"sha1": sha1, "sha512": sha512},
        "env": {
            "client": "required",
            # `side = client` means the server does not want it; everything else ships to both.
            "server": "unsupported" if side == "client" else "required",
        },
        "downloads": [url.group(1)],
        "fileSize": size,
    })

index = {
    "formatVersion": 1,
    "game": "minecraft",
    "versionId": VERSION,
    "name": "Coffees Aero SMP",
    "summary": "Testing build - mods referenced from Modrinth, not bundled.",
    "files": files,
    "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.244"},
}

skip_mods = {f["path"].split("/", 1)[1] for f in files}
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index, indent=2))
    for dirpath, _, filenames in os.walk(OVERRIDES):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, OVERRIDES).replace("\\", "/")
            # Everything referenced above is downloaded by the launcher, so shipping it again
            # would defeat the whole point -- and a bundled copy WINS over a files[] entry.
            if rel.startswith("mods/") and fn in skip_mods:
                continue
            z.write(full, "overrides/" + rel)

print(f"version:  {VERSION}")
print(f"referenced from Modrinth: {len(files)} mods")
print(f"bundled (not on the CDN):  {len(bundled)} mods")
if modified:
    print(f"  ...of which HAND-MODIFIED (index hash != local bytes): {', '.join(modified)}")
print(f"output:   {OUT}")
print(f"size:     {os.path.getsize(OUT)/1024/1024:.1f} MB")
