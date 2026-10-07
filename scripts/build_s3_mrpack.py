#!/usr/bin/env python3
r"""
Season 3 MODRINTH upload artifact, built from the same inputs as the s3/ channel.

    py scripts/build_s3_channel.py --version 3.0.0     # first: writes s3/ + the release assets
    py scripts/build_s3_mrpack.py --version 3.0.0

  REFERENCE  every file Modrinth hosts (s3/ Modrinth metafiles) -> files[] pointing at its CDN.
  BUNDLE     our Core (full, with the updater), aerowarptics, the replay mod -> overrides/mods/,
             the same way the published Season 2 Modrinth builds carried them.
  DROP       the FTB stack. Modrinth review rejects it ("no permission") and does not host it.

Dropped mods are not lost: the shipped Core config is stamped one notch BELOW the channel version,
so the first launch runs the in-client updater against main/s3/ and pulls FTB from the s3-v<ver>
release. Same trick as scripts/build_modrinth_mrpack.py for Season 2.

Overrides here are the FULL profile settings (config/, defaultconfigs/, options.txt), unlike the
channel, which manages only the Core config + news. See build_s3_channel.py for why.
"""
import argparse, json, os, re, sys, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_s3_channel as ch  # noqa: E402

RELEASES = r"D:\MC Project\Releases"
NO_PERMISSION = ("ftb-chunks", "ftb-essentials", "ftb-library", "ftb-quests", "ftb-teams", "ftb-ultimine")
BOOTSTRAP = "2.99.99"  # < 3.0.0, so the updater fires on first launch and backfills NO_PERMISSION


def jar_mods(path):
    """(modIds provided, modIds required on the client) from a jar's neoforge.mods.toml."""
    import tomllib
    try:
        raw = zipfile.ZipFile(path).read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
        t = tomllib.loads(raw)
    except Exception:
        return set(), set()
    provides = {m.get("modId") for m in t.get("mods", []) if m.get("modId")}
    needs = set()
    for deps in (t.get("dependencies") or {}).values():
        for dep in deps if isinstance(deps, list) else []:
            required = dep.get("type", "required" if dep.get("mandatory", True) else "optional")
            if str(required).lower() == "required" and str(dep.get("side", "BOTH")).upper() != "SERVER":
                needs.add(dep.get("modId"))
    return provides, needs


def sweep_dependents(by_name, refs, bundled, dropped):
    """Move every kept mod that (transitively) requires a dropped modId into `dropped`. In place."""
    path_of = {n: p for (d, n), p in by_name.items() if d == "mods"}
    info = {n: jar_mods(p) for n, p in path_of.items()}
    gone = set().union(*(info.get(n, (set(), set()))[0] for n in dropped)) if dropped else set()
    swept = []
    while True:
        kept = [r["path"].split("/", 1)[1] for r in refs if r["path"].startswith("mods/")] + \
               [n for d, n, _ in bundled if d == "mods"]
        provided = set().union(*(info[n][0] for n in kept if n in info))
        hit = [n for n in kept if n in info and (info[n][1] & gone) - provided]
        if not hit:
            return swept
        for n in hit:
            if n.startswith("CoffeesAeroCore-"):
                ch.fail("the Core would be dropped (it requires %s) -- the updater could never run"
                        % sorted(info[n][1] & gone))
            gone |= info[n][0]
            dropped.append(n)
            swept.append(n)
        refs[:] = [r for r in refs if r["path"].split("/", 1)[1] not in hit]
        bundled[:] = [b for b in bundled if b[1] not in hit]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="3.0.0")
    ap.add_argument("--core", default="1.3.54")
    a = ap.parse_args()

    pack = open(os.path.join(ch.S3, "pack.toml"), encoding="utf-8").read()
    if not re.search(r'(?m)^version\s*=\s*"%s"' % re.escape(a.version), pack):
        ch.fail("s3/pack.toml is not %s -- run build_s3_channel.py first" % a.version)

    core = ch.find_core(a.core)
    files = ch.profile_files(core)
    by_name = {(d, n): p for d, n, p in files}

    refs, bundled, dropped = [], [], []
    for d in ch.CONTENT_DIRS:
        mdir = os.path.join(ch.S3, d)
        for mf in sorted(os.listdir(mdir)) if os.path.isdir(mdir) else []:
            t = open(os.path.join(mdir, mf), encoding="utf-8").read()
            n = re.search(r'(?m)^filename\s*=\s*"([^"]+)"', t).group(1)
            url = re.search(r'(?m)^url\s*=\s*"([^"]+)"', t).group(1)
            p = by_name[(d, n)]
            if "cdn.modrinth.com" in url:
                refs.append({"path": "%s/%s" % (d, n),
                             "hashes": {"sha1": ch.sha(p, "sha1"), "sha512": ch.sha(p, "sha512")},
                             "env": {"client": "required", "server": "required"},
                             "downloads": [url], "fileSize": os.path.getsize(p)})
            elif n.startswith(NO_PERMISSION):
                dropped.append(n)
            else:
                bundled.append((d, n, p))
    if len(refs) + len(bundled) + len(dropped) != len(files):
        ch.fail("coverage gap: %d+%d+%d != %d" % (len(refs), len(bundled), len(dropped), len(files)))

    # 🔴 Dependency sweep (2026-10-07). Dropping FTB left createultimine + ultimine_rewind in the pack,
    # both REQUIRE ftbultimine -> NeoForge refuses to boot -> the Core never runs -> the updater never
    # backfills anything. Every KEPT mod that requires a dropped modId is dropped too, to a fixpoint.
    # They are all in the s3/ channel, so the first-launch update installs them together with FTB.
    swept = sweep_dependents(by_name, refs, bundled, dropped)
    for n in swept:
        print("  dropped (requires a dropped mod):", n)

    index = {"formatVersion": 1, "game": "minecraft", "versionId": a.version,
             "name": "Coffee's Create: Aeronautics SMP - Season 3",
             "summary": "Season 3 of Coffee's Create: Aeronautics SMP.",
             "files": refs, "dependencies": {"minecraft": ch.MC, "neoforge": ch.NEOFORGE}}
    out = os.path.join(RELEASES, "CoffeesAeroSMP-S3-%s-MODRINTH.mrpack" % a.version)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        for d, n, p in bundled:
            z.write(p, "overrides/%s/%s" % (d, n))
        for d in ch.CONTENT_DIRS:  # side files like the shader licence .txt
            for n in os.listdir(os.path.join(ch.PROFILE, d)):
                if n.endswith(".txt"):
                    z.write(os.path.join(ch.PROFILE, d, n), "overrides/%s/%s" % (d, n))
        managed = set()
        for dp, _, fns in os.walk(ch.S3_OVERRIDES_SRC):
            for fn in fns:
                src = os.path.join(dp, fn)
                rel = os.path.relpath(src, ch.S3_OVERRIDES_SRC).replace(os.sep, "/")
                data = open(src, "rb").read()
                if rel == "config/coffeesaerosmp_core-client.toml":
                    data, k = re.subn(rb'(?m)^packVersion\s*=\s*"[^"]*"',
                                      ('packVersion = "%s"' % BOOTSTRAP).encode(), data)
                    if k != 1:
                        ch.fail("could not stamp the bootstrap packVersion")
                z.writestr("overrides/" + rel, data)
                managed.add(rel)
        for d in ch.OVERRIDE_DIRS:
            for dp, _, fns in os.walk(os.path.join(ch.PROFILE, d)):
                for fn in fns:
                    src = os.path.join(dp, fn)
                    rel = os.path.relpath(src, ch.PROFILE).replace(os.sep, "/")
                    if rel not in ch.OVERRIDE_EXCLUDE and rel not in managed:
                        z.write(src, "overrides/" + rel)
        for f in ch.OVERRIDE_FILES:
            z.write(os.path.join(ch.PROFILE, f), "overrides/" + f)

    print("wrote %s  %.1f MB" % (out, os.path.getsize(out) / 1e6))
    print("referenced=%d bundled=%d dropped=%d (backfilled by the updater from s3-v%s)"
          % (len(refs), len(bundled), len(dropped), a.version))
    for d, n, _ in bundled:
        print("  bundled:", n)


if __name__ == "__main__":
    main()
