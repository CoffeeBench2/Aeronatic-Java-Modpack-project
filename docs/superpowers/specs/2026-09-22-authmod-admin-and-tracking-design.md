# CoffeesAeroAuth — Admin Tooling & Player Tracking

**Date:** 2026-09-22
**Target version:** auth **1.11.0** (current local build: 1.10.12)
**Status:** design approved, plan not yet written

Six owner-requested features land in one release: a fresh-start wipe, real player tracking,
a confiscation (detain) mode, an assembly-time exploit alert, `/invsee` + `/invsee_echest`, and
hidden-op anonymity on leave as well as join.

---

## 1. Goals

| # | Feature | One-line goal |
|---|---|---|
| F1 | Fresh start | Wipe a player's *progress* from the world while keeping their account |
| F2 | Tracking & stats | Record session history, activity counters, world footprint and infractions per player |
| F3 | Confiscation mode | Freeze a player completely so they must listen to an admin |
| F4 | Exploit alert | Detect an Item Drain assembled onto a Swivel Bearing and ping admins in the watchdog channel |
| F5 | invsee | View — and for online players, edit — a player's inventory and ender chest |
| F6 | Hidden-op anonymity | A hidden op's join **and leave** are announced nowhere players can see — in game or in the public Discord feed |

## 2. Non-goals

These are out of scope **by decision**, not by oversight. Each is stated because a reader will
otherwise assume it is covered.

- **F1 does not touch third-party mod data.** FTB Teams membership, FTB Chunks claims, Waystones,
  graves and AeroClaims ship claims live in other mods' storage and are not safely reachable from
  this jar. The command prints a manual checklist instead of pretending it handled them.
- **F4 does not prevent anything.** Alert-only, by owner decision. No assembly is refused, no block
  is removed. A future `exploitRefuseAssembly` flag is explicitly *not* part of this release.
- **F5 never writes an offline player's `.dat`.** Offline viewing is a read-only snapshot. Writing
  an offline `.dat` is how inventories get duped or lost.
- **No module split.** OPEN WORK item A (splitting the 21k-line auth monolith) stays deferred. Each
  feature here goes in its own small package so that split gets easier, not harder.
- **No new surfaces for F2.** Stats are readable in-game only. No Discord command, no public
  `/profile` exposure, no Obsidian export in this release.
- **F6 does not silence the watchdog channel.** The admin-only webhook keeps receiving hidden-op
  joins and leaves, under the player's real account name. That channel exists so admins see
  everything, and it is not a surface players can see.

## 3. Inherited constraints

These are established facts about this codebase and server. Violating any one of them has already
caused a production incident.

1. **One jar, two roles.** The same jar runs the lobby and the SMP, switched by `serverRole`
   (`AuthConfig`). F1–F5 are SMP-only and must be role-gated at registration. **F6 is the
   exception — it must run on BOTH**, because a hidden op passing through the lobby would otherwise
   announce themselves there.
2. **The new MySQL tables are shared with the creative test server.** Both point at
   the same database. Any destructive write path must be safe to run against live rows.
3. **Nothing on the join path may block on the database.** The DB is now on the game host and the
   old cross-continent RTT is gone, but that is headroom, not licence. All F2 writes go through
   `util/AsyncIo.submit(Runnable)`.
4. **A handler that teleports must not run on `PlayerTickEvent`.** `ServerGamePacketListenerImpl.tick()`
   calls `absMoveTo` immediately after `doTick()`, which reverts the move while leaving
   `awaitingPositionFromClient` armed — the player can then never move again. This froze the lobby on
   2026-09-07. F3's movement freeze stays on `EntityTickEvent.Pre`, reusing the existing unauth path.
5. **`PlayerLoggedOutEvent` fires *before* vanilla saves the `.dat`.** It is the first statement of
   `PlayerList.remove`. F1 must not act on that event; it must confirm
   `server.getPlayerList().getPlayer(uuid) == null` on a later tick.
6. **`require=0` on a mixin means silent failure** — no crash, no feature, no log line. F4's and
   F6's mixins all use `require=1`; for F6 a silent failure means hidden ops are publicly announced,
   which is strictly worse than a loud boot failure.
7. **MySQL has no `ADD COLUMN IF NOT EXISTS`.** New columns follow the existing
   `DatabaseManager` pattern: issue the `ALTER TABLE` and swallow the duplicate-column error.
8. **`AccountTransfer` must run on the server thread**, because it reads `PlayerList`. F1 inherits
   this rule and the same explicit thread assertion.

---

## 4. F1 — Fresh start

### Command

```
/authmod freshstart <playername>            → dry run: prints the plan, changes nothing
/authmod freshstart <playername> confirm    → executes
```

Permission 2 (inherited from the `/authmod` root). Name-based `StringArgumentType.word()`, **never**
`EntityArgument` — the target is usually offline, and an online-only argument type would reject the
common case. This mirrors `/authmod transferaccount` exactly.

### Execution order

1. **Resolve** the offline UUID via `AccountTransfer.offlineUuid(name)` — `md5("OfflinePlayer:" + name)`.
2. **Refuse** if the profile does not exist (catches a typo before anything is deleted).
3. **Refuse** if MySQL is down. A wipe that clears files but cannot clear profile columns leaves a
   half-wiped player, and the flat-file fallback would then disagree with the DB.
4. **If online:** kick with a message, then re-enter on a later tick and poll until
   `getPlayerList().getPlayer(uuid) == null`. Give up with a clear error after 10 seconds rather
   than racing the save.
5. **Back up first.** Copy all four files to
   `<world>/coffeesaeroauth/freshstart-backups/<name>-<yyyyMMdd-HHmmss>/`. A wipe with no backup is
   not shipped. Copy failure aborts the whole command.
6. **Delete** the four per-player files.
7. **Reset** the progress columns in MySQL, through `AsyncIo`, and invalidate the `ProfileStore`
   cache entry — reads are cache-first, so a stale entry would answer for the rest of the uptime.
8. **Report** what was deleted, what was kept, the backup path, and the manual checklist.

### What is deleted

Reuse `AccountTransfer`'s `Sub` record and `SUBS` table — the same four stores it already moves:

| Path | Resource |
|---|---|
| `playerdata/<uuid>.dat` | `LevelResource.PLAYER_DATA_DIR` |
| `playerdata/<uuid>.dat_old` | `LevelResource.PLAYER_DATA_DIR` |
| `advancements/<uuid>.json` | `LevelResource.PLAYER_ADVANCEMENTS_DIR` |
| `stats/<uuid>.json` | `LevelResource.PLAYER_STATS_DIR` |

The ender chest rides inside the `.dat` as the `EnderItems` list tag, so it goes with it. Numismatics
coins are physical items in the inventory, so they go too. `/home` is the vanilla bed/respawn point
(`AuthManager.handleHome`) with no stored data of ours — nothing to wipe.

### What is reset in MySQL

`total_playtime_seconds`, `season1_playtime_seconds`, `session_start_epoch`, `first_join_complete`,
`startup_bonus_given`, `return_dim`, `return_x`, `return_y`, `return_z`.

⚠️ **Resetting playtime resets their level.** The sidebar level is `1 + floor(2·√hours)` and is
playtime-only. A fresh start drops the player back to Lv 1. This is intended — playtime is progress —
but it is the most visible side effect and the command's dry-run output must say so.

### What is kept

`password_hash` / `password_salt`, `display_name`, `discord_id`, `account_type`, `name_approved`,
`join_date`, `first_ip`, `skin_url`, `cape_enabled`, `skin_changes_used`, and all `trusted_ips`
rows. The player logs back in normally with the same name and password and starts over.

### Manual checklist printed after a run

Not handled by this command, listed so the admin can finish the job:
FTB Teams membership · FTB Chunks claims · AeroClaims ship claims · Waystones · graves ·
any Sable sub-levels (ships) they own.

### New files

`admin/FreshStart.java` (plan/execute, mirroring `AccountTransfer`'s `Result` record and
plan-then-confirm shape), plus the command branch in `commands/ProfileCommands.java`.

---

## 5. F2 — Player tracking & stats

Four stores under a new `tracking/` package. Every write goes through `AsyncIo.submit`.

### 5.1 Session history — `PlayerSessionLog`

One row per session, written on logout. Safe to write from `PlayerLoggedOutEvent` because it is our
own table, not the vanilla `.dat`.

```sql
CREATE TABLE IF NOT EXISTS session_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  uuid         CHAR(36)     NOT NULL,
  login_epoch  BIGINT       NOT NULL,
  logout_epoch BIGINT       NOT NULL,
  duration_s   INT          NOT NULL,
  ip           VARCHAR(45)  NULL,
  server_role  VARCHAR(8)   NOT NULL,   -- 'SMP' or 'LOBBY'
  reason       VARCHAR(32)  NULL,       -- QUIT / KICK / TIMEOUT / TRANSFER / AFK
  -- Composite: the only read is WHERE uuid=? ORDER BY logout_epoch DESC LIMIT n.
  -- Two single-column indexes cannot serve filter and sort together.
  INDEX idx_session_uuid_logout (uuid, logout_epoch DESC)
);
```

`server_role` exists because the lobby and the SMP share this table and **run different clocks** —
the lobby is UTC, the SMP is +05:30. All epochs are stored as UTC millis; only rendering is
localised. Session duration comes from `sessionStartEpoch`, the same field the six existing playtime
consumers read; it is never recomputed independently.

### 5.2 Activity counters — `ActivitySampler`

**Sampled, not event-hooked.** A `Repeating` task every 5 minutes, plus a final sample on logout,
reads `player.getStats()` and upserts one row per player.

This is a deliberate design choice. Per-event listeners on inventory and world activity are how this
server lost 7.89% of the server thread to 9,168 recipe-advancement listeners, and
`EntityTickEvent`-shaped subscriptions cost a whole-world scan regardless of what the handler does.
Sampling costs one map read per online player per 5 minutes.

```sql
CREATE TABLE IF NOT EXISTS player_stats (
  uuid           CHAR(36) PRIMARY KEY,
  blocks_mined   BIGINT NOT NULL DEFAULT 0,
  items_used     BIGINT NOT NULL DEFAULT 0,
  deaths         INT    NOT NULL DEFAULT 0,
  mob_kills      INT    NOT NULL DEFAULT 0,
  player_kills   INT    NOT NULL DEFAULT 0,
  distance_cm    BIGINT NOT NULL DEFAULT 0,
  chat_messages  BIGINT NOT NULL DEFAULT 0,
  commands_run   BIGINT NOT NULL DEFAULT 0,
  sampled_epoch  BIGINT NOT NULL
);
```

Sources: `Stats.BLOCK_MINED` and `Stats.ITEM_USED` summed over their registries;
`Stats.CUSTOM` for `DEATHS`, `MOB_KILLS`, `PLAYER_KILLS`; `WALK_ONE_CM` + `SPRINT_ONE_CM` +
`FLY_ONE_CM` for distance. `chat_messages` and `commands_run` are our own counters, incremented in
`events/ChatEvents` and the existing `CommandEvent` listener — both already run on every message and
command, so this adds a `long++`, not a new subscription.

⚠️ Vanilla stats are per-world and survive nothing that F1 deletes. After a `/authmod freshstart` the
sampled values legitimately drop to zero; `ActivitySampler` must **overwrite** on upsert, never
`GREATEST(old, new)`, or a wiped player keeps ghost counters forever.

### 5.3 World footprint — `FootprintSampler`

Ships owned and chunks claimed, piggybacking the **existing** `SableShips` census schedule rather
than adding a timer. `SableShips.census()` is already documented server-thread-only and read-only;
this reads the result it already produces.

```sql
CREATE TABLE IF NOT EXISTS player_footprint (
  uuid           CHAR(36) PRIMARY KEY,
  ships_owned    INT    NOT NULL DEFAULT 0,
  chunks_claimed INT    NOT NULL DEFAULT 0,
  sampled_epoch  BIGINT NOT NULL
);
```

Where ownership cannot be resolved (an unnamed or unattributed sub-level), the count is omitted
rather than guessed. `SableShips` already degrades every lookup failure to "unknown" and logs once;
that behaviour is inherited, not re-implemented.

### 5.4 Infraction log — `InfractionLog`

```sql
CREATE TABLE IF NOT EXISTS infractions (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  uuid    CHAR(36)     NOT NULL,
  type    VARCHAR(24)  NOT NULL,  -- CHAT_FILTER | WATCHDOG | CONFISCATE | RELEASE | WARN | BAN | NAME_REJECT
  detail  VARCHAR(512) NULL,
  actor   VARCHAR(64)  NULL,      -- admin name, or 'system'
  epoch   BIGINT       NOT NULL,
  INDEX idx_infraction_uuid_epoch (uuid, epoch DESC)
);
```

Written from call sites that **already exist** — `chat/ChatFilter`, `watchdog/WatchdogManager`,
F3's confiscate/release, the warn and ban branches in `commands/ProfileCommands`, and
`lobby/NameApprovalQueue`. No new event subscriptions.

### 5.5 Surface — `/authmod player <name>`

The existing branch grows into a paged stat sheet: identity and account, playtime and level, last
five sessions, activity counters, footprint, and infraction count with the most recent three.

The command reads four tables. It runs the query on `AsyncIo`, then posts the formatted result back
with `server.execute` — an admin command must not block the tick loop on four `SELECT`s, even
against a local DB.

---

## 6. F3 — Confiscation mode

New package `moderation/`.

### Commands

```
/authmod confiscate <player> [reason]   → hold
/authmod release <player>               → release
/authmod confiscate list                → who is currently held, since when, and why
```

Permission 2. **An op (permission 4) can never be confiscated** — the check is in the command, so
you cannot lock yourself or another admin out.

Manual release only, per owner decision. The hold survives relog **and** restart.

### State

```sql
CREATE TABLE IF NOT EXISTS confiscations (
  uuid          CHAR(36) PRIMARY KEY,
  reason        VARCHAR(256) NULL,
  actor         VARCHAR(64)  NOT NULL,
  started_epoch BIGINT       NOT NULL
);
```

`Confiscation` keeps an in-memory `Set<UUID>` as the hot path — the enforcement predicate runs every
tick and must never touch the DB. The set is loaded once at boot and mutated in lockstep with the
table; the table is the durable copy, the set is the one that is read.

A row present means held. Release deletes the row and writes a `RELEASE` infraction, so the history
lives in `infractions` rather than as a dead `active` column here.

### Enforcement

One predicate, ORed into the existing gate:

```java
// events/PlayerRestrictEvents
private static boolean shouldBlock(Entity e) {
    ... existing unauthenticated check ...
    || (e instanceof ServerPlayer p && Confiscation.isHeld(p.getUUID()));
}
```

That single change inherits every handler already registered against `shouldBlock`: block break,
block place, right-click block, right-click item, attack entity, item pickup, item drop and
container open. No new event subscriptions, and no second implementation of "this player may not
act" to drift out of sync with the first.

**Movement freeze** reuses the existing unauthenticated freeze inside
`PlayerRestrictEvents.onPlayerTick(EntityTickEvent.Pre)` — the same `teleportTo` path that already
runs for players awaiting login.

🔴 This handler stays on `EntityTickEvent.Pre`. It must not be "optimised" onto `PlayerTickEvent`;
see constraint 4. The class comment already documents this at length and that comment must be
extended to mention confiscation, so the next reader sees both users of the freeze.

**Commands** are blocked through the existing `CommandEvent` listener, using the same root-literal
approach as `CombatGuard.BLOCKED_WHILE_TAGGED` — but inverted: for a held player, *every* root is
blocked except a config allow-list (`confiscateAllowedCommands`, default empty).

**Chat stays open.** A confiscated player has to be able to answer the admin. Every message they
send while held is mirrored to the watchdog channel at `Severity.LOW`, so the exchange is on the
record.

**On join**, a held player is told they are held, by whom and why, and the freeze engages
immediately. On `/authmod release` they get a message and normal play resumes with no restart.

### New files

`moderation/Confiscation.java` (state + predicate + persistence), plus command branches and the
one-line hook in `PlayerRestrictEvents`.

---

## 7. F4 — Item Drain assembled on a Swivel Bearing

New package `exploit/`.

### Background — what "Swivel Bearing" actually is

`simulated:swivel_bearing`, from the **`simulated`** mod nested inside
`create-aeronautics-bundled-1.21.1-1.3.2.jar` at
`META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.2.jar`. It is not a
Create block, and it does not produce a Create `Contraption` — it assembles a **Sable sub-level**.
Searching the pack for "aeronautics" misses it; the bundle ships four mod ids in one jar.

### Hook points — both verified against the shipped jars

| Target | Signature | Covers |
|---|---|---|
| `dev.simulated_team.simulated.util.assembly.SimAssemblyContraption` | `public boolean searchMovedStructure(Level, BlockPos)` | Swivel Bearing, Physics Assembler |
| `com.simibubi.create.content.contraptions.Contraption` | `public boolean searchMovedStructure(Level, BlockPos, Direction)` | every Create-family bearing, incl. Aeronautics' Propeller Bearing and Offroad's Borehead Bearing |

Accessors confirmed by `javap`:

- `SimAssemblyContraption` — `public final BlockPos anchor`, `public Collection<BlockPos> getBlocks()`
- `Contraption` — `public BlockPos anchor`, `protected Map<BlockPos, StructureBlockInfo> blocks`
  (reachable with `@Shadow` from a mixin on the class itself)

Both methods *search* the structure and return before any block is moved, so at `RETURN` the blocks
are still readable in the main level by position. That is what makes one cheap scan possible.

### `AssemblyScanner`

Called from both mixins with `(Level, BlockPos anchor, Collection<BlockPos> positions)`:

1. Return immediately if `exploitDetectEnabled` is false or the flagged-block set is empty.
2. Look up the anchor's block id. Return unless it is in `exploitAssemblerBlocks` (the literal `"*"`
   in that list means "any assembler").
3. Walk the positions, resolving each block state, counting flagged matches. Stop at
   `exploitScanMaxBlocks` (default 20,000) and log once if the cap is hit — the scan must never be
   able to cost an unbounded tick on a huge ship.
4. On a non-zero count, raise the alert.

Both mixins use **`require=1`**. A silent `require=0` failure would leave no feature and no error,
which is exactly the failure mode that made an EMI/JEI mixin look fine while doing nothing. Each
mixin logs one line on first application so its presence is provable from the log.

### The alert

```java
WatchdogEvent.of(Severity.HIGH, "Flagged block assembled", "None — alert only",
    "Flagged",   "create:item_drain ×3",
    "Assembler", "simulated:swivel_bearing",
    "Location",  "minecraft:overworld  -1421, 78, 305",
    "Nearest",   "coffee — 4.2m away",
    "Nearby",    "coffee 4.2m, GeneralBronze 18.7m, SweetYuzu 27.1m",
    "Claim",     "<team or ->");
```

🔑 **`Severity.HIGH` is load-bearing.** `WatchdogManager` adds `content: "<@&adminRole>"` with
`allowed_mentions` only when `severity.ordinal() >= Severity.HIGH.ordinal()`. Embeds alone notify
nobody. HIGH is therefore the minimum severity that satisfies "mention admins", and dropping this to
MEDIUM would silently remove the ping.

**Deduplication:** keyed on `(anchorPos, flaggedBlockId)` with a cooldown of
`exploitAlertCooldownSeconds` (default 600). A bearing that assembles and disassembles in a redstone
loop must not be able to spam-ping the admin role.

### ⚠️ Attribution is best-effort, and the alert says so

`SwivelBearingBlockEntity.assemble()` takes no player — assembly is triggered by rotation, redstone
or `assembleNextTick`, and the `AssemblePacket` path is only one of several. There is no reliable
"who did this" at the hook.

The alert therefore reports **who was around when it happened**, under field names that say exactly
that. It never names a culprit. An admin gets a location and a ranked list of who to ask.

- **`Nearest`** — the single closest player in that dimension, with distance, **at any range**. Not
  capped at the 32-block radius: if the closest person was 180 m away that is itself the finding,
  because it means the assembly was almost certainly redstone-driven rather than hand-triggered.
- **`Nearby`** — every player within 32 blocks, **sorted nearest first with distances**. An unsorted
  list makes an admin guess; the ordering is the actual signal about who to talk to.
- **`Claim`** — the claim owner at that position, which is often more useful than proximity because
  the builder may be offline entirely.

Distances are rounded to one decimal and measured from the anchor block centre. If nobody is online
in that dimension, both player fields read `—` rather than being omitted, so the absence is explicit
rather than looking like a formatting failure.

### Config — `defaultconfigs/coffees_aero_auth-server.toml`

```toml
[exploit]
    exploitDetectEnabled        = true
    exploitFlaggedBlocks        = ["create:item_drain"]
    exploitAssemblerBlocks      = ["simulated:swivel_bearing"]
    exploitScanMaxBlocks        = 20000
    exploitAlertCooldownSeconds = 600
```

A new exploit becomes a config edit and a restart, not a rebuild. Unknown block ids in either list
are logged once at boot and ignored — a typo must not disable the whole detector silently.

### New files

`exploit/AssemblyScanner.java`, `mixin/SimAssemblyContraptionMixin.java`,
`mixin/CreateContraptionMixin.java`, plus config keys in `config/AuthConfig.java`.

---

## 8. F5 — `/invsee` and `/invsee_echest`

New package `invsee/`. Permission 3 (above the `/authmod` root's 2 — looking inside a player's
belongings is a higher bar than reading their profile).

### Online — live and editable

**Ender chest.** `player.getEnderChestInventory()` returns a `PlayerEnderChestContainer`: a real
27-slot `Container`. `ChestMenu.threeRows(id, viewerInventory, target)` over it is live and editable
with no adapter at all.

**Main inventory.** `Inventory` implements `Container` with 41 slots, which is not a vanilla menu
size. A `PlayerInventoryView` adapter presents 45 slots over the live inventory:

| View slots | Backing |
|---|---|
| 0–35 | main inventory |
| 36–39 | armor |
| 40 | offhand |
| 41–44 | inert filler (barrier item, clicks cancelled) |

Reads and writes pass straight through to the live `Inventory`, so an admin removing an item removes
it from the player, and the player sees it go. `setChanged` triggers
`ServerPlayer.containerMenu.broadcastChanges()` on the target so their client stays in sync.

### Offline — read-only snapshot

Read `playerdata/<uuid>.dat` with `NbtIo`, parse the `Inventory` and `EnderItems` list tags into a
`SimpleContainer` snapshot, and open it with every click cancelled and a title marked
`§c[READ-ONLY]`. The snapshot is never written back. This is the whole of offline support and it is
deliberately one-directional.

### Two integration hazards

1. **The lobby menu-slam guard.** `PlayerRestrictEvents.onPlayerTick` closes any menu that is not
   the player's own inventory, for anyone below permission 4. An invsee opened at permission 3 in
   the lobby would be slammed shut on the next tick. Invsee menus need an explicit exemption, using
   the same mechanism as the existing Easy NPC exemption (`isEasyNpcMenu`) — a marker interface on
   our menu, checked alongside it.
2. **The target disconnecting mid-view.** A live menu backed by a departed player's `Inventory`
   writes into an object vanilla is about to discard. On `PlayerLoggedOutEvent`, close every invsee
   menu currently viewing that player. `PlayerLoggedOutEvent` firing before the save is *correct*
   here — closing before the save is exactly what is wanted.

### Auditing

Every open writes `WatchdogManager.recordAdminCommand(op, "/invsee <target>")`. An admin looking
inside someone's belongings belongs on the record, the same as any other moderation action.

### New files

`invsee/InvseeMenu.java`, `invsee/PlayerInventoryView.java`, `invsee/InvseeManager.java`
(open-tracking + logout close), `commands/InvseeCommands.java`.

---

## 9. F6 — Hidden-op anonymity on join and leave

### Current state, audited

`/authmod hide` toggles `display/HiddenOps`, which persists to `hidden_ops.json` and survives a
restart. Four surfaces announce a player arriving or leaving, and only three of them respect it:

| Surface | Hidden op joins | Hidden op leaves |
|---|---|---|
| In-game chat line | ✅ swallowed by `mixin/JoinMessageMixin` | 🔴 **ANNOUNCED — this is the bug** |
| Discord public feed | ✅ gated by `DiscordBridge.isHidden(player)` | ✅ gated by the same check |
| Discord watchdog feed | posted (deliberate, admin-only) | posted (deliberate, admin-only) |

So the public Discord half of this request is already done, and was done deliberately — the
`isHidden` javadoc in `DiscordBridge` records the reasoning. **The single real gap is the in-game
leave line.** A hidden op can join invisibly and then announce themselves to the whole server by
logging out, which defeats the feature entirely.

### The fix — `LeaveMessageMixin`

Vanilla `PlayerList.remove(ServerPlayer)` calls `broadcastSystemMessage` directly with
`Component.translatable("multiplayer.player.left", player.getDisplayName())`. There is no cancellable
event for it, so it is redirected — the same technique `JoinMessageMixin` already uses for the join
line, and for the same reason.

```java
@Mixin(PlayerList.class)
public abstract class LeaveMessageMixin {
    @Redirect(method = "remove",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/players/PlayerList;"
                              + "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
              require = 1)
    private void aeroauth$leaveLine(PlayerList list, Component message, boolean overlay,
                                    ServerPlayer player) {
        if (HiddenOps.isHidden(player.getUUID())) return;   // swallow entirely
        ... rebuild with PlayerDisplay.segments(..., Surface.JOIN, false) ...
        list.broadcastSystemMessage(rebuilt, overlay);
    }
}
```

`Surface.JOIN` is already documented as "Join/leave line", so the leave message gets the same badge,
staff tag, clan tag and colour treatment the join line gets — this mixin fixes the anonymity gap and
closes a cosmetic inconsistency in the same stroke.

Like the join line, it keeps the vanilla translatable (`multiplayer.player.left`) so the sentence
stays localised, and only substitutes the name argument. Any exception falls back to broadcasting
the original message — a rendering failure must never *lose* a leave announcement for a
non-hidden player.

### 🔴 Both mixins move to `require = 1`

`JoinMessageMixin` currently carries an explicit `require = 0`, overriding the mixin config's
`defaultRequire: 1`. Its own javadoc says: *"Failure is therefore SILENT — verify it applied, never
assume."*

That trade-off was chosen to protect boots. It is the wrong trade-off for an anonymity feature: a
silently-unapplied mixin means **hidden ops are announced to everyone**, with no error anywhere, and
the person relying on being invisible has no way to know. Silent exposure is worse than a loud boot
failure.

Both mixins therefore use `require = 1`. The risk this accepts is a refused boot if a NeoForge
update moves `placeNewPlayer` or `remove` — which is caught immediately, at boot, on the first
restart after the update, instead of leaking for weeks. `PlayerList` is a vanilla class pinned at
1.21.1, so the odds of that are low and the failure is loud.

⚠️ This means the **first boot after deploying 1.11.0 is the test** for the join mixin as well as
the leave mixin. If `JoinMessageMixin` has silently been dead in production, raising it to
`require = 1` is what will finally reveal that — as a refused boot. Roll back to 1.10.12 and
re-target the redirect if so; do not simply restore `require = 0`, which would restore the bug.

### New and modified files

**New:** `mixin/LeaveMessageMixin.java`
**Modified:** `mixin/JoinMessageMixin.java` (`require = 0` → `1`) ·
`coffees_aero_auth.mixins.json` (register `LeaveMessageMixin` under `server`)

No config key and no new data. `HiddenOps` is read as-is; nothing about the toggle changes.

---

## 10. Data model summary

Five new tables — four from F2 plus `confiscations` from F3 — created in `db/DatabaseManager`
alongside the existing
`players` / `trusted_ips` / `sessions` / `name_queue` / `ip_bans` / `server_flags`, using the same
`CREATE TABLE IF NOT EXISTS` + swallow-the-duplicate-error pattern:

`session_log` · `player_stats` · `player_footprint` · `confiscations` · `infractions`

No changes to the `players` table. F1 only resets columns that already exist.

⚠️ All five are visible to the creative test server, which shares this database. `confiscations` is
the one that matters: a player held on live is also held on test. That is acceptable — it fails
safe — but it must be written down so it is not diagnosed later as a bug.

---

## 11. Testing

| Layer | What |
|---|---|
| Unit | `AssemblyScanner` match/cap/cooldown logic against a fake block lookup; `PlayerInventoryView` slot mapping including the inert range; `FreshStart.plan()` refusal cases (no profile, DB down, still online). Follows the existing `ClearScheduleTest` / `ShipCensusTest` pattern. |
| Boot | Clean boot with all six features registered; confirm the two F4 mixin "applied" log lines, and that the server boots at all with `JoinMessageMixin`/`LeaveMessageMixin` at `require = 1`. |
| Live | Each feature exercised in game — see below. |

🔴 **A clean boot does not validate F4.** Both mixin targets are loaded lazily, on the first
contraption assembly of the run. The boot test must be followed by *actually assembling a
contraption*, or the mixins are unverified regardless of how clean the log looks.

🔴 **The first boot IS the F6 test.** Raising the two `PlayerList` mixins to `require = 1` turns a
silent no-op into a refused boot. If the server refuses to start, `JoinMessageMixin` had already
been dead in production and hidden ops were being announced — re-target the redirect, do not restore
`require = 0`.

Live checklist:

1. **F1** — wipe a throwaway account while online. Confirm the kick, the backup folder, the four
   deleted files, level back to 1, and that login with the old password still works.
2. **F2** — join, mine, die, log out; confirm one `session_log` row and non-zero counters.
3. **F3** — confiscate a test account: verify it cannot move, break, place, open a chest or run a
   command; verify chat still works and mirrors to Discord; relog and confirm the hold survives;
   restart and confirm it survives that too; release and confirm normal play resumes.
   **Then confirm an ordinary authenticated player can still move** — that is the regression the
   09-07 lobby freeze would reproduce, and it cannot be caught by watching the held player.
4. **F4** — assemble an Item Drain on a Swivel Bearing; confirm the alert, the admin-role ping, the
   coordinates, and that re-assembling within 10 minutes does **not** re-ping. Then assemble a
   normal contraption and confirm silence.
5. **F5** — invsee an online player (move an item both ways, confirm they see it), invsee an offline
   player (confirm read-only), open one in the lobby (confirm it is not slammed shut), and have the
   target log out while the menu is open (confirm it closes cleanly).
6. **F6** — with a second account watching chat: `/authmod hide`, then join and leave. Confirm
   **no** line in chat for either, and **no** post in the public Discord feed for either. Then
   unhide and repeat: confirm both lines return, and that the leave line now carries the badge and
   clan tag the join line already had. Finally confirm the watchdog channel received all four
   events throughout — F6 must not have silenced the admin feed.

## 12. Deployment

Auth 1.11.0 is a **server-side jar**, deployed to **both** the lobby and the SMP — one jar, two
roles, and running a skewed pair against a shared DB has bitten this project before (1.7.51/1.7.54).

1. `./gradlew build` → `build/libs/CoffeesAeroAuth-1.11.0.jar`; verify `Implementation-Version` in
   the jar manifest.
2. Back up the world before the first `/authmod freshstart` is ever run in anger.
3. Upload to both servers by SFTP; do **not** deploy from `untitled/server-mods/`, which is a stale
   reference mirror, not the live set.
4. Re-read the rendered host startup command and diff it against the vault — the heap flags get
   reverted without notice.
5. Boot both, confirm `DB: UP` and the five `CREATE TABLE` statements, then run the live checklist.

Rollback jar: 1.10.12. The new tables are additive and harmless to an older jar, so a rollback needs
no schema work.

## 13. Files touched

**New:** `admin/FreshStart.java` · `tracking/PlayerSessionLog.java` ·
`tracking/ActivitySampler.java` · `tracking/FootprintSampler.java` · `tracking/InfractionLog.java` ·
`moderation/Confiscation.java` · `exploit/AssemblyScanner.java` ·
`mixin/SimAssemblyContraptionMixin.java` · `mixin/CreateContraptionMixin.java` ·
`invsee/InvseeMenu.java` · `invsee/PlayerInventoryView.java` · `invsee/InvseeManager.java` ·
`commands/InvseeCommands.java` · `mixin/LeaveMessageMixin.java`

**Modified:** `CoffeesAeroAuth.java` (registration) · `commands/ProfileCommands.java` (freshstart,
confiscate, release, extended `player`) · `events/PlayerRestrictEvents.java` (one predicate, one
menu exemption) · `events/ChatEvents.java` (chat counter, held-player mirror) ·
`db/DatabaseManager.java` (five tables) · `config/AuthConfig.java` (exploit + confiscate keys) ·
`chat/ChatFilter.java`, `watchdog/WatchdogManager.java`, `lobby/NameApprovalQueue.java`
(infraction rows) · `mixin/JoinMessageMixin.java` (`require = 0` → `1`) ·
`coffees_aero_auth.mixins.json` (register `LeaveMessageMixin`)
