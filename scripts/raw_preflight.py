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
import os
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


def check_reachable(index_text):
    """Every file the index names, HEAD-checked against raw main. Returns the ones that are not 200.

    This is the only check that catches "indexed locally, absent on main". Threaded because the
    index is ~285 entries and a serial sweep would make the preflight too slow to actually run.
    """
    import concurrent.futures
    import urllib.parse

    files = re.findall(r'file\s*=\s*"([^"]+)"', index_text)
    cb = str(time.time_ns())

    def head(f):
        url = BASE + urllib.parse.quote(f) + "?cb=" + cb
        try:
            req = urllib.request.Request(url, method="HEAD")
            return (f, urllib.request.urlopen(req, timeout=25).status)
        except Exception as e:
            return (f, getattr(e, "code", str(e)))

    bad = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
        for f, st in ex.map(head, files):
            if st != 200:
                bad.append(f)
    return bad


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

    # 🔴 Comparing the served pair against ITSELF is not enough. A stale edge serves a consistent
    # OLD pack.toml + index.toml, so the gate passes — and when only the index changed between two
    # commits (a config edit, say), the served version string is identical too. Both signals look
    # green while the edge is a commit behind. The only reliable test is against the LOCAL index:
    # if they differ, the CDN has not caught up, and a client updating now can fetch a stale index
    # paired with a fresh file and fail with "hash mismatch after download".
    here = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "index.toml")
    local = None
    if os.path.isfile(here):
        local = hashlib.sha256(open(here, "rb").read()).hexdigest()
        print("index local         : %s" % local[:20])
        print("index matches local : %s" % (actual == local))

    # 🔴 An INDEXED file is not a REACHABLE file, and nothing above notices the difference.
    # packwiz indexes whatever is on disk; anything untracked or gitignored is then absent from raw
    # main, and the client dies mid-download with "Update failed: HTTP 404" — AFTER the integrity
    # gate has passed, because the index itself is perfectly valid.
    #
    # This has now shipped THREE times: *.bak-* (2026-08-13), client-mods-temp (2026-08-17) and
    # .mcp.json (2026-09-09, pack 1.10.13, caught only by a player's screenshot). Every one was a
    # file that existed locally and not on main. The gate cannot see it; only fetching can.
    missing = check_reachable(index.decode("utf-8", "replace"))
    print("indexed files 404   : %s" % (len(missing) if missing else "none"))
    for f in missing[:10]:
        print("   MISSING           : %s" % f)

    if expected:
        fresh = version == expected and pack_version == expected
        synced = local is None or actual == local
        if missing:
            print()
            print("STATUS              : BROKEN — %d indexed file(s) 404 on raw main. Every updater"
                  % len(missing))
            print("                      client will fail. Add them to .packwizignore (or commit")
            print("                      them), run `packwiz refresh`, and push before announcing.")
            sys.exit(1)
        print()
        print("expected            : %s" % expected)
        if fresh and gate and synced:
            print("STATUS              : LIVE — edges are serving the current commit")
        elif fresh and gate and not synced:
            print("STATUS              : STALE INDEX — version strings match but the served index is")
            print("                      a commit behind. Do NOT tell players to update yet; they can")
            print("                      hit 'hash mismatch after download'. Wait ~5 min, re-run.")
        else:
            print("STATUS              : STALE — CDN still on the old commit, wait and re-run")
        if not (fresh and gate and synced):
            sys.exit(1)


if __name__ == "__main__":
    main()
