"""Pre-upload gate for the CurseForge store zip.

Two independent failure modes killed earlier uploads, and neither shows up by eyeballing the zip:

 1. A manifest reference that no longer resolves, or whose project has been made unavailable.
    The launcher cannot install the pack at all if a referenced file is gone.

 2. A BUNDLED jar whose exact filename CurseForge also hosts. That is the rule that got 1.9.4.1
    rejected -- `allowModDistribution` is NOT the test (GlitchCore reports True and was rejected
    anyway); the test is whether CF hosts a file with the same filename. Our jars come from
    Modrinth so they fingerprint differently and CF's automatic pass cannot catch them.

CF projects gain files over time, so a bundled jar that was collision-free at build time can
collide later. Re-run this immediately before every upload, not once per pack version.

Slug guessing produces FALSE SAFES (Explosive Enhancement lives at `explosivenhancement`, missing
an "e"), so this searches by name and enumerates EVERY file page, not just the first.

Usage: py scripts/cf_preflight.py <path-to-CURSEFORGE.zip>
"""
import io, json, os, re, sys, urllib.parse, urllib.request, zipfile

KEY = open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".cf-key")).read().strip()
GAME_ID = 432


def api(url, data=None):
    rq = urllib.request.Request(
        url,
        data=json.dumps(data).encode() if data else None,
        headers={"x-api-key": KEY, "Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(rq, timeout=60) as r:
        return json.loads(r.read())


def all_files(mod_id):
    """Every file page, not just the first -- concluding 'no collision' from page 1 is how a
    collision slips through."""
    out, index = [], 0
    while True:
        try:
            r = api("https://api.curseforge.com/v1/mods/%d/files?pageSize=50&index=%d" % (mod_id, index))
        except Exception:
            break
        data = r.get("data", [])
        out += data
        if len(data) < 50:
            break
        index += 50
        if index > 1000:
            break
    return out


def search(term):
    q = urllib.parse.urlencode({"gameId": GAME_ID, "searchFilter": term, "pageSize": 20})
    try:
        return api("https://api.curseforge.com/v1/mods/search?" + q).get("data", [])
    except Exception:
        return []


def by_slug(slug):
    q = urllib.parse.urlencode({"gameId": GAME_ID, "slug": slug})
    try:
        return api("https://api.curseforge.com/v1/mods/search?" + q).get("data", [])
    except Exception:
        return []


def jar_identity(zf, jar_path):
    """displayName + modId straight from the jar's own neoforge.mods.toml.

    Deriving a search term from the FILENAME is what produces false safes: a concatenated
    filename like `explosiveenhancement-2.0.1.jar` finds nothing, while the declared display
    name "Explosive Enhancement" finds the project immediately. Ask the jar, don't guess.
    """
    names, mod_ids = [], []
    try:
        with zf.open(jar_path) as fh:
            inner = zipfile.ZipFile(io.BytesIO(fh.read()))
    except Exception:
        return names, mod_ids
    for meta in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json"):
        try:
            txt = inner.read(meta).decode("utf-8", errors="replace")
        except KeyError:
            continue
        if meta.endswith(".json"):
            try:
                j = json.loads(txt)
                if j.get("name"):
                    names.append(j["name"])
                if j.get("id"):
                    mod_ids.append(j["id"])
            except Exception:
                pass
        else:
            names += re.findall(r'(?m)^\s*displayName\s*=\s*"([^"]+)"', txt)
            mod_ids += re.findall(r'(?m)^\s*modId\s*=\s*"([^"]+)"', txt)
    return names, mod_ids


MUST_BUNDLE = [
    "Analog-Audio-", "connector-", "forgified-fabric-api-", "kotlinforforge-",
    "kubejs-neoforge-", "rhino-", "GlitchCore-neoforge-",
]


def check_import(z, man, jars):
    """The import zip is NOT moderated, so the filename-collision rule does not apply to it --
    it deliberately bundles jars CF also hosts. What it must never ship without is the boot
    chain: it is imported directly and the in-client updater only runs after the title screen,
    so a profile missing its language provider can never repair itself.

    Collisions here are benign ONLY while our filename matches CF's exactly (the override then
    lands on the same path). If they ever diverge the profile gets two jars of one mod id and
    will not boot, so the divergence is reported.
    """
    fail = []
    print("[2] boot chain (import zip must ship these or the profile cannot start)")
    for pre in MUST_BUNDLE:
        hit = [j for j in jars if j.startswith(pre)]
        print("    %-6s %-26s %s" % ("ok" if hit else "MISS", pre, hit[0] if hit else "*** MISSING ***"))
        if not hit:
            fail.append("boot-chain jar %s* is missing from the import zip" % pre)
    print()

    print("[3] duplicate-install risk (bundled jar ALSO referenced in the manifest)")
    ids = [f["projectID"] for f in man["files"]]
    info = {}
    for i in range(0, len(ids), 200):
        for d in api("https://api.curseforge.com/v1/mods", {"modIds": ids[i:i + 200]})["data"]:
            info[d["id"]] = d
    fid_to_pid = {f["fileID"]: f["projectID"] for f in man["files"]}
    hosted = {}
    fids = list(fid_to_pid)
    for i in range(0, len(fids), 200):
        for d in api("https://api.curseforge.com/v1/mods/files", {"fileIds": fids[i:i + 200]})["data"]:
            hosted[d["fileName"]] = info.get(d["modId"], {}).get("name", d["modId"])
    dupes = 0
    for pre in MUST_BUNDLE:
        for j in [x for x in jars if x.startswith(pre)]:
            same = [h for h in hosted if h == j]
            ref_of_mod = [h for h in hosted if h.startswith(pre)]
            if same:
                print("    ok     %-46s CF hosts the SAME filename (overwrite, 1 jar)" % j)
            elif ref_of_mod:
                print("    !! %-46s bundled as %r but CF ref is %r -> TWO jars, will not boot"
                      % ("DIVERGED", j, ref_of_mod[0]))
                fail.append("%s diverges from the CF-referenced filename %s" % (j, ref_of_mod[0]))
                dupes += 1
            else:
                print("    ok     %-46s not referenced (bundled only)" % j)
    print()
    return fail


def main():
    path = sys.argv[1]
    z = zipfile.ZipFile(path)
    man = json.loads(z.read("manifest.json"))
    jars = sorted(n.split("/")[-1] for n in z.namelist() if n.lower().endswith(".jar"))

    print("zip      : %s (%.1f MB)" % (os.path.basename(path), os.path.getsize(path) / 1024 / 1024))
    print("version  : %s   minecraft %s / %s" % (
        man.get("version"), man["minecraft"]["version"],
        ",".join(l["id"] for l in man["minecraft"]["modLoaders"])))
    print("refs     : %d      bundled: %d" % (len(man["files"]), len(jars)))
    print()

    fail = []

    # --- 1. every reference resolves and is installable -------------------------
    ids = [f["projectID"] for f in man["files"]]
    info = {}
    for i in range(0, len(ids), 200):
        for d in api("https://api.curseforge.com/v1/mods", {"modIds": ids[i:i + 200]})["data"]:
            info[d["id"]] = d

    unresolved = [i for i in ids if i not in info]
    unavailable = [(i, info[i]["name"]) for i in info if not info[i].get("isAvailable", True)]
    print("[1] references")
    print("    resolved      : %d/%d" % (len(info), len(set(ids))))
    print("    unresolved    : %s" % (unresolved or "none"))
    print("    unavailable   : %s" % (unavailable or "none"))
    if unresolved:
        fail.append("%d manifest references do not resolve" % len(unresolved))
    if unavailable:
        fail.append("%d referenced projects are unavailable" % len(unavailable))

    # every referenced fileID must still exist
    fids = [f["fileID"] for f in man["files"]]
    got = set()
    for i in range(0, len(fids), 200):
        for d in api("https://api.curseforge.com/v1/mods/files", {"fileIds": fids[i:i + 200]})["data"]:
            got.add(d["id"])
    dead = [f for f in fids if f not in got]
    print("    dead fileIDs  : %s" % (dead or "none"))
    if dead:
        fail.append("%d referenced fileIDs no longer exist" % len(dead))
    print()

    # --- 2. no bundled jar collides with a CF-hosted filename -------------------
    if "CF-IMPORT" in os.path.basename(path).upper() or "--import" in sys.argv:
        fail += check_import(z, man, jars)
        print("=" * 70)
        if fail:
            print("PRE-FLIGHT FAILED")
            for f in fail:
                print("  - %s" % f)
            sys.exit(1)
        print("PRE-FLIGHT PASSED -- safe to distribute (Discord/direct, NOT CurseForge)")
        return

    print("[2] bundled jars vs CF-hosted filenames")
    jar_paths = {n.split("/")[-1]: n for n in z.namelist() if n.lower().endswith(".jar")}
    unverified = []
    for jar in jars:
        names, mod_ids = jar_identity(z, jar_paths[jar])
        stem = jar.rsplit("-", 1)[0]
        # Ask the jar first, then fall back to filename-derived guesses.
        terms = names + mod_ids + [stem.replace("_", " ").replace("-", " "), jar.split("-")[0]]
        slugs = mod_ids + [stem.lower().replace("_", "-"), stem.lower().replace("_", "").replace("-", "")]
        slugs += [n.lower().replace(" ", "-").replace(":", "") for n in names]

        seen, collision = {}, None
        for s in dict.fromkeys(s for s in slugs if s):
            for proj in by_slug(s):
                seen[proj["id"]] = proj["name"]
        for t in dict.fromkeys(t for t in terms if t):
            for proj in search(t):
                seen[proj["id"]] = proj["name"]
            if len(seen) > 40:
                break

        for pid, pname in seen.items():
            for f in all_files(pid):
                if f["fileName"] == jar:
                    collision = (pname, pid, f["id"])
                    break
            if collision:
                break

        label = (names[0] if names else "?")
        if collision:
            print("    !! COLLISION  %-46s == %s (proj %s, file %s)" % (jar, collision[0], collision[1], collision[2]))
            fail.append("bundled jar %s has an exact CF filename match" % jar)
        elif not seen:
            # Never print "ok" here: finding no project is not the same as finding no collision.
            print("    ?? UNVERIFIED %-46s declared=%r -- no CF project located" % (jar, label))
            unverified.append(jar)
        else:
            print("    ok            %-46s declared=%r (%d proj)" % (jar, label, len(seen)))
    if unverified:
        print()
        print("    NOTE: %d jar(s) could not be located on CF. That is a WEAKER result than 'ok' --" % len(unverified))
        print("          it means no project was found to compare against, not that it is safe.")
    print()

    print("=" * 70)
    if fail:
        print("PRE-FLIGHT FAILED")
        for f in fail:
            print("  - %s" % f)
        sys.exit(1)
    print("PRE-FLIGHT PASSED -- safe to upload")


if __name__ == "__main__":
    main()
