"""Check that the pack version agrees everywhere, and that the packwiz index is intact.

Run this before EVERY release push:

    py scripts/version_preflight.py

The pack version does not live in one place. It lives in FIVE, and a miss in any one of them
ships a broken update:

  1. scripts/build_mrpack.py                            VERSION      -> names the mrpack
  2. pack.toml                                          version      -> what packwiz serves
  3. overrides/config/coffeesaerosmp_core-client.toml   packVersion  -> what the CLIENT thinks
                                                                       it has installed
  4. version.json                                       version      -> what the client polls
                                                                       to decide "update available"
  5. mods/coffeesaerocore.pw.toml                       download url -> pinned to the GH RELEASE TAG

Miss #3 and the client compares a stale installed-version against a new remote one, decides it is
out of date, updates, still reads the stale value, and loops forever.

Miss #5 and every updater client 404s on the Core, because the tag in that URL is the release the
jar was uploaded to. It is the place that gets forgotten when a release is renumbered or folded
into another one — #1-#4 are all "the version", but #5 is "the tag", and they only look the same.

Also verifies pack.toml's [index] hash against sha256(index.toml). packwiz refresh maintains this;
any hand-edit of index.toml, or an overrides/ edit without a refresh, breaks it and the in-client
updater then fails with "hash mismatch after download".

Exit code 0 = safe to push. 1 = do not push.
"""
import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# path -> (regex with ONE capturing group, human label)
PLACES = {
    "scripts/build_mrpack.py":
        (r'^VERSION\s*=\s*"([\d.]+)"', "mrpack build version"),
    "pack.toml":
        (r'^version\s*=\s*"([\d.]+)"', "packwiz pack version"),
    "overrides/config/coffeesaerosmp_core-client.toml":
        (r'^packVersion\s*=\s*"([\d.]+)"', "version stamped into the shipped client config"),
    "version.json":
        (r'"version"\s*:\s*"([\d.]+)"', "version the client polls"),
    "mods/coffeesaerocore.pw.toml":
        (r'releases/download/v([\d.]+)/', "GH release tag in the Core download URL"),
}


def read(rel):
    # utf-8-sig: build_mrpack.py carries a BOM. Do not "fix" it here — rewriting these files is
    # how options.txt got silently voided once already.
    with open(os.path.join(ROOT, rel), encoding="utf-8-sig") as fh:
        return fh.read()


def main():
    problems = []
    found = {}

    for rel, (pattern, label) in PLACES.items():
        try:
            text = read(rel)
        except OSError as e:
            problems.append(f"{rel}: cannot read ({e})")
            continue
        m = re.search(pattern, text, re.M)
        if not m:
            problems.append(f"{rel}: no version found - did the file format change?")
            continue
        found[rel] = m.group(1)
        print(f"  {found[rel]:<10} {rel}  ({label})")

    print()
    distinct = set(found.values())
    if len(distinct) > 1:
        problems.append(f"versions disagree: {sorted(distinct)}")
    elif distinct:
        print(f"version: all {len(found)} places agree on {distinct.pop()}")

    # packwiz index integrity
    try:
        pack = read("pack.toml")
        block = re.search(r"\[index\](.*?)(?=\n\[|\Z)", pack, re.S)
        declared = re.search(r'hash\s*=\s*"([0-9a-f]+)"', block.group(1)).group(1)
        with open(os.path.join(ROOT, "index.toml"), "rb") as fh:
            actual = hashlib.sha256(fh.read()).hexdigest()
        if declared == actual:
            print("index:   pack.toml [index] hash matches sha256(index.toml)")
        else:
            problems.append(
                "index hash mismatch - run `packwiz refresh`\n"
                f"    pack.toml declares : {declared}\n"
                f"    index.toml actually: {actual}")
    except (AttributeError, OSError) as e:
        problems.append(f"could not verify the index hash: {e}")

    # The Core metafile hash must match the jar actually bundled in overrides/mods, or the two
    # delivery channels (mrpack bundle vs updater download) ship different bytes.
    try:
        meta = read("mods/coffeesaerocore.pw.toml")
        filename = re.search(r'filename\s*=\s*"([^"]+)"', meta).group(1)
        declared = re.search(r'^hash\s*=\s*"([0-9a-f]+)"', meta, re.M).group(1)
        jar = os.path.join(ROOT, "overrides", "mods", filename)
        if not os.path.isfile(jar):
            problems.append(f"Core metafile names {filename}, which is not in overrides/mods/")
        else:
            with open(jar, "rb") as fh:
                actual = hashlib.sha256(fh.read()).hexdigest()
            if declared == actual:
                print(f"core:    {filename} matches its metafile hash")
            else:
                problems.append(
                    f"Core metafile hash does not match overrides/mods/{filename}\n"
                    f"    metafile declares: {declared}\n"
                    f"    jar on disk      : {actual}")
    except (AttributeError, OSError) as e:
        problems.append(f"could not verify the Core metafile: {e}")

    print()
    if problems:
        print("BLOCKED - do not push:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("OK - versions consistent, index intact, Core hash matches.")
    print("Reminder: the GH release tag in the Core URL must EXIST before you push,")
    print("or every updater client 404s on the Core download.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
