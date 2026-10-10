# /back ghost, /ticket, cart contraptions off — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship auth 1.13.26 (/back countdown + 60 s ghost, /ticket with Discord buttons, tip removal) and guard 1.0.9 (cart contraptions disabled).

**Architecture:** All server-side. Auth gets two new focused classes (`pvp/GhostState`, `pvp/BackCountdown`) beside `CombatGuard`, and a `ticket/` package (`TicketStore` = DB, `TicketService` = commands + Discord JSON). Discord clicks route through the existing `DiscordInteractions.handle()` before the moderation admin gate, with their own `ticketAdminRoleId`. Guard gets one mixin on Create's `CartAssemblerBlockEntity.tryAssemble` plus a recipe override in its jar data.

**Tech Stack:** NeoForge 21.1 (MC 1.21.1), Mixin, MySQL via `DB_MANAGER` + `AsyncIo`, Discord REST v10 (raw JSON strings, as the codebase does). No unit-test harness exists in these mods — verification is compile + boot test + in-game checks (the project's established practice).

Spec: `docs/superpowers/specs/2026-10-10-back-ghost-ticket-cart-design.md`

---

### Task 1: Remove the rail auto-claim tip
**Files:** Modify `src/CoffeesAeroAuth/.../tablist/TabListManager.java:20-29`
- [ ] Delete the line `"Lay Create track — it auto-claims to your team",` and the javadoc paragraph about it.
- [ ] Commit `fix(tab): drop the rail auto-claim tip`.

### Task 2: Config keys (auth)
**Files:** Modify `config/AuthConfig.java` (`combat` section; new `ticket` section)
- [ ] `backCooldownSeconds` default 600 → 180; add `backCountdownSeconds` (5, 0–30) and `ghostSeconds` (60, 0–600).
- [ ] New section `ticket`: `ticketsEnabled` (true), `ticketsPerDay` (2, 1–20), `ticketChannelId` ("1558498026375614515"), `ticketAdminRoleId` ("" = anyone in the channel).

### Task 3: GhostState
**Files:** Create `pvp/GhostState.java`; register listeners in `CoffeesAeroAuth.java` next to CombatGuard (line ~382).
- `start(ServerPlayer, int seconds, Vec3 safeSpot)`, `isGhost(UUID)`, `end(ServerPlayer, String reason)`.
- Per-player record: expiry ms, `ServerBossEvent`, safe spot.
- Listeners: `LivingIncomingDamageEvent` (cancel for ghost), `LivingChangeTargetEvent` (cancel when new target is ghost),
  `AttackEntityEvent`/`BlockEvent.BreakEvent`/`BlockEvent.EntityPlaceEvent`/`PlayerInteractEvent.RightClickBlock`
  → end; `PlayerInteractEvent.EntityInteract` + `EntityInteractSpecific` → end unless target type id is `corpse:corpse`;
  logout/death/dimension change → end silently; `ServerTickEvent.Post` → particles every 4 ticks, extinguish,
  void lift, bossbar progress, expiry.
- On start: clear target of every `Mob` within 64 blocks targeting the ghost.

### Task 4: BackCountdown + /back rewrite
**Files:** Create `pvp/BackCountdown.java`; modify `pvp/CombatGuard.java` (`handleBack`, `onLivingDamage`, javadoc).
- `handleBack` keeps checks; instead of teleporting, `BackCountdown.start(player, death)` (refuses if one is already running).
- Tick (ServerTickEvent.Post): each second a title `Returning in N…`; cancel if moved > 1 block (distanceToSqr > 1),
  tagged, dead, disconnected; damage → `BackCountdown.cancel(uuid, "you took damage")` from `onLivingDamage`
  (any damage, LivingDamageEvent.Post with victim player).
- At 0: teleport, consume death point, set cooldown, `GhostState.start(player, ghostSeconds, deathSpot)`.
- Death point / cooldown maps stay in CombatGuard; expose package-private `consumeDeath(uuid)`, `lockBack(uuid)`, `peekDeath`.

### Task 5: TicketStore (DB)
**Files:** Create `ticket/TicketStore.java`; call `TicketStore.createSchema(c)` from `db/DatabaseManager.java:397` beside MailStore.
- Table `tickets` (id BIGINT AI PK, uuid CHAR(36), name VARCHAR(32), message VARCHAR(600), location VARCHAR(96),
  status VARCHAR(12), created_at BIGINT, updated_at BIGINT, closed_by VARCHAR(64), last_reply VARCHAR(1024),
  INDEX (uuid, created_at)) DEFAULT CHARSET utf8mb4.
- `countSince(server, uuid, sinceMs, cb)`, `create(server, Ticket, cb(id))`, `get(id, cb)` (off-thread callback ok for Discord),
  `update(id, status, closedBy, lastReply)`, `listFor(server, uuid, cb)`. All on AsyncIo; callbacks to server thread where a player is involved.

### Task 6: TicketService (command + Discord)
**Files:** Create `ticket/TicketService.java`; register command in `CoffeesAeroAuth.onRegisterCommands` (~line 900).
- `/ticket <message>`: SMP role + enabled + DB + Discord REST present; length 10–500; count since local midnight
  (`LocalDate.now().atStartOfDay(ZoneId.systemDefault())`) < ticketsPerDay; create row; post embed + 3 buttons
  `tkack:SURVIVAL:<id>`, `tkreply:SURVIVAL:<id>`, `tkclose:SURVIVAL:<id>` (origin from `NameApprovalQueue.originTag()`).
- `/ticket list`: last 10 of the player's tickets with status.
- `embedJson(Ticket)` rebuilds the embed from a row (colour by status, reply field) — used for post and every update.
- Discord actions (called from DiscordInteractions): `ack(id, clicker, interactionId, token)`, `openReplyModal`,
  `reply(id, text, clicker, …)`, `openCloseModal`, `close(id, note, clicker, …)` — each updates the row, mails the player via
  `MailService.sendSystemReward(server, uuid, subject, body, List.of(), 0, null, ok -> {})`, and responds type 7 with the rebuilt
  embed (closed = no components).

### Task 7: Route ticket interactions
**Files:** Modify `discord/DiscordInteractions.java:66-85`.
- Before the admin-role gate: if customId starts with `tk`, compute `forUs`, check `ticketAdminRoleId` (blank = allowed), dispatch to TicketService, return.

### Task 8: Version bump + build (auth)
- [ ] `gradle.properties` mod_version 1.13.26; `./gradlew build`; expect BUILD SUCCESSFUL. Commit.

### Task 9: Guard cart mixin
**Files:** Create `src/CoffeesAeroGuard/src/main/java/com/coffeesaerosmp/guard/mixin/CartAssemblerBlockEntityMixin.java`;
modify `coffeesaeroguard.mixins.json` (server list); create `src/CoffeesAeroGuard/src/main/resources/data/create/recipe/crafting/kinetics/cart_assembler.json`;
add `cartContraptionsDisabled` to Guard config; bump to 1.0.9; build.
- Inject HEAD of `tryAssemble(AbstractMinecart)`, cancellable; when config on: cancel, action-bar nearby players (16 blocks, 5 s/pos throttle).
- Ensure Create is a compileOnly dep of Guard (it already mixes into Create packets).

### Task 10: Boot test + deploy notes
- Boot local test server with Guard + Create; grep log for mixin apply errors; confirm `tickets` table creation on DB-enabled boot.
- Deploy (owner-gated): stop S3, upload jars, set `backCooldownSeconds = 180` in S3's `coffees_aero_auth-server.toml`
  **while stopped** (watched-config rule), start, verify.
