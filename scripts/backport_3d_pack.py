"""Backport 'Actually 3D Stuff' (1.21.9/1.21.11, item-model system) to 1.21.1.

The pack uses the 1.21.4+ assets/<ns>/items/<name>.json model-definition system,
which 1.21.1 does not read. Each definition redirects (often via a display_context
select) to a *_a3ds 3D model that already lives under assets/<ns>/models/item/.

This converts every redirect into a 1.21.1 vanilla item-model override
assets/<ns>/models/item/<name>.json = {"parent": "<ns>:item/<name>_a3ds"} so the
3D models render in 1.21.1, sets pack_format to 34, and strips the unusable
overlays/items defs/holdmyitems lua.
"""
import zipfile, json, re, os

SRC = os.path.expanduser(r"~\Downloads\Actually 3D Stuff.zip")
DST = r"D:\MC Project\untitled\overrides\resourcepacks\Actually 3D Stuff.zip"

A3DS = re.compile(r'"([a-z0-9_]+:item/[a-z0-9_/]*a3ds[a-z0-9_/]*)"')

zin = zipfile.ZipFile(SRC, "r")
names = zin.namelist()

# Existing base (non-overlay) item-model overrides we must not clobber.
existing = set()
for n in names:
    m = re.match(r"^assets/([^/]+)/models/item/(.+)\.json$", n)
    if m:
        existing.add((m.group(1), m.group(2)))

generated = {}
scanned = 0
for n in names:
    m = re.match(r"^assets/([^/]+)/items/(.+)\.json$", n)
    if not m:
        continue
    scanned += 1
    ns, name = m.group(1), m.group(2)
    raw = zin.read(n).decode("utf-8", "replace")
    refs = A3DS.findall(raw)
    if not refs:
        continue
    ref = refs[-1]                       # fallback (held) model is normally last
    if (ns, name) in existing:
        continue
    generated[f"assets/{ns}/models/item/{name}.json"] = \
        json.dumps({"parent": ref}, indent=2).encode("utf-8")

mcmeta = {
    "pack": {
        "pack_format": 34,
        "description": "Actually 3D Stuff (1.21.1 backport) - By Arch1vways"
    }
}

zout = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
written = set()
for n in names:
    if re.match(r"^1\.21\.\d+/", n):                # drop version overlays
        continue
    if re.match(r"^assets/[^/]+/items/", n):        # drop new-system item defs
        continue
    if re.match(r"^assets/[^/]+/shaders/", n):      # drop 1.21.9 core shaders (crash 1.21.1 reload)
        continue
    if "holdmyitems" in n:                          # drop HoldMyItems lua (mod not present)
        continue
    if n == "pack.mcmeta" or n.endswith("/"):
        continue
    zout.writestr(n, zin.read(n))
    written.add(n)

for path, data in generated.items():
    if path not in written:
        zout.writestr(path, data)

zout.writestr("pack.mcmeta", json.dumps(mcmeta, indent=2))
zout.close()
zin.close()
print(f"item defs scanned: {scanned}, 3D overrides generated: {len(generated)}")
print(f"output: {DST}")
