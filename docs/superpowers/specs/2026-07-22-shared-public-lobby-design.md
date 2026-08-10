# Shared Public Login Lobby — Design Spec

**Date:** 2026-07-22
**Component:** CoffeesAeroAuth (server-side)
**Status:** Draft for review
**Author:** Claude Code session (Coffees Aero SMP)

> NOTE ON PRIVACY: this repo is public. This spec is written to the working tree
> but is **NOT** committed to git (per the project's no-internal-docs rule). It can
> be moved to the Obaa vault if preferred.

---

## 1. Problem

The auth lobby (`coffees_aero_auth:auth_lobby`, a void flat dimension) currently gives every
player their **own private room** on a 1-D line:

```
roomX = 1_000_000 + (hash(uuid) % 10_000) * 200      // X = 1,000,000 .. 3,000,000
```

Rooms are hash-scattered mega-blocks apart, and **nothing force-loads them**, so each room's
region is cold. On login the mod cross-dimension-teleports the player into that cold, far-flung
region → a synchronous main-thread chunk load, amplified by **Sable** (server-side Rapier physics,
which tracks the lobby dimension). Result: the server stalls 9–35 s, all online players
freeze/rubber-band, voicechat mass-times-out, and the joining client itself times out and
reconnects. Proven: a real login coord `(2,172,607.5, 101, 5.5)` matched lobby slot 5863 exactly.

**Root cause:** cold, far, per-player chunk loads amplified by Sable.

## 2. Goals / Non-goals

**Goals**
- Eliminate the login freeze (the primary driver).
- Replace scattered private rooms with **one shared public lobby near origin, permanently loaded**.
- Players in the lobby **see each other and chat**, but **cannot interact** (no PvP/hitting, no
  block break/place, no item exchange, no containers) — a "walk-and-talk ghosts" hub.
- Make the lobby a **floating island**: fall off → teleported back to the lobby spawn.
- Keep the build **map-agnostic** (swap in the Middle Ages / any build later, no code change).

**Non-goals**
- No change to the world, `/spawn`, returning-player fast path, or the client pack.
- No moving/kinetic Create contraptions in the lobby build (static blocks only).
- Advancement in-game display-name fix and the quest investigation are **separate** follow-ups
  (they may ride the same jar bump but are not part of this spec).

## 3. Design overview

One shared lobby room lives at a fixed near-origin coordinate in the existing `auth_lobby` void
dimension. A **permanent force-load** keeps its chunks resident 24/7, so the region is warm from the
first boot and no login ever triggers a cold far-region load again. All existing lobby behavior
(freeze-until-auth, name hiding, container lockdown, grief protection) is keyed on the lobby
dimension and carries over unchanged. New: damage is fully off in the lobby, item pickup is blocked,
a per-tick fall-catch returns anyone who drops off the island, and lobby chat is routed lobby-only.

## 4. Components

### 4.1 Shared lobby location + permanent force-load
- A single fixed lobby anchor near origin (e.g. `(0, 100, 0)`), configurable.
- On `ServerStartingEvent` (where `ROOM_MANAGER` is created today), after the lobby dimension is
  available: add a **permanent** force-load over the lobby region and its immediate neighbors.
  - Use `ServerLevel.setChunkForced(x, z, true)` for each lobby chunk — the vanilla `/forceload`
    mechanism, which persists in the level's forced-chunk set and survives restarts and is honored
    even with no player present. (Alternative: a custom permanent `TicketType` re-added each boot,
    mirroring `RtpCommand`'s ticket pattern. `setChunkForced` is simpler and persistent.)
  - Force-load a small footprint that covers the whole build (the build's chunk span, padded by 1).
- The forced region is small and near origin, so Sable only ever tracks one tiny, always-warm area.
- **Open lever:** if Sable exposes a per-dimension disable, excluding `auth_lobby` from Sable is a
  clean complementary hardening (investigate; not required for the fix).

### 4.2 Player flow (unchanged security model)
- **Unauthenticated** → spawn/held in the shared lobby, **frozen** (teleported back to a fixed spot
  every tick, name hidden) until the gate cookie resolves them — exactly today, just shared.
  - Frozen players are placed on a small **ring of standing spots** around the spawn pad (e.g. 8–12
    positions) so simultaneous logins don't stack on one block. Assignment is transient (in-memory,
    by join order), not persisted.
- **Authenticated-but-not-yet-`/spawn`'d** (premium first-join, freshly approved offline) → roam the
  island freely until they `/spawn`.
- **`/spawn`** → leaves the lobby into the world (unchanged; still auth-gated). Returning
  valid-session players skip the lobby entirely (unchanged fast path).

### 4.3 Protected zone (mostly already implemented)
`PlayerRestrictEvents` already cancels, for any non-op in `LOBBY_DIMENSION`: block break, block
place, attack-entity (**players already can't hit each other**), left-click block, right-click block
(except vendor + levers), entity interact, and item toss; plus the per-tick container lockdown. All
of this carries over to the shared lobby unchanged.

**Additions:**
- **Damage fully off** in the lobby: cancel `LivingIncomingDamageEvent` (or hurt) for any player in
  `LOBBY_DIMENSION` — covers fall (needed for the fall-catch window), drowning, projectiles, mobs.
- **Item pickup blocked** in the lobby: cancel `ItemEntityPickupEvent` for players in the lobby
  (belt-and-braces; there should be no ground items anyway).

### 4.4 Fall-catch (floating island)
- The lobby build sits in void → naturally reads as a floating island.
- Per-tick (in the existing `AuthManager.onTick` / restrict tick path), for any player in
  `LOBBY_DIMENSION`: if `player.getY() < (islandFloorY - FALL_CATCH_DROP)` (default drop = 15),
  teleport them to the lobby spawn pad and reset fall distance + delta movement.
- Because damage is off (4.3), the catch is cosmetic-safe even if it fires a tick late; the drop
  threshold stays well above the dimension `min_y` (-64) so nobody reaches the void kill-plane.
- Frozen unauth players can't jump off (they're position-locked); the catch matters for the roaming
  authed-in-lobby group and is a cheap Y-check.

### 4.5 Lobby-only chat routing
In `ChatEvents.onServerChat` (authenticated senders only; unauth are already blocked):
- If the **sender is in `LOBBY_DIMENSION`**, deliver the formatted message **only to viewers who are
  also in `LOBBY_DIMENSION`** (+ console). Do **not** bridge lobby chat to Discord.
- If the sender is **in the world**, deliver only to **non-lobby** viewers (so world chat doesn't
  spill into the lobby). Discord bridging stays for world chat only.
- Net effect: lobby and world are two separate channels (option A). Unauth frozen players remain
  chat-blocked (password-leak safety), unchanged.

### 4.6 Map integration (pluggable, persistent)
- The chosen build (`Middle Ages small lobby`, a **world save**, ~1000-block footprint centered on
  origin) is **larger than a vanilla structure template's 48³ limit**, so we do **not** bundle it as
  a jar template or re-place it per boot.
- Instead the shared lobby is **persistent world data**: the build is pasted **once** into the
  `auth_lobby` dimension near origin (admin op + WorldEdit/structure blocks, or a one-time import),
  and the **force-load** keeps it resident so it saves and reloads reliably. No per-boot rebuild.
- This is safe here (unlike the old ephemeral private rooms that hit empty-on-disk persistence
  issues) precisely because the region is permanently force-loaded and never cleared.
- `/lobby` preview + `/lobby save` are retained for small in-place edits; the large initial build
  comes in via the paste. Swapping the build later = paste a new one at the anchor.
- Decorative entities from the save (item frames, armor stands, paintings) are imported; living
  mobs are not.

### 4.7 Simplification / removal
- `PrivateRoomManager`: remove per-player slot assignment (`assignSlot`/`usedSlots`), the 1M–3M
  `ROOM_BASE_X`/`ROOM_SPACING` scatter, per-player `buildRoom`/`forceRebuildRoom`, and the
  `runStartupCleanup` per-player rebuild loop. Replace with a single fixed lobby anchor + spawn pad +
  ring, force-load setup, and `ensureSafeFooting`/`teleportToSpawn` retained.
- `AuthManager.routeToLobbyRoom` / `teleportToRoom` become "teleport to the shared lobby (ring spot)."

### 4.8 Migration
- Profile `roomSlot` becomes **dead** (ignored; no data migration, no wipe). Existing profiles keep
  it harmlessly.
- Profile `returnPos` (used by `/spawn` to resume a returning player at their last world spot) is
  **unchanged**.

## 5. Config additions (AuthConfig)
- `lobbySpawnX/Y/Z` (or a single anchor) — the shared lobby spawn pad. Default near origin.
- `lobbyForceloadRadiusChunks` — how many chunks around the anchor to force-load (cover the build).
- `fallCatchDrop` — blocks below island floor before the catch fires (default 15).
- `islandFloorY` — reference floor Y for the fall-catch (default 100).

## 6. Startup ordering
1. Server starting → lobby dimension available.
2. `ROOM_MANAGER` created.
3. **Force-load** the lobby region (`setChunkForced`).
4. (Build already persisted in the dimension from the one-time paste; nothing to re-place.)
5. Ready. First login teleports into an already-warm region.

## 7. Testing / verification
- **Freeze fix (primary):** on a running server, watch the log while a player logs in and gets
  routed to the lobby — confirm **no** `Can't keep up! … ticks behind` spike and no far-coordinate
  login (positions stay near origin). Compare against the 9–35 s stall today.
- **Fall-catch:** authed-in-lobby player walks off the island edge → returns to spawn pad, no damage,
  no void death.
- **Protected zone:** two players in the lobby cannot damage each other; no block break/place; no
  item drop/pickup; containers/backpacks stay shut.
- **Lobby chat:** a lobby player's message reaches only other lobby players; a world player's message
  does not appear in the lobby; Discord receives world chat only.
- **Returning player:** valid-session return still skips the lobby and lands at `returnPos`.

## 8. Deploy
- Server-only CoffeesAeroAuth jar bump + one restart. One-time: paste the lobby build into the
  `auth_lobby` dimension near origin (op session). No client/pack change.

## 9. Decisions to confirm (from brainstorm)
- Fall trigger = **Y-threshold** (islandFloorY − 15). ✔ proposed
- Lobby spawn = **central pad on the island**, frozen players in a ring around it. ✔ proposed
- Decorative entities: **import** item frames / armor stands / paintings; no mobs. ✔ proposed
- Theme = **Middle Ages** (the provided world save). ✔ confirmed
- **Licensing** of the Middle Ages build — confirm it's cleared for public-server use (user action).
- Frozen (still-authenticating) players stay **chat-blocked**; lobby chat is for authed-in-lobby
  players. ✔ proposed

## 10. Out of scope (tracked separately)
- In-game advancement announcement still shows the registered username instead of the display name
  (Discord/Obsidian paths already use the display name) — small mixin/rebroadcast fix, may ride the
  same jar bump.
- "New quests broke the server" — full static audit found the quest files valid; awaiting the actual
  crash log / symptom before acting.
