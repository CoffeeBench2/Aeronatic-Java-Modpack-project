#!/usr/bin/env python3
r"""Build the coffees_aero_end datapack zip — TEST or PRODUCTION.

    py scripts/build_end_datapack.py            -> ..._TEST.zip   (custom boss bar kept)
    py scripts/build_end_datapack.py --production -> ..._PROD.zip (custom boss bar stripped)

There is ONE source of truth: server-assets/datapacks/coffees_aero_end. The production
variant is produced by mechanically stripping the boss-bar blocks, so the two can never
drift the way two hand-maintained copies would.

Owner's call 2026-09-26: the official release ships the **vanilla dragon bar only**. The
custom "❖ The Ender Dragon  274999 / 300000" bar exists purely so the scaled pool is
legible while testing — vanilla's dragon bar renders no numbers at all.

What "stripping" means, precisely:
  * every line mentioning `bossbar` is dropped from load.mcfunction and dragon.mcfunction
  * the `#uitick` throttle that exists only to pace the bar goes with them
  * data/aero_end/function/bossbar_name.mcfunction (the macro) is not copied
Nothing else differs between the two builds. The fight itself is identical.

⚠ Zipped with Python, NOT Compress-Archive: PowerShell writes entries the server cannot
read, and pack.mcmeta MUST sit at the ZIP ROOT or the pack is silently ignored.
"""
import os
import shutil
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "server-assets", "datapacks", "coffees_aero_end")
OUT_DIR = r"D:\MC Project\Releases\deploy-1.11.0\datapacks"

# Lines carrying these markers are boss-bar only and are removed for production.
BAR_MARKERS = ("bossbar", "#uitick", "aero_end:ui", "aero_end:bossbar_name")
BAR_ONLY_FILES = ("bossbar_name.mcfunction",)


def strip_bar(text):
    """Drop boss-bar command lines, keep comments and everything else intact."""
    kept = []
    for line in text.splitlines():
        stripped = line.strip()
        is_comment = stripped.startswith("#")
        if not is_comment and any(m in line for m in BAR_MARKERS):
            continue
        kept.append(line)
    return "\n".join(kept) + "\n"


def build(production):
    label = "PROD" if production else "TEST"
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, "coffees_aero_end_%s.zip" % label)

    staged = tempfile.mkdtemp(prefix="aeroend_")
    try:
        dest = os.path.join(staged, "coffees_aero_end")
        shutil.copytree(SRC, dest)

        if production:
            for name in BAR_ONLY_FILES:
                p = os.path.join(dest, "data", "aero_end", "function", name)
                if os.path.isfile(p):
                    os.remove(p)
                    print("  stripped file  %s" % name)
            for rel in ("data/aero_end/function/load.mcfunction",
                        "data/aero_end/function/dragon.mcfunction",
                        "data/aero_end/function/tick.mcfunction"):
                p = os.path.join(dest, rel)
                before = open(p, encoding="utf-8").read()
                after = strip_bar(before)
                open(p, "w", encoding="utf-8", newline="\n").write(after)
                gone = len(before.splitlines()) - len(after.splitlines())
                print("  stripped %2d bar lines from %s" % (gone, os.path.basename(rel)))

        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for dirpath, _, files in os.walk(dest):
                for fn in files:
                    full = os.path.join(dirpath, fn)
                    z.write(full, os.path.relpath(full, dest).replace("\\", "/"))

        with zipfile.ZipFile(out) as z:
            names = z.namelist()
            assert "pack.mcmeta" in names, "pack.mcmeta must be at the ZIP ROOT"
            bars = [n for n in names if "bossbar" in n]
            print("\n%s -> %s" % (label, out))
            print("  %d entries, pack.mcmeta at root" % len(names))
            if production:
                assert not bars, "production build still contains %s" % bars
                print("  verified: no boss-bar macro in the zip")
    finally:
        shutil.rmtree(staged, ignore_errors=True)


if __name__ == "__main__":
    build("--production" in sys.argv)
