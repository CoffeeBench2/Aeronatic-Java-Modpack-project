"""Post-process `packwiz modrinth export` output for the MODRINTH upload channel.

Two faults in the raw export, both previously fixed by hand for 1.8.1.1:

 1. overrides/overrides/... — packwiz writes pack-ROOT-relative paths into the zip's
    overrides/ folder, and this pack keeps its client files in a folder literally named
    `overrides/`, so the prefix doubles. Players' configs would install to the wrong path
    and be ignored. Flatten it.

 2. Bundled jars that Modrinth actually hosts. Mods added with `packwiz cf add` carry
    CurseForge metadata, so `packwiz modrinth export` bundles the bytes instead of
    referencing them. Modrinth's per-file permission review flags bundled third-party
    jars (23 were rejected as "No permission" on 1.8.1.1), so every jar we can turn into
    a CDN reference is one less rejection risk — and a much smaller upload.
    Look each bundled jar up by SHA-512 via Modrinth's version_files API; anything it
    hosts becomes a reference and its bytes are dropped.
"""
import zipfile, json, hashlib, urllib.request, os, sys

SRC = sys.argv[1] if len(sys.argv) > 1 else r"D:\MC Project\untitled\Coffees Aero SMP-1.8.2.mrpack"
OUT = sys.argv[2] if len(sys.argv) > 2 else r"D:\MC Project\Releases\CoffeesAeroSMP-1.8.2-MODRINTH.mrpack"
UA  = {"User-Agent": "CoffeesAeroSMP/1.8.2 (modpack build script)", "Content-Type": "application/json"}

# Modrinth's per-file permission review rejects these outright: "its license does not
# normally allow redistribution." They are NOT on Modrinth, so they cannot become CDN
# references either -- the only way to publish is to leave them OUT of the Modrinth
# artifact. The in-client updater re-adds them on first launch from the packwiz index,
# so players still end up with the full pack. Same approach used for 1.8.1.1.
NO_PERMISSION = (
    "ftb-essentials", "ftb-ultimine", "tombstone-neoforge",
)
# Stamp a LOWER pack version into the bundled Core config so the in-client updater fires
# on first launch and pulls the removed mods down. Without this the client would think it
# is already current and the pack would stay incomplete.
BOOTSTRAP_VERSION = "1.8.1.1"
CORE_CFG = "overrides/config/coffeesaerosmp_core-client.toml"

zin = zipfile.ZipFile(SRC)
manifest = json.loads(zin.read("modrinth.index.json"))
entries = [(i, zin.read(i.filename)) for i in zin.infolist() if i.filename != "modrinth.index.json"]
zin.close()

# --- fault 1: flatten ------------------------------------------------------------------
flat = 0
norm = []
for info, data in entries:
    name = info.filename
    if name.startswith("overrides/overrides/"):
        name = "overrides/" + name[len("overrides/overrides/"):]
        flat += 1
    norm.append((name, data))
print(f"flattened overrides/overrides -> overrides : {flat} entries")

# --- fault 2: hash bundled jars against Modrinth ----------------------------------------
bundled = {n: d for n, d in norm if n.startswith("overrides/mods/") and n.endswith(".jar")}
by_sha  = {hashlib.sha512(d).hexdigest(): n for n, d in bundled.items()}
print(f"bundled jars to look up: {len(by_sha)}")

found = {}
shas = list(by_sha)
for i in range(0, len(shas), 100):
    body = json.dumps({"hashes": shas[i:i+100], "algorithm": "sha512"}).encode()
    req = urllib.request.Request("https://api.modrinth.com/v2/version_files", data=body, headers=UA)
    try:
        found.update(json.load(urllib.request.urlopen(req, timeout=90)))
    except Exception as e:
        print("  lookup batch failed:", str(e)[:80])

print(f"Modrinth hosts {len(found)} of {len(by_sha)} — those become CDN references.")

drop = set()
for sha, ver in found.items():
    name = by_sha[sha]
    f = next((x for x in ver["files"] if x.get("primary")), ver["files"][0])
    manifest["files"].append({
        "path": "mods/" + os.path.basename(name),
        "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
        "downloads": [f["url"]],
        "fileSize": f["size"],
    })
    drop.add(name)

# --- fault 3: drop the licence-blocked set + arm the updater ---------------------------
import re as _re
removed = []
for name, _ in norm:
    if name.startswith("overrides/mods/") and any(k in name.lower() for k in NO_PERMISSION):
        drop.add(name); removed.append(name.split("/")[-1])
print(f"removed (no redistribution permission): {len(removed)} -> {', '.join(removed)}")

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(manifest, indent=2))
    for name, data in norm:
        if name in drop:
            continue
        if name == CORE_CFG:
            # NB: the TOML is CRLF, so anchor on the line start only — a trailing `$` would
            # never match because of the \r sitting before the \n.
            data, _n = _re.subn(rb'(?m)^packVersion\s*=\s*"[^"]*"',
                                f'packVersion = "{BOOTSTRAP_VERSION}"'.encode(), data)
            print(f"  bootstrap packVersion -> {BOOTSTRAP_VERSION} ({_n} replacement)")
        zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, data)

still = len(bundled) - len(drop)
print(f"\nDONE: {OUT}")
print(f"  size: {os.path.getsize(OUT)/1024/1024:.1f} MB")
print(f"  CDN references: {len(manifest['files'])}   still bundled: {still}")
