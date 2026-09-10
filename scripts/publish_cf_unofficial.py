#!/usr/bin/env python3
r"""
Refresh the "Unofficial CurseForge-format download" release page.

    py scripts/publish_cf_unofficial.py

Builds the slim CF zip for the CURRENT pack version and republishes it to the fixed `cf-unofficial`
tag, so the Discord link never changes:

    page      https://github.com/CoffeeBench2/Aeronatic-Java-Modpack-project/releases/tag/cf-unofficial
    download  .../releases/download/cf-unofficial/CoffeesAeroSMP-CF-SLIM.zip

WHY A FIXED TAG AND A VERSION-LESS FILENAME. Both are what make the link permanent. Put the version
in either one and every pack release orphans the link that is already pasted in Discord.

🔴 WHY THE RELEASE MUST STAY A PRE-RELEASE. `version.json` sets the in-game update link to
`/releases/latest`, and GitHub defines "latest" as the newest release that is NOT a pre-release. Cut
this page as a normal release and `/releases/latest` starts resolving HERE instead of the pack's own
tag -- so every player who clicks Update lands on the CurseForge-format zip. The gate at the bottom
checks that after publishing, because this is silent and would only surface as confused players.

Run this as the LAST step of the re-release runbook, after `packwiz refresh` + push and after the
pack's own `gh release create`. It reads pack.toml, so there is nothing to bump here.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES = r"D:\MC Project\Releases"
SCRIPTS = os.path.join(ROOT, "scripts")
TAG = "cf-unofficial"
ASSET = "CoffeesAeroSMP-CF-SLIM.zip"          # version-less ON PURPOSE -- see docstring
REPO = "CoffeeBench2/Aeronatic-Java-Modpack-project"
TITLE = "Unofficial CurseForge-format download (self-updating)"


def gh(*args, **kw):
    """gh must run with cwd=ROOT -- it resolves the repo from git, and CWD elsewhere fails."""
    return subprocess.run(["gh", *args], cwd=ROOT, check=kw.pop("check", True),
                           capture_output=True, text=True, **kw).stdout.strip()


def pack_version():
    t = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
    return re.search(r'(?m)^version\s*=\s*"([^"]+)"', t).group(1)


NOTES = """## Unofficial CurseForge-format download — **self-updating**

This is the pack in **CurseForge launcher format**, for players who use the CurseForge / Overwolf app
instead of the Modrinth app or Prism.

It is **not** the CurseForge store listing. It updates **in-game**, like the Modrinth/GitHub pack —
you will never be told to go re-download and reinstall from your launcher.

> **Currently: pack {version}** · Minecraft {mc} · NeoForge {neoforge} · ~{size} MB

### Install

1. Download **`{asset}`** below.
2. CurseForge app → **Minecraft** → **Create Custom Profile** → **Import**.
3. Pick the zip, let it finish, then launch.
4. Set your RAM to **8 GB**. More is worse, not better — over-allocating starves the GPU/native
   memory that Distant Horizons and the shaders need, and the game dies mid-load with no crash report.

### Updating

Just launch the game. When a new pack version is out, the title screen offers **Update** and it
downloads only what changed. Nothing to re-import, nothing to re-download here.

### What's different from the store pack

The CurseForge store listing cannot carry the in-client updater — CurseForge policy forbids a mod
that fetches files from outside the pack, so the store build has the updater removed and you have to
update through the launcher. This build keeps the updater, which is why it lives here instead.

A few mods are bundled in the zip rather than downloaded from CurseForge, because CurseForge either
doesn't host them or hosts a differently-built copy. That's deliberate: the updater matches files by
hash, so the bundled copies are the ones it expects.

### Links

- **Discord** — https://discord.gg/AnFUh5vTz6
- Modrinth app / Prism users: use the `.mrpack` on the [latest pack release](https://github.com/{repo}/releases/latest)

---

*This page is a fixed link — it is refreshed in place for every pack version, so bookmarks and the
Discord link never go stale. It is marked a pre-release only so it stays out of the way of the
pack's own "latest release" pointer, which the in-game update check uses.*
"""


def main():
    version = pack_version()

    print("building the slim CF zip for %s ..." % version)
    r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "build_cf_discord_slim.py")],
                       cwd=ROOT)
    if r.returncode != 0:
        sys.exit("slim build failed -- not publishing")

    built = os.path.join(RELEASES, "CoffeesAeroSMP-%s-CF-DISCORD-SLIM.zip" % version)
    if not os.path.isfile(built):
        sys.exit("expected %s -- did the build change its output name?" % built)

    alias = os.path.join(RELEASES, ASSET)
    shutil.copyfile(built, alias)
    size_mb = round(os.path.getsize(alias) / 1024 / 1024)

    pack = open(os.path.join(ROOT, "pack.toml"), encoding="utf-8").read()
    notes = NOTES.format(
        version=version, asset=ASSET, size=size_mb, repo=REPO,
        mc=re.search(r'(?m)^minecraft\s*=\s*"([^"]+)"', pack).group(1),
        neoforge=re.search(r'(?m)^neoforge\s*=\s*"([^"]+)"', pack).group(1))
    notes_path = os.path.join(RELEASES, "_cf_unofficial_notes.md")
    open(notes_path, "w", encoding="utf-8", newline="\n").write(notes)

    exists = subprocess.run(["gh", "release", "view", TAG], cwd=ROOT,
                            capture_output=True, text=True).returncode == 0
    if exists:
        print("updating the existing %s page ..." % TAG)
        gh("release", "edit", TAG, "--title", TITLE, "--notes-file", notes_path,
           "--prerelease")
        gh("release", "upload", TAG, alias, "--clobber")
    else:
        print("creating the %s page ..." % TAG)
        gh("release", "create", TAG, "--title", TITLE, "--notes-file", notes_path,
           "--prerelease", "--target", "main", alias)

    # ── gates ────────────────────────────────────────────────────────────────
    info = json.loads(gh("release", "view", TAG, "--json", "isPrerelease,assets,tagName"))
    problems = []
    if not info["isPrerelease"]:
        problems.append("%s is NOT a pre-release -- it will hijack /releases/latest" % TAG)
    if ASSET not in [a["name"] for a in info["assets"]]:
        problems.append("%s is missing from the release" % ASSET)

    # The invariant that actually matters, checked against GitHub rather than assumed: the pack's
    # own tag must still be what /releases/latest resolves to, because that is where every
    # in-game Update click goes.
    with urllib.request.urlopen(
            "https://github.com/%s/releases/latest" % REPO, timeout=30) as resp:
        landed = resp.geturl()
    if landed.rstrip("/").endswith("/" + TAG):
        problems.append("/releases/latest now resolves to %s -- in-game Update links are broken" % TAG)
    print()
    print("/releases/latest -> %s" % landed)

    if problems:
        sys.exit("PUBLISHED BUT BROKEN:\n  - " + "\n  - ".join(problems))

    print("page     : https://github.com/%s/releases/tag/%s" % (REPO, TAG))
    print("download : https://github.com/%s/releases/download/%s/%s" % (REPO, TAG, ASSET))
    print("pack %s, %d MB, pre-release (latest pointer untouched)" % (version, size_mb))


if __name__ == "__main__":
    main()
