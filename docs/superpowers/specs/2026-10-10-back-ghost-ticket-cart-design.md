# S3 server features: /back + ghost, /ticket, cart contraptions off, tip removal

Date: 2026-10-10 · Owner-approved design · Targets: **CoffeesAeroAuth 1.13.26**, **CoffeesAeroGuard 1.0.9**
Server-side only — no client release. Track A of the 10-09 owner to-do (B = client pack, C = sequenced-assembly bug).

## 1. Tip removal (auth)
Delete `"Lay Create track — it auto-claims to your team"` (and its javadoc paragraph) from
`tablist/TabListManager.java`. Nothing else references it.

## 2. Minecart contraptions OFF (guard)
- Mixin `CartAssemblerBlockEntityMixin` on `com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity`:
  `@Inject(method = "tryAssemble", at = @At("HEAD"), cancellable = true)` → `ci.cancel()`.
  `tryAssemble(AbstractMinecart)` is the single assembly entry (Create 6.0.10, verified with javap).
  Covers assemblers already placed in the S3 world.
- On refusal, action-bar message to players within 16 blocks, rate-limited to once per 5 s per assembler:
  `Cart contraptions are disabled — build an Aeronautics drill.`
- Recipe removal: Guard jar ships `data/create/recipe/crafting/kinetics/cart_assembler.json` with
  `"neoforge:conditions":[{"type":"neoforge:false"}]` (mod data overrides Create's same-path recipe).
- Config switch `cartContraptionsDisabled` (default true) in Guard's server config; the mixin reads it.
- Existing cart contraptions are NOT removed. Disassembly still works.
- Registered in `coffeesaeroguard.mixins.json` `server` list (defaultRequire 1 ⇒ a missed target fails the boot loudly).

## 3. /back remake (auth, `pvp/CombatGuard` + new `pvp/GhostState`)
- `backCooldownSeconds` default 600 → **180**. `backWindowSeconds` stays 300.
- `/back` runs the existing checks (auth, combat tag, cooldown, death window, dimension), then starts a
  **5 s countdown** (title each second + soul particles). Cancelled — cooldown NOT spent — if the player moves
  > 1 block from the start position, takes damage, gets combat-tagged, dies or logs out.
  Countdown is driven from `ServerTickEvent.Post` (never teleport from PlayerTickEvent — see memory).
- On teleport: cooldown spent, death point consumed, player becomes a **ghost for 60 s** (`ghostSeconds`).
- Ghost:
  - Immune to all damage (`LivingIncomingDamageEvent` cancel), PvP included. Void: if y < minBuildHeight,
    lift to the death spot.
  - Mobs cannot target: on start, every `Mob` within 64 blocks whose target is the ghost has it cleared;
    `LivingChangeTargetEvent` with the ghost as new target is cancelled. Not invisible.
  - Fire extinguished each tick while ghost.
  - Visual (server-only): SOUL + SCULK_SOUL particles around the body every 4 ticks, bossbar with seconds left.
  - Ends early on: attacking any entity (`AttackEntityEvent`), breaking (`BlockEvent.BreakEvent`) or placing
    (`BlockEvent.EntityPlaceEvent`) a block, right-clicking any block (`PlayerInteractEvent.RightClickBlock`),
    interacting with any entity (`PlayerInteractEvent.EntityInteract[Specific]`) **except** entity type
    `corpse:corpse`. Message: `👻 The ghostly veil fades.` Bossbar removed.
  - State is in memory only; logout or death ends it.
- New config keys under `combat`: `backCountdownSeconds` (5), `ghostSeconds` (60).

## 4. /ticket (auth, new package `ticket/`)
- Commands: `/ticket <message>` (greedy string, 10–500 chars), `/ticket list`.
- Limit: `ticketsPerDay` (2) per player per server-local calendar day.
- Table `tickets`: id INT AUTO_INCREMENT PK, uuid CHAR(36), name VARCHAR(32), message TEXT,
  status VARCHAR(12) (OPEN/ACK/CLOSED), discord_msg_id VARCHAR(32), created_at BIGINT, updated_at BIGINT,
  closed_by VARCHAR(64), utf8mb4 (new table — CREATE TABLE IF NOT EXISTS). All DB work async (`AsyncIo`).
  Daily count = `COUNT(*) WHERE uuid=? AND created_at >= <local midnight>`.
- Discord: embed posted to `ticketChannelId` (default `1558498026375614515`): ticket #, name, location,
  playtime, message. Buttons `tkack:<id>`, `tkreply:<id>`, `tkclose:<id>`, origin-tagged like the existing
  moderation buttons and gated by the same admin-role check.
  - Acknowledge → mail "We've received ticket #N and will take action." · embed yellow, status ACK.
  - Reply → modal (paragraph) → text mailed to player · embed gets a "Reply by <admin>" field.
  - Close → modal (optional note) → mail "Ticket #N closed" + note · embed grey, buttons removed, status CLOSED.
- Mail via `MailService.sendSystemReward` (no items). If the player is online, chat ping too.
- Only active when `serverRole = SMP` and the bot is up; otherwise the command says tickets are unavailable.

## Testing
Compile both jars; boot the local test server with Create + Guard to confirm the mixin applies (log line on
first refusal + mixin audit). In-game on S3 after staged deploy: /back countdown + cancel-on-move, ghost
immunity in lava, mob de-aggro, corpse open keeps ghost, chest open ends it; ticket post/ack/reply/close round trip.
