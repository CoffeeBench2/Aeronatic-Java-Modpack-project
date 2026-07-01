"""Build CoffeesAeroSMP-<version>.mrpack â€” self-contained (files:[], everything bundled
as overrides/). Mirrors the proven 1.2.0 layout: config, mods, resourcepacks,
shaderpacks, options.txt. Skips empty/world-scoped datapacks."""
import zipfile, json, os

ROOT = r"D:\MC Project\untitled"
OVERRIDES = os.path.join(ROOT, "overrides")
VERSION = "1.6.4"
OUT = os.path.expanduser(rf"~\Downloads\CoffeesAeroSMP-{VERSION}.mrpack")

# Stamp the version into the CoffeesAeroCore client config so the in-game version check
# and the pack are always built in lockstep (no hand-editing).
import re as _re
_CORE_CFG = os.path.join(OVERRIDES, "config", "coffeesaerosmp_core-client.toml")
if os.path.isfile(_CORE_CFG):
    with open(_CORE_CFG, "r", encoding="utf-8") as fh:
        _cfg = fh.read()
    _new = _re.sub(r'(?m)^packVersion\s*=\s*".*"$', f'packVersion = "{VERSION}"', _cfg)
    if _new != _cfg:
        with open(_CORE_CFG, "w", encoding="utf-8") as fh:
            fh.write(_new)
        print(f"stamped packVersion = {VERSION} into coffeesaerosmp_core-client.toml")

# .analogaudio holds the bundled analogplayer-1.0.2.jar (Lavaplayer runtime) so Analog
# Audio's first-launch download prompt never fires and audio works offline. isInstalled()
# checks <gamedir>/.analogaudio/internal/analogplayer-1.0.2.jar â€” keep this dir bundled.
INCLUDE_DIRS = {"config", "mods", "resourcepacks", "shaderpacks", ".analogaudio"}
INCLUDE_FILES = {"options.txt"}

manifest = {
    "formatVersion": 1,
    "game": "minecraft",
    "versionId": VERSION,
    "name": "Coffees Aero SMP",
    "summary": "Create: Aeronautics SMP modpack by MrCoffeeBench",
    "files": [],
    "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.234"},
}

def _add(zf, full, rel):
    # Fixed timestamp: deterministic + avoids "ZIP does not support timestamps before 1980".
    zi = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    with open(full, "rb") as fh:
        zf.writestr(zi, fh.read())

z = zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED)
z.writestr("modrinth.index.json", json.dumps(manifest, indent=2))

count = 0
for top in sorted(INCLUDE_DIRS):
    base = os.path.join(OVERRIDES, top)
    if not os.path.isdir(base):
        continue
    for dirpath, _dirs, files in os.walk(base):
        for fn in files:
            full = os.path.join(dirpath, fn)
            rel = "overrides/" + os.path.relpath(full, OVERRIDES).replace("\\", "/")
            _add(z, full, rel)
            count += 1
for fn in sorted(INCLUDE_FILES):
    full = os.path.join(OVERRIDES, fn)
    if os.path.isfile(full):
        _add(z, full, f"overrides/{fn}")
        count += 1

z.close()
mods = len([f for f in os.listdir(os.path.join(OVERRIDES, "mods")) if f.endswith(".jar")])
print(f"version: {VERSION}")
print(f"files bundled: {count} (mods: {mods})")
print(f"output: {OUT}")
print(f"size: {os.path.getsize(OUT)/1024/1024:.1f} MB")
