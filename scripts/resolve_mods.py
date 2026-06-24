"""Add a curated set of Create addons (+ FTB stack) and auto-resolve their required
dependencies by pulling missing libs from the 'All of Create - Aeronautics' pack
(same MC 1.21.1 + Create 6.0.10, so drop-in compatible). Jarjar-aware so we never
duplicate a mod we already bundle inside another jar."""
import zipfile, os, re, shutil, io

DEST = r"D:\MC Project\untitled\overrides\mods"
SOURCE = r"C:\Users\disan\curseforge\minecraft\Instances\All of Create - Aeronautics\mods"
DOWNLOADS = os.path.expanduser(r"~\Downloads")

FTB = [
    "ftb-library-neoforge-2101.1.32.jar",
    "ftb-teams-neoforge-2101.1.10.jar",
    "ftb-chunks-neoforge-2101.1.19.jar",
    "ftb-quests-neoforge-2101.1.27.jar",
]
CURATED = [  # from SOURCE
    "aeroclaims-0.8.5.jar",                                  # airship protection (the "ship protection" ask)
    "integrated_villages-1.3.2+1.21.1-neoforge.jar",         # village generation
    "create_aquatic_ambitions-1.21.1-2.0.2.jar",
    "createframed-1.21.1-1.7.3.jar",
    "bellsandwhistles-0.4.7-1.21.1.jar",                     # rail/Create decoration
    "Design-n-Decor-1.21.1-2.1.0.jar",
    "create_connected-1.1.16-mc1.21.1.jar",
    "createdieselgenerators-1.21.1-1.3.11.jar",
    "bits_n_bobs-0.0.44.jar",
    "createrailwaysnavigator-neoforge-1.21.1-beta-0.9.0-C6.jar",  # train routing UI (rail theme)
    "extra_create_recipes_1.21.1_v1.jar",
]
IGNORE = {"minecraft", "neoforge", "forge", "fml", "java"}
TOML_NAMES = ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")

def read_toml(zf):
    for n in TOML_NAMES:
        try:
            return zf.read(n).decode("utf-8", "ignore")
        except KeyError:
            continue
    return ""

def own_id(toml):
    m = re.search(r'\[\[mods\]\].*?modId\s*=\s*"([^"]+)"', toml, re.S)
    return m.group(1) if m else None

def req_deps(toml):
    deps = set()
    for blk in re.split(r'\[\[dependencies\.[^\]]+\]\]', toml)[1:]:
        blk = re.split(r'\n\[\[', blk)[0]
        mid = re.search(r'modId\s*=\s*"([^"]+)"', blk)
        typ = re.search(r'type\s*=\s*"?(\w+)', blk)
        if mid and (not typ or typ.group(1).lower() == "required"):
            deps.add(mid.group(1))
    return deps

def ids_in_jar(path):
    ids = set()
    try:
        with zipfile.ZipFile(path) as z:
            oid = own_id(read_toml(z))
            if oid:
                ids.add(oid)
            for e in z.namelist():
                if e.startswith("META-INF/jarjar/") and e.endswith(".jar"):
                    try:
                        with zipfile.ZipFile(io.BytesIO(z.read(e))) as jz:
                            jid = own_id(read_toml(jz))
                            if jid:
                                ids.add(jid)
                    except Exception:
                        pass
    except Exception:
        pass
    return ids

def index(folder):
    idx = {}
    for f in os.listdir(folder):
        if f.endswith(".jar"):
            for i in ids_in_jar(os.path.join(folder, f)):
                idx.setdefault(i, os.path.join(folder, f))
    return idx

installed = set(index(DEST).keys())
src_idx = index(SOURCE)
added = []

def add(path):
    dst = os.path.join(DEST, os.path.basename(path))
    if not os.path.exists(dst):
        shutil.copy2(path, dst)
        installed.update(ids_in_jar(path))
        added.append(os.path.basename(path))

for f in FTB:
    p = os.path.join(DOWNLOADS, f)
    add(p) if os.path.exists(p) else print("MISSING FTB:", f)
for f in CURATED:
    p = os.path.join(SOURCE, f)
    add(p) if os.path.exists(p) else print("MISSING PICK:", f)

unresolved = set()
changed = True
while changed:
    changed = False
    for f in [x for x in os.listdir(DEST) if x.endswith(".jar")]:
        with zipfile.ZipFile(os.path.join(DEST, f)) as z:
            t = read_toml(z)
        for d in req_deps(t):
            if d in IGNORE or d in installed:
                continue
            if d in src_idx:
                add(src_idx[d]); changed = True
            else:
                unresolved.add(d)

print("=== ADDED ({}) ===".format(len(added)))
for a in sorted(added):
    print("  +", a)
print("=== UNRESOLVED required deps (need manual download) ===")
left = sorted(d for d in unresolved if d not in installed)
print("  none" if not left else "\n".join("  ! " + d for d in left))
print("total mods now:", len([x for x in os.listdir(DEST) if x.endswith(".jar")]))
