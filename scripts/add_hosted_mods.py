"""Write packwiz url-metadata for every pack file that is NOT on Modrinth.

Run this AFTER `build_packwiz.py`. That script pins everything it can find on Modrinth by file
hash; whatever it cannot find is self-hosted on our GitHub Release and needs an explicit
`[download] url` entry, which is what this writes.

    py scripts/build_packwiz.py
    py scripts/add_hosted_mods.py            # tag defaults to v<pack.toml version>
    py scripts/add_hosted_mods.py --tag v1.9.4
    packwiz refresh

WHY THIS WAS REWRITTEN (2026-08-17)
The old version hardcoded `TAG = "v1.5.0"` and a hand-maintained list of filenames that had
drifted years out of date -- it still named `CoffeesAeroCore-1.0.0.jar` and
`ftb-chunks-2101.1.19`. Running it would have written metadata pointing at assets that do not
exist on that release, i.e. a 404 for every player mid-update. Both the tag and the file list are
now DERIVED, so they cannot rot:

  * the tag comes from pack.toml (the release we are actually cutting), and
  * the file list is "everything in the pack with no .pw.toml", computed fresh each run.

SAFETY RULE: this never writes an entry it has not verified exists on the release. A missing
asset is reported and the script exits non-zero, because a wrong URL here breaks the updater for
everyone and is invisible until players hit it.
"""
import argparse, hashlib, json, os, re, subprocess, sys

ROOT = r"D:\MC Project\untitled"
REPO = "CoffeeBench2/Aeronatic-Java-Modpack-project"

# (metadata dir, source dir, extensions) — mirrors build_packwiz.py
SOURCES = [
    ("mods",          os.path.join(ROOT, "overrides", "mods"),          (".jar",)),
    ("resourcepacks", os.path.join(ROOT, "overrides", "resourcepacks"), (".zip",)),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", os.path.splitext(s)[0].lower()).strip("-")


def pack_version():
    with open(os.path.join(ROOT, "pack.toml"), encoding="utf-8") as f:
        m = re.search(r'^version\s*=\s*"(.+?)"', f.read(), re.M)
    if not m:
        sys.exit("pack.toml has no version field")
    return m.group(1)


def indexed_filenames():
    """Every filename that already has a .pw.toml, so we only fill real gaps."""
    have = set()
    for cat, _, _ in SOURCES:
        d = os.path.join(ROOT, cat)
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if not f.endswith(".pw.toml"):
                continue
            with open(os.path.join(d, f), encoding="utf-8") as fh:
                m = re.search(r'^filename\s*=\s*"(.+?)"', fh.read(), re.M)
            if m:
                have.add(m.group(1))
    return have


def release_assets(tag):
    """name -> browser download URL, already URL-encoded by GitHub (filenames contain spaces)."""
    out = subprocess.run(
        ["gh", "release", "view", tag, "--repo", REPO, "--json", "assets"],
        capture_output=True, text=True, cwd=ROOT, shell=True)
    if out.returncode != 0:
        sys.exit(f"could not read release {tag}: {out.stderr.strip()}\n"
                 f"Create the release and upload the self-hosted jars BEFORE running this.")
    return {a["name"]: a["url"] for a in json.loads(out.stdout)["assets"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", help="release tag (default: v<pack.toml version>)")
    ap.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    args = ap.parse_args()

    tag = args.tag or ("v" + pack_version())
    print(f"release tag: {tag}")

    assets = release_assets(tag)
    have = indexed_filenames()

    gaps, written, absent = [], 0, []
    for cat, folder, exts in SOURCES:
        if not os.path.isdir(folder):
            continue
        for name in sorted(os.listdir(folder)):
            if not name.lower().endswith(exts) or name in have:
                continue
            gaps.append((cat, folder, name))

    if not gaps:
        print("no gaps — every pack file already has metadata. Nothing to do.")
        return 0

    print(f"files with no metadata: {len(gaps)}")
    for cat, folder, name in gaps:
        if name not in assets:
            absent.append(name)
            print(f"  !! {name}  — NOT UPLOADED to {tag}")
            continue
        path = os.path.join(folder, name)
        body = (f'name = "{os.path.splitext(name)[0]}"\n'
                f'filename = "{name}"\n'
                f'side = "both"\n\n'
                f"[download]\n"
                f'url = "{assets[name]}"\n'
                f'hash-format = "sha256"\n'
                f'hash = "{sha256(path)}"\n')
        dest = os.path.join(ROOT, cat, slug(name) + ".pw.toml")
        if args.dry_run:
            print(f"  (dry-run) would write {os.path.relpath(dest, ROOT)}")
        else:
            with open(dest, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(body)
            print(f"  + {os.path.relpath(dest, ROOT)}")
        written += 1

    if absent:
        print(f"\n*** {len(absent)} file(s) are in the pack but NOT on release {tag}. ***")
        print("Upload them to the release, then re-run. Writing metadata for a missing asset")
        print("would 404 every client mid-update, so nothing was written for these.")
        for a in absent:
            print("   ", a)
        return 1

    print(f"\nwrote {written} entr{'y' if written == 1 else 'ies'}. Run `packwiz refresh` next.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
