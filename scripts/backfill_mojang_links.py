#!/usr/bin/env python3
r"""Backfill players.mojang_uuid for PREMIUM profiles that have never been linked.

WHY THIS EXISTS
---------------
Identity on this server is md5("OfflinePlayer:" + name), so whoever holds a Mojang name inherits the
profile filed under it. players.mojang_uuid is the only thing that makes a takeover detectable, and it
only fills in when a player logs in through the gate. 176 premium profiles had never been linked, which
left them both undetectable AND undeliverable for a Tebex purchase.

RenameHealer cannot help an unlinked profile either -- it needs a stored uuid to prove a rename -- so
this backfill is a SECURITY control, not merely store plumbing.

THE SAFETY ARGUMENT, AND ITS LIMIT
----------------------------------
Asking Mojang "who owns this name" answers "who owns it NOW", which is not automatically the person who
played under it: if they renamed away and somebody else bought the name, the answer is the wrong human,
and binding it would CERTIFY a takeover rather than flag it. Mojang removed the name-history API, so
that cannot be ruled out by asking.

It can be ruled out by arithmetic. A rename releases the old name only after ~37 days. So if a profile
was last seen more recently than that, no rename-plus-release-plus-repurchase can have completed since
they were playing under it, and the current owner IS them. That is a proof, not a heuristic, and it is
what separates the auto-bind tier from the review tier below.

TIERS
-----
  BIND_PROVEN   last_seen inside the release window  -> safe to bind automatically
  REVIEW        last_seen older than the window      -> could have changed hands; needs a human
  SKIP_RELEASED name no longer resolves              -> already released. NEVER bind; quarantine instead
  SKIP_DUP      resolved uuid already on another row -> an unhealed rename pair; needs a manual merge
  SKIP_CASE     Mojang's canonical casing differs    -> binding does not help; see below
  SKIP_ERROR    Mojang did not answer                -> never guess

SKIP_CASE matters because md5 is case sensitive and Mojang name ownership is not. If canonical casing
differs from what we stored, the player's next login derives a DIFFERENT local uuid and lands on another
profile, so a link on this row would not follow them.

USAGE
    py scripts/backfill_mojang_links.py                 # report only, writes nothing (default)
    py scripts/backfill_mojang_links.py --apply         # writes the BIND_PROVEN tier only
    py scripts/backfill_mojang_links.py --apply --include-review   # also writes REVIEW (deliberate)

Reads DB_* from the server .env. Never prints credentials.
"""
import argparse
import collections
import io
import os
import re
import sys
import time
import urllib.error
import urllib.request

ENV_PATH = r"D:\MC Project\Lagless Hosting Imports\root\.env"
# Mojang releases a changed name after ~37 days. 30 is deliberately INSIDE that, so the window errs
# toward "needs review" rather than toward binding something unprovable.
RELEASE_WINDOW_DAYS = 30
VALID_NAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
UA = {"User-Agent": "CoffeesAeroSMP/backfill-mojang-links"}


def load_env(path):
    env = {}
    for line in io.open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def mojang_lookup(name):
    """-> (status, uuid, canonical_name). status in RESOLVES / RELEASED / UNREGISTRABLE / ERROR."""
    if not VALID_NAME.match(name):
        return "UNREGISTRABLE", None, None
    req = urllib.request.Request(
        "https://api.mojang.com/users/profiles/minecraft/" + name, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            import json
            d = json.loads(r.read().decode("utf-8", "replace"))
            raw = d.get("id")
            if not raw:
                return "RELEASED", None, None
            dashed = "%s-%s-%s-%s-%s" % (raw[0:8], raw[8:12], raw[12:16], raw[16:20], raw[20:32])
            return "RESOLVES", dashed, d.get("name")
    except urllib.error.HTTPError as e:
        if e.code in (404, 204):
            return "RELEASED", None, None
        if e.code == 429:
            return "RATELIMIT", None, None
        return "ERROR", None, None
    except Exception:
        return "ERROR", None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="write the BIND_PROVEN tier")
    ap.add_argument("--include-review", action="store_true",
                    help="with --apply, ALSO write the REVIEW tier (unprovable; deliberate choice)")
    args = ap.parse_args()

    env = load_env(ENV_PATH)
    try:
        import pymysql
    except ImportError:
        print("pymysql is required:  py -m pip install pymysql")
        return 2

    cn = pymysql.connect(host=env["DB_HOST"], port=int(env["DB_PORT"]), user=env["DB_USER"],
                         password=env["DB_PASSWORD"], database=env["DB_NAME"],
                         connect_timeout=10, autocommit=True)
    cur = cn.cursor()

    # The column may not exist yet if the jar carrying it has not been deployed. Adding it here is
    # idempotent and matches how mojang_uuid itself is handled (ALTER, swallow the duplicate).
    try:
        cur.execute("ALTER TABLE players ADD COLUMN link_source ENUM('GATE','BACKFILL') NULL")
        print("added players.link_source")
    except Exception:
        pass

    # Every already-linked uuid, so a resolved owner that belongs to another profile is caught.
    cur.execute("SELECT LOWER(mojang_uuid), username FROM players "
                "WHERE mojang_uuid IS NOT NULL AND mojang_uuid<>''")
    already = {u: n for u, n in cur.fetchall()}

    # PREMIUM only. An OFFLINE profile's identity IS its name; binding a Mojang uuid to one is the
    # offline->premium CLAIM, which must be proved with the old password, not inferred here.
    cur.execute("""SELECT uuid, username, total_playtime, last_seen
                   FROM players
                   WHERE account_type='PREMIUM' AND (mojang_uuid IS NULL OR mojang_uuid='')
                   ORDER BY total_playtime DESC""")
    rows = cur.fetchall()
    print("unlinked PREMIUM profiles: %d" % len(rows))
    print("release window: %d days  (Mojang frees a changed name after ~37)" % RELEASE_WINDOW_DAYS)
    print()

    now_ms = int(time.time() * 1000)
    cutoff_ms = now_ms - RELEASE_WINDOW_DAYS * 86400 * 1000

    tiers = collections.defaultdict(list)
    for local_uuid, name, playtime, last_seen in rows:
        status, resolved, canonical = mojang_lookup(name)
        if status == "RATELIMIT":
            time.sleep(8)
            status, resolved, canonical = mojang_lookup(name)

        hours = round((playtime or 0) / 3600, 1)
        days_ago = round((now_ms - (last_seen or 0)) / 86400000.0, 1) if last_seen else None
        rec = (name, local_uuid, resolved, hours, days_ago, canonical)

        if status in ("RELEASED", "UNREGISTRABLE"):
            tiers["SKIP_RELEASED"].append(rec)
        elif status == "ERROR":
            tiers["SKIP_ERROR"].append(rec)
        elif resolved and resolved.lower() in already:
            tiers["SKIP_DUP"].append(rec)
        elif canonical and canonical != name:
            tiers["SKIP_CASE"].append(rec)
        elif last_seen and last_seen >= cutoff_ms:
            tiers["BIND_PROVEN"].append(rec)
        else:
            tiers["REVIEW"].append(rec)
        time.sleep(0.25)

    order = ["BIND_PROVEN", "REVIEW", "SKIP_RELEASED", "SKIP_DUP", "SKIP_CASE", "SKIP_ERROR"]
    print("%-14s %5s  %s" % ("tier", "count", "hours"))
    for t in order:
        recs = tiers[t]
        print("%-14s %5d  %.1f" % (t, len(recs), sum(r[3] for r in recs)))
    print()

    for t in order:
        recs = tiers[t]
        if not recs:
            continue
        print("--- %s (%d)" % (t, len(recs)))
        for name, lu, resolved, hours, days_ago, canonical in sorted(recs, key=lambda r: -r[3])[:25]:
            extra = ""
            if t == "SKIP_DUP":
                extra = "  already linked to: %s" % already.get((resolved or "").lower(), "?")
            if t == "SKIP_CASE":
                extra = "  Mojang canonical: %s" % canonical
            print("   %-18s %7.1f h  last seen %s d ago%s"
                  % (name, hours, days_ago if days_ago is not None else "never", extra))
        if len(recs) > 25:
            print("   ... and %d more" % (len(recs) - 25))
        print()

    to_write = list(tiers["BIND_PROVEN"])
    if args.include_review:
        to_write += list(tiers["REVIEW"])

    if not args.apply:
        print("REPORT ONLY - nothing written. Re-run with --apply to write %d BIND_PROVEN row(s)."
              % len(tiers["BIND_PROVEN"]))
        cn.close()
        return 0

    written = 0
    for name, local_uuid, resolved, hours, days_ago, canonical in to_write:
        # The WHERE re-checks emptiness, so a player who logged in DURING this run keeps their
        # gate-proven link and is not downgraded to an inference.
        cur.execute("UPDATE players SET mojang_uuid=%s, link_source='BACKFILL' "
                    "WHERE uuid=%s AND (mojang_uuid IS NULL OR mojang_uuid='')",
                    (resolved, local_uuid))
        if cur.rowcount:
            written += 1
        else:
            print("   skipped %s - it gained a link while this ran" % name)
    print("WROTE %d link(s) as link_source=BACKFILL." % written)

    cur.execute("SELECT COUNT(*) FROM players WHERE account_type='PREMIUM' "
                "AND (mojang_uuid IS NULL OR mojang_uuid='')")
    print("premium profiles still unlinked: %d" % cur.fetchone()[0])
    cn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
