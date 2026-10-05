#!/usr/bin/env python3
r"""
LAUNCH DAY ONLY: retire the Season 2 update channel (the ROOT pack.toml / version.json).

    py scripts/retire_s2.py --dry-run     # show what would change
    py scripts/retire_s2.py               # write it; then review, commit and push main

Every installed Season 2 Core polls the root version.json and installs from the root pack.toml.
This makes ONE last Season 2 release, 1.11.5, whose only change is the shipped Core config:
manualUpdateOnly = true. The root version.json then advertises 3.0.0 and points at Modrinth.

  S2 client on 1.11.5          -> 1.11.5 < 3.0.0, manualUpdateOnly -> store screen ("get the newest
                                  version from Modrinth"), downloads nothing.
  S2 client still on <= 1.11.4 -> its updater is still ON, syncs to root pack.toml = 1.11.5 (only the
                                  config changes), relaunches into the same store screen. No loop:
                                  manualUpdateOnly stops the downloader.

After this, never touch the root channel files again. Season 3 ships from s3/.
The Core metafile URL stays on v1.11.4 -- that asset still exists and the Core does not change.
"""
import argparse, difflib, json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FINAL_S2 = "1.11.5"
S3_VERSION = "3.0.0"
MODRINTH_PAGE = "https://modrinth.com/modpack/coffees-create-aeronautics-smp"
NOTES = ("Season 3 is here! This Season 2 install can't update to it. Open the Modrinth App (or modrinth.com), "
         "install the newest version of Coffee's Create: Aeronautics SMP, and play from that.")


def sub(text, pattern, repl, what):
    new, k = re.subn(pattern, repl, text, flags=re.M)
    if k != 1:
        sys.exit("ERROR: expected exactly one %s, found %d" % (what, k))
    return new


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    edits = {}
    p = os.path.join(ROOT, "pack.toml")
    edits[p] = sub(open(p, encoding="utf-8").read(), r'^version\s*=\s*"[^"]*"', 'version = "%s"' % FINAL_S2,
                   "pack.toml version")
    p = os.path.join(ROOT, "scripts", "build_mrpack.py")
    edits[p] = sub(open(p, encoding="utf-8").read(), r'^VERSION\s*=\s*"[^"]*"', 'VERSION = "%s"' % FINAL_S2,
                   "build_mrpack VERSION")
    p = os.path.join(ROOT, "overrides", "config", "coffeesaerosmp_core-client.toml")
    t = open(p, encoding="utf-8").read()
    t = sub(t, r'^packVersion\s*=\s*"[^"]*"', 'packVersion = "%s"' % FINAL_S2, "packVersion")
    if re.search(r"(?m)^manualUpdateOnly\s*=", t):
        t = sub(t, r"^manualUpdateOnly\s*=.*$", "manualUpdateOnly = true", "manualUpdateOnly")
    else:
        t = t.rstrip("\n") + "\n# Season 2 is retired (S3 launch): show the store screen, never download.\nmanualUpdateOnly = true\n"
    edits[p] = t
    p = os.path.join(ROOT, "version.json")
    vj = json.load(open(p, encoding="utf-8"))
    vj.update(version=S3_VERSION, url=MODRINTH_PAGE, notes=NOTES)
    edits[p] = json.dumps(vj, indent=2) + "\n"

    for path, new in edits.items():
        old = open(path, encoding="utf-8").read()
        sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
                                                   os.path.relpath(path, ROOT), os.path.relpath(path, ROOT)))
    if a.dry_run:
        print("\n(dry run -- nothing written)")
        return
    for path, new in edits.items():
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(new)
    r = subprocess.run([r"C:\tools\packwiz\packwiz", "refresh"], cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("ERROR: packwiz refresh failed\n" + r.stdout + r.stderr)
    print("\nWritten. Review `git diff`, confirm index.toml changed ONLY the Core config + pack.toml, then")
    print("commit and push main. Check the CDN with: py scripts/raw_preflight.py %s" % FINAL_S2)


if __name__ == "__main__":
    main()
