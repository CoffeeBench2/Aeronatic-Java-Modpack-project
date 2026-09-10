#!/usr/bin/env python3
r"""
Generate a reviewed account-transfer package for a player whose Minecraft NAME changed.

    py scripts/transfer_player.py <old-name> <new-name> [--set-display-name]

WHY A TRANSFER IS NEEDED AT ALL. The backend is `online-mode=false`, and since the transfer gate the
client connects DIRECTLY to it -- no proxy forwards an identity. So the server mints every UUID
itself as `md5("OfflinePlayer:" + username)` (v3), which means **the UUID is derived from the name**.
`players.uuid` is the primary key, and vanilla `playerdata/`, `advancements/` and `stats/` are keyed
on it too. A rename therefore does not rename anything -- it creates a brand-new player, and the old
account is orphaned rather than moved.

The Mojang UUID *is* available (the gate's cookie carries it) but arrives too late to help:
`CoffeesAeroAuth.handleAuthCookie` receives a `ServerPlayer` that already exists, so the UUID is
already fixed. It is only stashed in `VERIFIED_PREMIUM_UUID` for skin lookup.

WHY THIS EMITS FILES INSTEAD OF DOING IT. The DB credentials live only in the server-root `.env`, the
world lives on Lagless behind SFTP, and the world is ~40 GB and LIVE. A script that reached in and
mutated it would be both un-runnable from here and the wrong shape for the job. So this produces a
package to read, then run, in order -- and every destructive step is guarded.

⚠️ THE SERVER MUST BE STOPPED for the file half. `playerdata/*.dat` is held open and rewritten on
shutdown, so a running server will overwrite the rename.
⚠️ The live MySQL is SHARED WITH THE CREATIVE TEST SERVER (`players` included). This package touches
one player's rows, but be aware the change is visible to both.
"""
import argparse
import hashlib
import os
import sys
import uuid as _uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTBASE = r"D:\MC Project\Releases"
WORLD = "Season_2_Finale"

# Auto-created-profile guard. If the player already logged in under the new name, an empty profile
# exists at the destination UUID and must go before the old row can take its place. The DELETE is
# guarded on playtime so it can never remove a REAL account that happens to sit there.
AUTOCREATED_MAX_PLAYTIME = 3600      # seconds


def offline_uuid(name: str) -> _uuid.UUID:
    """Exactly what vanilla computes: Java's UUID.nameUUIDFromBytes over "OfflinePlayer:"+name.

    That is MD5 with the version nibble forced to 3 and the RFC-4122 variant bits set. Getting this
    wrong produces a plausible-looking UUID that matches nothing, so it is worth being literal.
    """
    d = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    d[6] = (d[6] & 0x0F) | 0x30
    d[8] = (d[8] & 0x3F) | 0x80
    return _uuid.UUID(bytes=bytes(d))


PREFLIGHT = """\
-- ============================================================================
-- 01 PREFLIGHT -- read this before running 02. Nothing here writes.
--   old {old}  {ouu}
--   new {new}  {nuu}
-- ============================================================================

-- (a) The account being moved. Expect exactly ONE row, with real playtime.
SELECT uuid, username, display_name, account_type, total_playtime, first_join,
       last_seen, discord_id, name_changes_used, name_approved
  FROM players WHERE uuid = '{ouu}';

-- (b) The destination. Two acceptable outcomes:
--       0 rows                -> he has not logged in under the new name yet. Ideal.
--       1 row, tiny playtime  -> an auto-created empty profile. 02 deletes it.
--     🔴 1 row with REAL playtime/discord_id means the UUID is taken by someone else.
--        STOP. Do not run 02 -- it would delete a live account.
SELECT uuid, username, display_name, account_type, total_playtime, first_join, discord_id
  FROM players WHERE uuid = '{nuu}';

-- (c) Anything else already claiming either NAME (display_name is globally unique).
SELECT uuid, username, display_name, account_type, total_playtime
  FROM players WHERE username IN ('{old}', '{new}')
             OR display_name IN ('{old}', '{new}');

-- (d) Side tables that carry the uuid.
SELECT 'trusted_ips' t, uuid, COUNT(*) n FROM trusted_ips
  WHERE uuid IN ('{ouu}','{nuu}') GROUP BY uuid
UNION ALL
SELECT 'sessions',     uuid, COUNT(*) FROM sessions
  WHERE uuid IN ('{ouu}','{nuu}') GROUP BY uuid
UNION ALL
SELECT 'name_queue',   uuid, COUNT(*) FROM name_queue
  WHERE uuid IN ('{ouu}','{nuu}') GROUP BY uuid;
"""

TRANSFER = """\
-- ============================================================================
-- 02 TRANSFER -- run only after reading 01.
--   {old}  {ouu}
--     ->  {new}  {nuu}
--
-- Re-keys the existing row rather than copying it, so every column carries over
-- (playtime, season totals, discord link, bio, skin, return point, room slot)
-- without this file having to know the column list.
-- ============================================================================

START TRANSACTION;

-- Clear the destination. Guarded: only removes a profile with under {guard}s of
-- playtime, i.e. one auto-created by a login under the new name. If 01(b) showed
-- a real account here, this deletes NOTHING and the UPDATE below fails on the
-- primary key -- which is the safe outcome, not a silent overwrite.
DELETE FROM players     WHERE uuid = '{nuu}' AND total_playtime < {guard};
DELETE FROM trusted_ips WHERE uuid = '{nuu}';
DELETE FROM name_queue  WHERE uuid = '{nuu}';

-- Move the account.
UPDATE players     SET uuid = '{nuu}', username = '{new}' WHERE uuid = '{ouu}';
UPDATE trusted_ips SET uuid = '{nuu}' WHERE uuid = '{ouu}';
UPDATE name_queue  SET uuid = '{nuu}' WHERE uuid = '{ouu}';

-- Sessions are short-lived and IP-bound; re-issue rather than migrate.
DELETE FROM sessions WHERE uuid IN ('{ouu}', '{nuu}');
{display}
-- Expect: 1 row, username = {new}, playtime unchanged from 01(a).
SELECT uuid, username, display_name, account_type, total_playtime, discord_id
  FROM players WHERE uuid = '{nuu}';

-- Expect: 0 rows.
SELECT COUNT(*) AS should_be_zero FROM players WHERE uuid = '{ouu}';

-- Read the two SELECTs above, then:
COMMIT;
-- ...or ROLLBACK; if either looks wrong.
"""

DISPLAY_ON = """
-- --set-display-name: also make the in-game display name match, WITHOUT spending
-- his one allowed change (name_changes_used is capped at 1 and is not consumed by
-- an admin-side rename).
UPDATE players SET display_name = '{new}', name_approved = TRUE,
                   name_approval_pending = FALSE, pending_display_name = NULL
  WHERE uuid = '{nuu}';
"""

DISPLAY_OFF = """
-- display_name deliberately UNCHANGED -- it is a separate, globally-unique column,
-- so he keeps showing as his old display name in chat/tab. Re-run with
-- --set-display-name if he wants it to follow the rename.
"""

FILES_SH = """\
#!/usr/bin/env bash
# ============================================================================
# 03 FILE MOVES -- run ON THE SERVER, with the server STOPPED.
#   cd to the directory that CONTAINS {world}/ , then: bash 03-files.sh
#
#   {old}  {ouu}
#     ->  {new}  {nuu}
#
# 🔴 STOPPED means stopped. playerdata/*.dat is held open and rewritten on
#    shutdown, so a running server will simply undo these renames.
# ============================================================================
set -uo pipefail

W="{world}"
OLD="{ouu}"
NEW="{nuu}"

if [ ! -d "$W/playerdata" ]; then
  echo "!! $W/playerdata not found -- run this from the directory containing $W/" >&2
  exit 1
fi
if pgrep -f 'server.jar' >/dev/null 2>&1; then
  echo "!! a server.jar process is running -- stop it first or these renames will be overwritten" >&2
  exit 1
fi

ts=$(date +%Y%m%d-%H%M%S)
BK="player-transfer-backup-$ts"
mkdir -p "$BK"
echo "backups -> $BK/"
echo

move() {{           # move <relative-path-without-ext> <ext>
  local dir="$1" ext="$2"
  local src="$W/$dir/$OLD$ext"
  local dst="$W/$dir/$NEW$ext"
  if [ ! -f "$src" ]; then
    echo "  --  $dir/$OLD$ext (absent, skipping)"
    return
  fi
  # Back BOTH sides up before touching either: the destination may be the empty
  # profile his new-name login created, and it is still evidence if this goes wrong.
  mkdir -p "$BK/$dir"
  cp -p "$src" "$BK/$dir/" 2>/dev/null
  if [ -f "$dst" ]; then
    cp -p "$dst" "$BK/$dir/$NEW$ext.destination-was" 2>/dev/null
    echo "  ->  $dir/$NEW$ext existed (auto-created by his new-name login) -- replacing, backed up"
    rm -f "$dst"
  fi
  mv "$src" "$dst"
  echo "  OK  $dir/$OLD$ext  ->  $NEW$ext"
}}

move playerdata    .dat
move playerdata    .dat_old
move advancements  .json
move stats         .json

echo
echo "done. Now run 04-sweep.sh to find UUID references the renames did not cover."
"""

SWEEP_SH = """\
#!/usr/bin/env bash
# ============================================================================
# 04 SWEEP -- find remaining references to the OLD uuid. Read-only.
#   Run from the directory containing {world}/ :  bash 04-sweep.sh
#
# Mod data is UUID-keyed in places no rename list can predict: FTB Teams
# membership, FTB Chunks claims, FTB Quests progress, Sable ship ownership,
# AeroClaims, Numismatics accounts, backpack ownership. Rather than guess the
# paths, ask the filesystem.
#
# Mods store the uuid in BOTH forms, so both are searched:
#   dashed    {ouu}
#   undashed  {obare}
# ...and as a 4-int NBT array, which a text grep CANNOT see -- so a clean result
# here is good news, not proof. Region files are zlib-compressed for the same
# reason: nothing text-based reads inside them.
# ============================================================================
set -uo pipefail

W="{world}"
DASH="{ouu}"
BARE="{obare}"

echo "searching for $DASH / $BARE"
echo

# Scoped to the dirs that hold structured data. Region files are compressed, so
# grepping them is noise, and they are also the bulk of the ~40 GB.
for d in "$W/data" "$W/playerdata" "$W/advancements" "$W/stats" "serverconfig" "world/data"; do
  [ -e "$d" ] || continue
  grep -rIl -e "$DASH" -e "$BARE" "$d" 2>/dev/null
done | sort -u | tee /tmp/aero-transfer-hits.txt

echo
n=$(wc -l < /tmp/aero-transfer-hits.txt | tr -d ' ')
if [ "$n" = "0" ]; then
  echo "no plain-text references left."
  echo "NOTE: FTB and friends often store uuids as NBT int-arrays, which this cannot see."
  echo "      Verify in game: his claims, team, quest progress and balance."
else
  echo "$n file(s) still reference the old uuid -- listed above."
  echo "Each needs a decision: edit the uuid, or have the player redo it (re-claim, re-join team)."
  echo "Back up any file before editing, and keep the server STOPPED while you do."
fi
"""

README = """\
# Account transfer: `{old}` -> `{new}`

Generated by `scripts/transfer_player.py`. Read this whole file first.

| | |
|---|---|
| old name | `{old}` |
| old uuid | `{ouu}` |
| new name | `{new}` |
| new uuid | `{nuu}` |
| world | `{world}` |
| display_name | {dispnote} |

The uuid is `md5("OfflinePlayer:" + name)` because the backend is `online-mode=false` and the gate
transfers clients to it directly. So the rename produced a different uuid, and both the auth DB and
vanilla playerdata key on it. Nothing moves by itself.

## Order

1. **`01-preflight.sql`** — read-only. Confirm the destination uuid is either empty or an
   auto-created profile. 🔴 If a real account sits there, stop and resolve it by hand.
2. **Stop the server.** Not restart — stop. `playerdata/*.dat` is rewritten on shutdown and will
   undo step 4.
3. **`02-transfer.sql`** — inside a transaction, ending in a `COMMIT` you run yourself after
   eyeballing two verification `SELECT`s.
4. **`03-files.sh`** — on the server, from the directory containing `{world}/`. Backs up both sides
   before moving, and refuses to run if it sees a `server.jar` process.
5. **`04-sweep.sh`** — read-only. Lists mod data still pointing at the old uuid.
6. **Start the server**, have him join, and check: playtime/level, Discord link, claims, FTB team,
   quest progress, balance, inventory, position.

## What this does not cover

`04-sweep.sh` only finds uuids stored as **text**. FTB and several others store them as NBT
int-arrays, and region files are zlib-compressed — neither is greppable. So a clean sweep means
"nothing obvious left", not "done". The in-game check in step 6 is the real verification.

## Stop doing this by hand

This is a workaround for a design issue, not a fix. Premium players carry a Mojang UUID that never
changes on rename, and the gate's cookie already contains it — but `handleAuthCookie` runs against a
`ServerPlayer` that already exists, so the login uuid is fixed by then. Reading the cookie in the
login/configuration phase and substituting the real Mojang uuid would make renames free forever.

🔴 One caveat that makes it a design decision rather than a patch: there are five paths where a
premium player currently resolves as OFFLINE (cookie timeout, missing secret, invalid cookie, absent
cookie, direct connect). Today that is cosmetic — same uuid, flagged offline. Under Mojang uuids it
would mean a *different* uuid, i.e. spawning as a new player with an empty inventory. That path must
**refuse entry** for a known-premium name rather than fall back.

The one-time migration cost scales with the number of premium players, so it is cheapest now.
"""


def main():
    ap = argparse.ArgumentParser(description="Generate an account-transfer package for a rename.")
    ap.add_argument("old_name")
    ap.add_argument("new_name")
    ap.add_argument("--set-display-name", action="store_true",
                    help="also set display_name to the new name (does not spend his one change)")
    ap.add_argument("--world", default=WORLD)
    a = ap.parse_args()

    if a.old_name.lower() == a.new_name.lower():
        sys.exit("old and new names are the same -- nothing to transfer")

    ouu, nuu = offline_uuid(a.old_name), offline_uuid(a.new_name)
    if ouu == nuu:
        sys.exit("both names hash to the same uuid -- refusing")

    out = os.path.join(OUTBASE, "player-transfer-%s-to-%s" % (a.old_name, a.new_name))
    os.makedirs(out, exist_ok=True)

    f = dict(old=a.old_name, new=a.new_name, ouu=str(ouu), nuu=str(nuu),
             obare=str(ouu).replace("-", ""), world=a.world,
             guard=AUTOCREATED_MAX_PLAYTIME)

    files = {
        "01-preflight.sql": PREFLIGHT.format(**f),
        "02-transfer.sql": TRANSFER.format(
            display=(DISPLAY_ON if a.set_display_name else DISPLAY_OFF).format(**f), **f),
        "03-files.sh": FILES_SH.format(**f),
        "04-sweep.sh": SWEEP_SH.format(**f),
        "README.md": README.format(
            dispnote=("set to `%s`" % a.new_name) if a.set_display_name
                     else "unchanged (still his old display name)", **f),
    }
    for name, body in files.items():
        with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(body)

    print("%-16s %s" % (a.old_name, ouu))
    print("%-16s %s" % (a.new_name, nuu))
    print()
    print("package: %s" % out)
    for name in files:
        print("   %s" % name)
    print()
    print("Run in order: 01 (read) -> STOP THE SERVER -> 02 -> 03 -> 04 -> start.")
    print("display_name: %s" % ("will be set to " + a.new_name if a.set_display_name
                                else "left unchanged (pass --set-display-name to change it)"))


if __name__ == "__main__":
    main()
