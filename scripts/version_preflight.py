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

🔴 A SIXTH site exists and it is the only BINARY one: the built mrpack. All five text sites can
agree and the zip can still bundle a different build of the Core, because it is a separate artifact
cut at its own moment in time. On 2026-09-14 the mrpack was zipped at 00:53 and the Core rebuilt at
00:56; they differed in exactly one class, so fresh installers would have received the broken
What's New popup while updater users received the fix — both calling themselves 1.10.23.
So this also opens the mrpack (when one exists for the current version) and checks that it bundles
a Core byte-identical to the metafile hash, stamps the right version inside, carries no BOM, and
bundles nothing listed in StaleMods.RETIRED (a retired mod in the bundle is a fresh-install lockout,
because the sweep only removes it after the first launch).

Also verifies pack.toml's [index] hash against sha256(index.toml). packwiz refresh maintains this;
any hand-edit of index.toml, or an overrides/ edit without a refresh, breaks it and the in-client
updater then fails with "hash mismatch after download".

Exit code 0 = safe to push. 1 = do not push.
"""
import hashlib
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES = r"D:\MC Project\Releases"

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


def load_retired():
    """The retired-mod prefixes, read from StaleMods.java — the one list that is authoritative.

    Deliberately parsed from source rather than duplicated here. A second copy of this list is
    exactly the bug that locked every player out on 2026-09-13: InClientUpdater kept its own copy,
    the removals were added to that one, and the list that actually runs never saw them.
    Returns [] if it cannot be parsed, so this check degrades to a no-op instead of a false block.
    """
    src = os.path.join(ROOT, "src", "AeroCore", "src", "main", "java", "com", "coffeesaerosmp",
                       "core", "cleanup", "StaleMods.java")
    try:
        with open(src, encoding="utf-8") as fh:
            text = fh.read()
        block = re.search(r"RETIRED\s*=\s*List\.of\((.*?)\);", text, re.S).group(1)
        # Strip // comments so a commented-out example cannot become a live prefix.
        block = re.sub(r"//[^\n]*", "", block)
        return [s.lower() for s in re.findall(r'"([^"]+)"', block)]
    except (OSError, AttributeError):
        return []


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
    # Kept in its OWN name, not the `declared` used by the index check above: if this block throws
    # before assigning, a later reader would silently compare against the index hash instead.
    core_hash = None
    try:
        meta = read("mods/coffeesaerocore.pw.toml")
        filename = re.search(r'filename\s*=\s*"([^"]+)"', meta).group(1)
        declared = re.search(r'^hash\s*=\s*"([0-9a-f]+)"', meta, re.M).group(1)
        core_hash = declared
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

    # ── The mrpack: a SIXTH site, and the only binary one ─────────────────────
    # 🔴 Every check above reads TEXT. On 2026-09-14 all five text sites agreed on 1.10.23 and the
    # mrpack still bundled a DIFFERENT build of Core 1.3.49 - the zip was cut at 00:53 and the jar
    # rebuilt at 00:56, differing in exactly one class. Fresh installers would have received the
    # broken What's New popup while updater users received the fix, both calling themselves 1.10.23.
    #
    # A green preflight proved nothing about the bytes players actually install, so check them.
    # Absent mrpack is fine (not built yet); a PRESENT one that disagrees is a blocker.
    mrpack_version = found.get("pack.toml")
    mrpack = os.path.join(RELEASES, f"CoffeesAeroSMP-{mrpack_version}.mrpack")
    if not mrpack_version:
        pass
    elif not os.path.isfile(mrpack):
        print(f"mrpack:  not built yet ({os.path.basename(mrpack)}) - build it before the release")
    else:
        try:
            import zipfile
            with zipfile.ZipFile(mrpack) as z:
                names = z.namelist()

                # 1. The bundled Core must be byte-identical to what the metafile hashes, or the
                #    two delivery channels ship different code under one version.
                cores = [n for n in names
                         if n.startswith("overrides/mods/")
                         and os.path.basename(n).lower().startswith("coffeesaerocore")
                         and n.lower().endswith(".jar")]
                if len(cores) != 1:
                    problems.append(f"mrpack bundles {len(cores)} CoffeesAeroCore jars: {cores}")
                elif core_hash is None:
                    problems.append("cannot check the mrpack's Core: the metafile hash did not read")
                else:
                    bundled = hashlib.sha256(z.read(cores[0])).hexdigest()
                    if bundled != core_hash:
                        problems.append(
                            "mrpack bundles a DIFFERENT Core than the metafile hashes\n"
                            "    (rebuild the mrpack - it was almost certainly zipped before the "
                            "last jar build)\n"
                            f"    metafile declares: {core_hash}\n"
                            f"    mrpack bundles   : {bundled}")
                    else:
                        print(f"mrpack:  bundles {os.path.basename(cores[0])}, "
                              "byte-identical to the metafile")

                # 2. Stamped version inside the zip.
                idx = json.loads(z.read("modrinth.index.json"))
                if str(idx.get("versionId")) != mrpack_version:
                    problems.append(f"mrpack modrinth.index.json versionId is "
                                    f"{idx.get('versionId')}, pack is {mrpack_version}")
                cfg_path = "overrides/config/coffeesaerosmp_core-client.toml"
                if cfg_path in names:
                    raw = z.read(cfg_path)
                    if raw[:3] == b"\xef\xbb\xbf":
                        problems.append("mrpack's client config has a UTF-8 BOM - NeoForge will "
                                        "silently ignore it")
                    m = re.search(r'^packVersion\s*=\s*"([\d.]+)"',
                                  raw.decode("utf-8", "replace"), re.M)
                    if not m or m.group(1) != mrpack_version:
                        problems.append(f"mrpack's bundled config says packVersion "
                                        f"{m.group(1) if m else '?'}, pack is {mrpack_version}")

                # 3. A retired mod inside the bundle is a FRESH-INSTALL lockout: the sweep removes
                #    it only after the first launch, and a mod with required network channels
                #    (create_submarine) refuses the server before that ever happens.
                retired = load_retired()
                if retired:
                    jars = [os.path.basename(n) for n in names
                            if n.startswith("overrides/mods/") and n.lower().endswith(".jar")]
                    bad = sorted({j for j in jars for p in retired
                                  if j.lower().startswith(p)})
                    if bad:
                        problems.append("mrpack bundles mods listed in StaleMods.RETIRED "
                                        f"(fresh-install lockout): {bad}")
                    else:
                        print(f"mrpack:  {len(jars)} jars, none retired")
        except Exception as e:
            problems.append(f"could not verify the mrpack: {e}")

    # ── News freshness ────────────────────────────────────────────────────────
    # WARNING, never a block. The What's New popup keys on the newest RELEASE entry in
    # announcements.json; if nobody adds an entry, the popup correctly decides there is nothing
    # new and stays silent. That is how it went quiet for ten releases (1.10.12 -> 1.10.22) while
    # the code was working perfectly and everyone assumed the popup was broken.
    #
    # Deliberately non-blocking: today proved emergency hotfixes happen, and refusing to ship a
    # lockout fix because the release-notes copy is not written would be worse than a silent popup.
    news_warning = None
    try:
        news_path = os.path.join(ROOT, "overrides", "config", "coffees_aero_announcements.json")
        with open(news_path, encoding="utf-8-sig") as fh:
            entries = json.load(fh)["entries"]
        # A teaser is any entry whose version does not start with a digit; only real releases count.
        newest = next((e["version"] for e in entries
                       if str(e.get("version", "")).strip()[:1].isdigit()), None)
        if newest is None:
            news_warning = "announcements.json has no release entries at all - the popup can never fire"
        elif newest != found.get("pack.toml"):
            news_warning = (f"announcements.json newest release is {newest}, "
                            f"pack is {found.get('pack.toml')}"
                            f" - What's New will show nothing for this release")
        else:
            print(f"news:    announcements.json has a {newest} entry")
    except (OSError, ValueError, KeyError) as e:
        news_warning = f"could not read announcements.json: {e}"

    print()
    if news_warning:
        print(f"WARNING: {news_warning}")
        print("         (not a blocker - add an entry to overrides/config/"
              "coffees_aero_announcements.json)")
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
