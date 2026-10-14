#!/usr/bin/env python3
r"""
Is GitHub's raw CDN serving the LOCAL s3/ channel yet, through the plain `main` path?

    py scripts/s3_served_check.py [--wait] [--skip-version]

Why: raw.githubusercontent.com caches every path ~300 s and IGNORES query strings, so after a push some
paths are fresh and others stale for up to 5 minutes. A pre-1.4.6 Core reads `main/...` paths, gets a
fresh index.toml next to a stale override file, and fails with "hash mismatch after download" (3.0.8,
2026-10-15). Core 1.4.6+ pins every run to one commit and is immune.

Release rule (two-phase): push everything EXCEPT s3/version.json, run this with --wait --skip-version
until it passes, THEN push version.json — clients only start an update when version.json flips, so
they never see a half-fresh snapshot.

Checks pack.toml, index.toml and every non-metafile + metafile in the index against local bytes.
Exit 0 = all fresh, 1 = stale paths remain.
"""
import argparse, hashlib, os, re, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S3 = os.path.join(ROOT, "s3")
RAW = "https://raw.githubusercontent.com/CoffeeBench2/Aeronatic-Java-Modpack-project/main/s3/"


def fetch(rel):
    req = urllib.request.Request(RAW + rel, headers={"User-Agent": "CoffeeBench2 s3_served_check"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def paths(skip_version):
    idx = open(os.path.join(S3, "index.toml"), encoding="utf-8").read()
    out = ["pack.toml", "index.toml"] + re.findall(r'(?m)^file = "([^"]+)"', idx)
    if not skip_version:
        out.append("version.json")
    return out


def stale(rels):
    bad = []
    for rel in rels:
        local = open(os.path.join(S3, rel), "rb").read()
        try:
            served = fetch(rel)
        except Exception as e:
            bad.append("%s (%s)" % (rel, e))
            continue
        if hashlib.sha256(served).digest() != hashlib.sha256(local).digest():
            bad.append(rel)
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wait", action="store_true", help="poll until fresh (max 12 min)")
    ap.add_argument("--skip-version", action="store_true", help="ignore s3/version.json (phase 1)")
    a = ap.parse_args()
    rels = paths(a.skip_version)
    deadline = time.time() + 720
    while True:
        bad = stale(rels)
        if not bad:
            print("served == local for all %d paths" % len(rels))
            return 0
        print("%d stale: %s" % (len(bad), ", ".join(bad[:6]) + (" ..." if len(bad) > 6 else "")))
        if not a.wait or time.time() > deadline:
            return 1
        time.sleep(30)


if __name__ == "__main__":
    sys.exit(main())
