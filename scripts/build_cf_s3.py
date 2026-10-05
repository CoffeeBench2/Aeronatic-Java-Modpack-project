#!/usr/bin/env python3
r"""
Season 3 CurseForge store zip, built straight from a Modrinth App profile (not packwiz).

    py scripts/build_cf_s3.py [--version 1.0.0] [--resolve-only]

Every jar / resourcepack / shaderpack is fingerprinted and REFERENCED when CurseForge hosts the exact
bytes. Anything left is looked up by its jar-declared identity: if CF hosts a file with the SAME
FILENAME it is referenced too (bundling it is what gets the pack rejected — see memory
cf-bundling-rejection-rule). Only what CF does not host at all is bundled into overrides/.

Shaders ship OFF (`enableShaders=false` in config/iris.properties is asserted, not assumed).
Output: D:\MC Project\Releases\CoffeesAeroSMP-S3-<ver>-CURSEFORGE.zip + a resolution report.
"""
import argparse, glob, io, json, os, re, sys, urllib.parse, urllib.request, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cf_fingerprint import cf_fingerprint, cf_match  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEY = open(os.path.join(ROOT, ".cf-key")).read().strip()
PROFILE = r"C:\Users\disan\AppData\Roaming\ModrinthApp\profiles\Coffees AeroSmp Season 3 v1.0-FULL"
RELEASES = r"D:\MC Project\Releases"
MC, LOADER = "1.21.1", "neoforge-21.1.251"
NAME, AUTHOR = "Coffee's AeroSMP Season 3", "MrCoffeeBench"

CONTENT_DIRS = ["mods", "resourcepacks", "shaderpacks"]
# Profile state that ships. Everything else (saves, xaero, DH data, logs, replays, caches) stays home.
OVERRIDE_DIRS = ["config", "defaultconfigs"]
OVERRIDE_FILES = ["options.txt"]
# resourceful-config-web.json carries a per-install generated password; ias.json is an orphan of a
# mod not in the pack. Neither should ship. servers.dat is left out: the profile's entry points at the
# S3 staff-test backend directly, which stops working once S3 goes behind the gate.
OVERRIDE_EXCLUDE = {"config/resourceful-config-web.json", "config/ias.json"}
SKIP_SUFFIX = (".txt",)
GENERIC_IDS = {"neoforge", "minecraft", "create", "sable", "curios", "emi", "jade", "sodium"}
# CF hosts these projects but not our version; reference CF's nearest build instead of bundling a
# copy of a CF-hosted mod. (projectID, fileID), checked 2026-10-05.
# Removed from the CF zip entirely (not referenced, not bundled): ARR and not on CurseForge. Core's
# MissingModsScreen sends the player to install it by hand, the same route Analog Audio takes on S2.
# The S3 Core config below MUST list each of these in manualMods, or the player gets no help at all.
STRIP_PREFIXES = ("SSRD-",)
# Added to the profile's mods: our -cf Core (no updater) carries the title screen + the prompt. It is
# hosted on CF project 1601629, so it is REFERENCED like any other mod; bundling a CF-hosted jar gets the
# pack rejected. Upload each new -cf Core to 1601629 and wait for approval before building.
CORE_DIR = os.path.join(RELEASES, "cf-core-noupdater")
EXTRA_OVERRIDES = os.path.join(ROOT, "scripts", "s3-cf-overrides")

# Jars with no declared identity to search by (language providers): project ids to check by filename.
PROJECT_HINTS = {"kotlinforforge-": 351264}
SUBSTITUTE = {
    "emiffect-neoforge-2.1.6+mc1.21.1.jar": (735528, None),  # newest 1.21.1 neoforge file on CF
}  # e.g. the Bliss licence .txt beside the zip is bundled as-is, not resolved


def api(url, data=None):
    rq = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None,
                                headers={"x-api-key": KEY, "Content-Type": "application/json",
                                         "Accept": "application/json"})
    with urllib.request.urlopen(rq, timeout=60) as r:
        return json.loads(r.read())


def jar_identity(path):
    names, ids = [], []
    try:
        z = zipfile.ZipFile(path)
    except Exception:
        return names, ids
    for meta in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json"):
        try:
            txt = z.read(meta).decode("utf-8", errors="replace")
        except KeyError:
            continue
        if meta.endswith(".json"):
            try:
                j = json.loads(txt)
                names += [j["name"]] if j.get("name") else []
                ids += [j["id"]] if j.get("id") else []
            except Exception:
                pass
        else:
            names += re.findall(r'(?m)^\s*displayName\s*=\s*"([^"]+)"', txt)
            ids += re.findall(r'(?m)^\s*modId\s*=\s*"([^"]+)"', txt)
    return names, ids


def search(**kw):
    # classId=6 = mods. Without it the results are mostly MODPACKS and the real project falls off
    # the page: Corpse, Create: Connected and Kotlin for Forge were all missed that way.
    kw.update(gameId=432, classId=6)
    try:
        return api("https://api.curseforge.com/v1/mods/search?" + urllib.parse.urlencode(kw)).get("data", [])
    except Exception:
        return []


def same_name_file(projects, fname):
    """Enumerate EVERY file page of each candidate project for an exact filename match."""
    for p in projects:
        index = 0
        while index <= 2000:
            try:
                data = api("https://api.curseforge.com/v1/mods/%d/files?pageSize=50&index=%d"
                           % (p["id"], index)).get("data", [])
            except Exception:
                break
            for f in data:
                if f.get("fileName") == fname:
                    return p, f
            if len(data) < 50:
                break
            index += 50
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="3.0.0")
    ap.add_argument("--resolve-only", action="store_true")
    a = ap.parse_args()

    iris = open(os.path.join(PROFILE, "config", "iris.properties"), encoding="utf-8").read()
    if not re.search(r"(?m)^enableShaders=false\s*$", iris):
        sys.exit("ERROR: config/iris.properties does not have enableShaders=false")
    opts = open(os.path.join(PROFILE, "options.txt"), "rb").read()
    if opts.startswith(b"\xef\xbb\xbf"):
        sys.exit("ERROR: options.txt has a UTF-8 BOM (memory bom-breaks-shipped-configs)")

    core_cfg = open(os.path.join(EXTRA_OVERRIDES, "config", "coffeesaerosmp_core-client.toml"), encoding="utf-8").read()
    for pre in STRIP_PREFIXES:
        if '"%s|' % pre.rstrip("-").lower() not in core_cfg:
            sys.exit("ERROR: %s is stripped but not listed in manualMods" % pre)
    cores = sorted(glob.glob(os.path.join(CORE_DIR, "CoffeesAeroCore-*-cf.jar")), key=os.path.getmtime)
    if not cores:
        sys.exit("ERROR: no -cf Core in " + CORE_DIR)
    core = cores[-1]
    print("core:", os.path.basename(core))

    files = []  # (dir, name, path)
    for d in CONTENT_DIRS:
        for n in sorted(os.listdir(os.path.join(PROFILE, d))):
            p = os.path.join(PROFILE, d, n)
            if d == "mods" and n.startswith(STRIP_PREFIXES):
                print("STRIPPED     mods         ", n)
                continue
            if os.path.isfile(p) and not n.endswith(SKIP_SUFFIX) and not n.endswith(".disabled"):
                files.append((d, n, p))
    files.append(("mods", os.path.basename(core), core))
    fps ={cf_fingerprint(p): (d, n, p) for d, n, p in files}
    if len(fps) != len(files):
        sys.exit("ERROR: duplicate fingerprints in the profile (same file twice?)")
    matched = cf_match(list(fps), KEY)

    refs, bundled, report = {}, [], []
    for fp, (d, n, p) in fps.items():
        if fp in matched:
            refs[(d, n)] = matched[fp]
            report.append(("REF-exact", d, n, matched[fp]))
    for fp, (d, n, p) in fps.items():
        if (d, n) in refs:
            continue
        names, ids = jar_identity(p) if n.endswith(".jar") else ([], [])
        stem = re.split(r"[-_ +]\d", n)[0]
        terms = list(dict.fromkeys(names + [i for i in ids if i not in GENERIC_IDS] + [stem]))
        projects = {}
        for t in terms:
            for pr in search(searchFilter=t, pageSize=20):
                projects[pr["id"]] = pr
            for pr in search(slug=re.sub(r"[^a-z0-9]+", "-", t.lower()).strip("-")):
                projects[pr["id"]] = pr
        for prefix, pid in PROJECT_HINTS.items():
            if n.startswith(prefix):
                projects[pid] = api("https://api.curseforge.com/v1/mods/%d" % pid)["data"]
        pr, f = same_name_file(list(projects.values()), n)
        if not f and n in SUBSTITUTE:
            pid = SUBSTITUTE[n][0]
            pr = api("https://api.curseforge.com/v1/mods/%d" % pid)["data"]
            cands = [x for x in api("https://api.curseforge.com/v1/mods/%d/files?gameVersion=%s&modLoaderType=6"
                                    % (pid, MC))["data"] if x.get("isAvailable")]
            f = max(cands, key=lambda x: x["fileDate"]) if cands else None
            if f:
                report.append(("REF-subst", d, n, (pid, f["id"])))
                refs[(d, n)] = (pid, f["id"])
                continue
        if f:
            refs[(d, n)] = (pr["id"], f["id"])
            report.append(("REF-samename", d, n, (pr["id"], f["id"])))
        else:
            located = ", ".join(sorted(x.get("slug", "?") for x in projects.values())[:6]) or "NO PROJECT LOCATED"
            bundled.append((d, n, p))
            report.append(("BUNDLE", d, n, located))

    # Every reference must be downloadable, or CF auto-rejects the manifest.
    ids = sorted({fid for _, fid in refs.values()})
    info = {}
    for i in range(0, len(ids), 100):
        for f in api("https://api.curseforge.com/v1/mods/files", {"fileIds": ids[i:i + 100]})["data"]:
            info[f["id"]] = f
    bad = [(k, v) for k, v in refs.items() if not info.get(v[1], {}).get("isAvailable")
           or info[v[1]].get("fileStatus") != 4]
    proj = {}
    pids = sorted({pid for pid, _ in refs.values()})
    for i in range(0, len(pids), 100):
        for m in api("https://api.curseforge.com/v1/mods", {"modIds": pids[i:i + 100]})["data"]:
            proj[m["id"]] = m

    lines = []
    for kind, d, n, x in sorted(report, key=lambda r: (r[0], r[1], r[2].lower())):
        extra = x if kind == "BUNDLE" else "%s (proj %d, file %d)" % (proj.get(x[0], {}).get("slug", "?"), x[0], x[1])
        lines.append("%-12s %-13s %-60s %s" % (kind, d, n, extra))
    rep = "\n".join(lines)
    print(rep)
    print("\nrefs=%d bundled=%d total=%d  unavailable_refs=%d" % (len(refs), len(bundled), len(files), len(bad)))
    for k, v in bad:
        print("  UNAVAILABLE:", k, v)
    os.makedirs(RELEASES, exist_ok=True)
    open(os.path.join(RELEASES, "CoffeesAeroSMP-S3-%s-cf-resolution.txt" % a.version), "w",
         encoding="utf-8").write(rep + "\n")
    if bad:
        sys.exit("ERROR: unavailable references")
    if ("mods", os.path.basename(core)) not in refs:
        sys.exit("ERROR: %s is not an approved CF file yet (project 1601629) -- refusing to bundle it"
                 % os.path.basename(core))
    if len(refs) + len(bundled) != len(files):
        sys.exit("ERROR: coverage gap")
    if a.resolve_only:
        return

    manifest = {
        "minecraft": {"version": MC, "modLoaders": [{"id": LOADER, "primary": True}]},
        "manifestType": "minecraftModpack", "manifestVersion": 1,
        "name": NAME, "version": a.version, "author": AUTHOR, "overrides": "overrides",
        "files": [{"projectID": pid, "fileID": fid, "required": True}
                  for (d, n), (pid, fid) in sorted(refs.items())],
    }
    out = os.path.join(RELEASES, "CoffeesAeroSMP-S3-%s-CURSEFORGE.zip" % a.version)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for d, n, p in bundled:
            z.write(p, "overrides/%s/%s" % (d, n))
        for dp, _, fns in os.walk(EXTRA_OVERRIDES):
            for fn in fns:
                fp = os.path.join(dp, fn)
                z.write(fp, "overrides/" + os.path.relpath(fp, EXTRA_OVERRIDES).replace(os.sep, "/"))
        for d in CONTENT_DIRS:  # side files like the shader licence .txt
            for n in os.listdir(os.path.join(PROFILE, d)):
                if n.endswith(SKIP_SUFFIX):
                    z.write(os.path.join(PROFILE, d, n), "overrides/%s/%s" % (d, n))
        for d in OVERRIDE_DIRS:
            base = os.path.join(PROFILE, d)
            for dp, _, fns in os.walk(base):
                for fn in fns:
                    fp = os.path.join(dp, fn)
                    if os.path.relpath(fp, PROFILE).replace(os.sep, "/") in OVERRIDE_EXCLUDE:
                        continue
                    z.write(fp, "overrides/" + os.path.relpath(fp, PROFILE).replace(os.sep, "/"))
        for f in OVERRIDE_FILES:
            z.write(os.path.join(PROFILE, f), "overrides/" + f)
    print("wrote", out, "%.1f MB" % (os.path.getsize(out) / 1e6))


if __name__ == "__main__":
    main()
