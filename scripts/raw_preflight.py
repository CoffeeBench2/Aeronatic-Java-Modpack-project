"""Verify what GitHub's raw CDN is ACTUALLY serving for the pack, not what was pushed.

The raw CDN lags a push by roughly 300 seconds and ignores no-cache headers, so for ~5 minutes
after a push some edges still serve the previous commit. The dangerous part is that they serve a
CONSISTENT previous commit: pack.toml and index.toml both come from the old pair, so the integrity
gate passes and a green result immediately after pushing proves nothing.

This checks the served version against what the pack should be, so "stale" is distinguishable from
"live" rather than both looking green.

Usage: py scripts/raw_preflight.py [expected-version]
"""
import hashlib
import json
import re
import sys
import time
import urllib.request

BASE = "https://raw.githubusercontent.com/CoffeeBench2/Aeronatic-Java-Modpack-project/main/"


def get(path):
    req = urllib.request.Request(
        BASE + path + "?cb=" + str(time.time()),
        headers={"User-Agent": "aero-preflight", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def main():
    expected = sys.argv[1] if len(sys.argv) > 1 else None

    version = json.loads(get("version.json"))["version"]
    pack = get("pack.toml").decode("utf-8")
    index = get("index.toml")

    m = re.search(r"(?ms)^\[index\].*?hash\s*=\s*\"([0-9a-f]+)\"", pack)
    declared = m.group(1) if m else "?"
    actual = hashlib.sha256(index).hexdigest()
    pv = re.search(r"(?m)^version\s*=\s*\"([^\"]+)\"", pack)
    pack_version = pv.group(1) if pv else "?"

    print("version.json served : %s" % version)
    print("pack.toml  served   : %s" % pack_version)
    print("index declared      : %s" % declared[:20])
    print("index actual        : %s" % actual[:20])
    gate = declared == actual
    print("integrity gate      : %s" % ("PASS" if gate else "MISMATCH"))
    print("analog 0.1.5 indexed: %s" % ("analog-audio-0-1-5" in index.decode("utf-8", "replace")))

    if expected:
        fresh = version == expected and pack_version == expected
        print()
        print("expected            : %s" % expected)
        print("STATUS              : %s" % (
            "LIVE — edges are serving the new pack" if fresh and gate
            else "STALE — CDN still on the old commit, wait and re-run"))
        if not (fresh and gate):
            sys.exit(1)


if __name__ == "__main__":
    main()
