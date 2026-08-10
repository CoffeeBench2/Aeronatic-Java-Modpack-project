# Shared Public Login Lobby — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 10,000 scattered private lobby rooms (X=1M–3M, cold, per-login far-chunk load → 9–75 s freezes) with ONE shared public lobby near origin that is permanently force-loaded, where players see each other + chat but can't interact, on a floating island with a fall-catch.

**Architecture:** Keep the existing `coffees_aero_auth:auth_lobby` void dimension and the `PrivateRoomManager`/`ROOM_MANAGER` handle (many refs). Rework its internals from per-player scatter to a single fixed anchor near origin, add a permanent force-load (`setChunkForced`), route all lobby teleports to a spawn pad + standing ring, add a fall-catch tick, extend the already-existing lobby protected zone with damage-off + pickup-block, and route lobby chat lobby-only. `roomSlot` becomes dead data (no migration).

**Tech Stack:** Java 21, NeoForge 21.1.x, `ModConfigSpec`, NeoForge event bus.

> **Privacy:** this plan lives in the working tree only — do NOT `git commit` it (public repo, no internal docs). Source under `src/CoffeesAeroAuth/` is committed normally.

> **API points to confirm at first compile** (verify against the NeoForge 21.1.234 sources, don't assume):
> - `ServerLevel.setChunkForced(int chunkX, int chunkZ, boolean add)` — permanent, persisted forceload.
> - Incoming-damage event: `net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent` (cancelable).
> - Item pickup event: `net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre` (`setCanPickup(TriState.FALSE)`), or fall back to `PlayerEvent.ItemPickupEvent` if the Pre variant differs.
> If a signature differs, adjust the handler; the logic (cancel when player is in `LOBBY_DIMENSION`) is unchanged.

**Build/verify commands (used by several tasks):**
- Compile: `cd src/CoffeesAeroAuth && ./gradlew build`  → Expected: `BUILD SUCCESSFUL`, jar in `build/libs/`.
- Boot test: copy jar into a test server `mods/`, start, expect `Done (Xs)` with no auth/lobby errors.
- Join test (the real gate — boot ≠ join): log in a client, confirm you land in the shared lobby near origin (not X=1M+), no multi-second `Can't keep up!` spike.

---

### Task 1: AuthConfig — shared-lobby tunables

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java`

- [ ] **Step 1: Declare the fields** (add near the "Name approval / private room" block, after `BANNED_WORDS` at line ~23):

```java
    // ── Shared public lobby (floating island near origin) ─────────────────────
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_X;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_Y;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_Z;
    public static final ModConfigSpec.IntValue      LOBBY_FLOOR_Y;
    public static final ModConfigSpec.IntValue      LOBBY_FALL_CATCH_DROP;
    public static final ModConfigSpec.IntValue      LOBBY_FORCELOAD_RADIUS_CHUNKS;
```

- [ ] **Step 2: Define them in the static block** (add a new pushed section; place after the auth section's `defineInRange` calls, before the section is popped — match the existing `b.comment(...).push("...")` / `b.pop()` style used elsewhere):

```java
        b.comment("Shared public login lobby (single forceloaded floating island near origin).")
         .push("sharedLobby");
        LOBBY_SPAWN_X = b
            .comment("Lobby spawn pad X (players teleport here; frozen players ring around it).")
            .defineInRange("lobbySpawnX", 0.5, -30000000.0, 30000000.0);
        LOBBY_SPAWN_Y = b
            .comment("Lobby spawn pad Y.")
            .defineInRange("lobbySpawnY", 101.0, -64.0, 320.0);
        LOBBY_SPAWN_Z = b
            .comment("Lobby spawn pad Z.")
            .defineInRange("lobbySpawnZ", 0.5, -30000000.0, 30000000.0);
        LOBBY_FLOOR_Y = b
            .comment("Reference island floor Y for the fall-catch.")
            .defineInRange("lobbyFloorY", 100, -64, 320);
        LOBBY_FALL_CATCH_DROP = b
            .comment("Blocks below the floor before a player who fell off the island is returned to spawn.")
            .defineInRange("lobbyFallCatchDrop", 15, 3, 200);
        LOBBY_FORCELOAD_RADIUS_CHUNKS = b
            .comment("Chunks (square radius) around the lobby anchor to permanently force-load. Cover the whole build.")
            .defineInRange("lobbyForceloadRadiusChunks", 8, 1, 32);
        b.pop();
```

- [ ] **Step 3: Compile.** Run `cd src/CoffeesAeroAuth && ./gradlew build`. Expected: `BUILD SUCCESSFUL` (config wiring only).

- [ ] **Step 4: Commit.**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java
git commit -m "auth: add shared-lobby config (spawn pad, floor, fall-catch, forceload radius)"
```

---

### Task 2: PrivateRoomManager — single shared lobby + force-load + standing ring

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/lobby/PrivateRoomManager.java`

Keep the class name and `LOBBY_DIMENSION` (heavily referenced). Replace the per-player scatter with one fixed anchor, add force-load + a standing ring, and repurpose the build machinery to the fixed anchor.

- [ ] **Step 1: Replace the grid constants** (the `ROOM_BASE_X`/`ROOM_SPACING`/spawn-offset block, ~lines 36–50) with a single anchor + ring. New code:

```java
    // Single shared lobby anchor near origin (all login-flow players share it; forceloaded 24/7).
    private static final int    ANCHOR_X = 0;      // build/room base corner X
    private static final int    ANCHOR_Z = 0;      // build/room base corner Z
    private static final int    FLOOR_Y  = 100;    // procedural fallback room floor
    private static final int    ROOM_W   = 15;
    private static final int    ROOM_D   = 10;
    private static final int    ROOM_H   = 10;
    private static final int    PREVIEW_UNUSED = 0; // (preview kept simple: previews the shared room)
    private static final ResourceLocation LOBBY_TEMPLATE =
        ResourceLocation.fromNamespaceAndPath("coffees_aero_auth", "lobby_room");
    private static final String BUNDLED_TEMPLATE_RESOURCE = "/coffees_aero_auth/lobby_room.nbt";

    // Standing ring: frozen login-flow players are placed on distinct pads so they don't stack.
    private static final int    RING_SIZE   = 12;
    private static final double  RING_RADIUS = 3.0;

    private boolean sharedBuilt = false;                      // shared room placed this server run
    private final Set<Integer> claimedRingSpots = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> ringSpotByUuid = new ConcurrentHashMap<>();
```

> Add imports at top if missing: `java.util.Map`, `java.util.UUID` (already `java.util.Set`, `ConcurrentHashMap`).

- [ ] **Step 2: Add the spawn-pad accessor + ring math** (new methods; the spawn pad comes from config, ring is computed around it):

```java
    /** The configured lobby spawn pad (double coords). */
    public static double[] spawnPad() {
        return new double[]{
            com.coffeesaerosmp.auth.config.AuthConfig.LOBBY_SPAWN_X.get(),
            com.coffeesaerosmp.auth.config.AuthConfig.LOBBY_SPAWN_Y.get(),
            com.coffeesaerosmp.auth.config.AuthConfig.LOBBY_SPAWN_Z.get()
        };
    }

    /** A distinct standing pad on the ring for index i (0..RING_SIZE-1). */
    private static double[] ringSpot(int i) {
        double[] c = spawnPad();
        double ang = (2 * Math.PI * i) / RING_SIZE;
        return new double[]{ c[0] + RING_RADIUS * Math.cos(ang), c[1], c[2] + RING_RADIUS * Math.sin(ang) };
    }

    /** Transient standing spot for a frozen player; assigned once per join, released on leave. */
    public double[] getFrozenSpotFor(UUID uuid) {
        Integer i = ringSpotByUuid.get(uuid);
        if (i == null) {
            i = 0;
            while (i < RING_SIZE && !claimedRingSpots.add(i)) i++;
            if (i >= RING_SIZE) i = Math.floorMod(uuid.hashCode(), RING_SIZE); // overflow: allow stacking
            ringSpotByUuid.put(uuid, i);
        }
        return ringSpot(i);
    }

    /** Release a player's ring spot on logout. */
    public void releaseFrozenSpot(UUID uuid) {
        Integer i = ringSpotByUuid.remove(uuid);
        if (i != null) claimedRingSpots.remove(i);
    }
```

- [ ] **Step 3: Rework `teleportToRoom`** (replace the existing method ~lines 88–109). It now ignores `slot`, builds the one shared room if needed, ensures footing, and teleports the player to the spawn pad:

```java
    /** Teleports player into the single shared lobby, building it once if needed. (slot ignored — kept
     *  for call-site compatibility; the lobby is shared.) */
    public void teleportToRoom(ServerPlayer player, int slot) {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c[Auth] Lobby dimension failed to load — contact an admin. UUID: " + player.getUUID()));
            CoffeesAeroAuth.LOGGER.error("Auth lobby dimension not loaded for player {}!", player.getGameProfile().getName());
            return;
        }
        ensureSharedRoom(lobby);
        double[] pad = spawnPad();
        ensureSpawnPlatform(lobby, ANCHOR_X, (int) Math.floor(pad[1]) - 1, ANCHOR_Z);
        player.teleportTo(lobby, pad[0], pad[1], pad[2], Set.of(), 180.0f, 0.0f);
    }
```

- [ ] **Step 4: Add `ensureSharedRoom`** and repurpose the builder to the fixed anchor. Replace `buildRoom(ServerLevel, int slot)`'s signature use: add a wrapper that builds at the anchor once per run:

```java
    /** Places the shared lobby build once per server run (bundled template if present, else procedural).
     *  Idempotent; the op pastes the real (Middle Ages) build over this and the forceload persists it. */
    private void ensureSharedRoom(ServerLevel lobby) {
        if (sharedBuilt) return;
        buildRoomAt(lobby, ANCHOR_X, FLOOR_Y, ANCHOR_Z);
        sharedBuilt = true;
    }
```

> In the existing `buildRoom(ServerLevel level, int slot)` body, replace the first three lines
> `int bx = ROOM_BASE_X + slot * ROOM_SPACING; int by = FLOOR_Y; int bz = 0;` — rename the method to
> `buildRoomAt(ServerLevel level, int bx, int by, int bz)` and delete those three derivation lines
> (bx/by/bz now come from params). All the interior `set(...)`/`placeTemplate(...)` calls already use
> `bx/by/bz`, so no other change inside is needed. Update `placeTemplate`/`ensureSpawnPlatform`/
> `clearVolume` callers accordingly (they already take explicit coords).

- [ ] **Step 5: Add the force-load setup** (new public method, called at server start in Task 6):

```java
    /** Permanently force-loads the lobby region around the anchor so it never goes cold (the freeze fix).
     *  Uses vanilla setChunkForced (persisted). Also places the shared room so footing exists. */
    public void initSharedLobby() {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) {
            CoffeesAeroAuth.LOGGER.error("[Lobby] auth_lobby dimension missing at startup — cannot force-load.");
            return;
        }
        int r = com.coffeesaerosmp.auth.config.AuthConfig.LOBBY_FORCELOAD_RADIUS_CHUNKS.get();
        int cx = ANCHOR_X >> 4, cz = ANCHOR_Z >> 4;
        int n = 0;
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) { lobby.setChunkForced(x, z, true); n++; }
        ensureSharedRoom(lobby);
        CoffeesAeroAuth.LOGGER.info("[Lobby] Shared lobby ready — force-loaded {} chunks around ({}, {}).", n, ANCHOR_X, ANCHOR_Z);
    }
```

- [ ] **Step 6: Simplify `runStartupCleanup`** (replace its per-player `forceRebuildRoom` loop). It should no longer rebuild per-player rooms; keep slot re-registration harmless and just ensure the shared lobby:

```java
    /** Runs on server start: force-load + ensure the single shared lobby. Per-player rooms are gone
     *  (roomSlot is dead data). */
    public void runStartupCleanup(ProfileStore store) {
        initSharedLobby();
        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Shared lobby startup done.");
    }
```

> `assignSlot`, `releaseSlot`, `getRoomSpawnPos`, `forceRebuildRoom`, `deleteRoom`, `saveTemplate`,
> `teleportToPreview`, `ensureSafeFooting` may stay as-is (unused-but-harmless or still used by /lobby).
> `getRoomSpawnPos` is replaced at its one call site in Task 3, so it can be left or deleted.

- [ ] **Step 7: Compile.** `cd src/CoffeesAeroAuth && ./gradlew build`. Expected: `BUILD SUCCESSFUL`. Fix any leftover references to the deleted `ROOM_BASE_X`/`ROOM_SPACING`/`buildRoom(level,slot)` signature the compiler flags.

- [ ] **Step 8: Commit.**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/lobby/PrivateRoomManager.java
git commit -m "auth: shared single lobby near origin + permanent forceload + standing ring"
```

---

### Task 3: AuthManager — frozen spot from the ring, release on leave, fall-catch

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/auth/AuthManager.java`

- [ ] **Step 1: Frozen spot from the ring.** In `onTick`, in the `pendingLobbyTeleport` block (~lines 393–406), replace the `getRoomSpawnPos(profile.roomSlot)` frozen-pos line:

Old:
```java
                if (!isAuthenticated(uuid)) {   // offline lobby/login players stay frozen; premium roam free
                    double[] pos = CoffeesAeroAuth.ROOM_MANAGER.getRoomSpawnPos(profile.roomSlot);
                    frozenPos.put(uuid, pos);
                }
```
New:
```java
                if (!isAuthenticated(uuid)) {   // offline lobby/login players stay frozen on their ring pad
                    double[] pos = CoffeesAeroAuth.ROOM_MANAGER.getFrozenSpotFor(uuid);
                    frozenPos.put(uuid, pos);
                    player.teleportTo(pos[0], pos[1], pos[2]);
                }
```

- [ ] **Step 2: Release the ring spot on leave.** In `onPlayerLeave` (~line 370, near the other `.remove(uuid)` cleanup calls), add:

```java
        if (CoffeesAeroAuth.ROOM_MANAGER != null) CoffeesAeroAuth.ROOM_MANAGER.releaseFrozenSpot(uuid);
```

- [ ] **Step 3: Fall-catch for authed lobby roamers.** In `onTick`, inside the `if (isAuthenticated(uuid)) { ... }` block (~lines 408–416), before its `return;`, add the fall-catch:

```java
            // Floating-island fall-catch: an authed player still in the lobby who drops off the island
            // is returned to the spawn pad (damage is off in the lobby, so this is cosmetic-safe).
            if (player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
                int floor = AuthConfig.LOBBY_FLOOR_Y.get();
                int drop  = AuthConfig.LOBBY_FALL_CATCH_DROP.get();
                if (player.getY() < floor - drop && CoffeesAeroAuth.ROOM_MANAGER != null) {
                    double[] pad = com.coffeesaerosmp.auth.lobby.PrivateRoomManager.spawnPad();
                    player.teleportTo(pad[0], pad[1], pad[2]);
                    player.setDeltaMovement(0, 0, 0);
                    player.fallDistance = 0;
                }
            }
```

- [ ] **Step 4: Compile.** `cd src/CoffeesAeroAuth && ./gradlew build`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/auth/AuthManager.java
git commit -m "auth: frozen players use lobby ring pad; add floating-island fall-catch"
```

---

### Task 4: PlayerRestrictEvents — damage-off + item-pickup block in the lobby

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java`

The protected zone (break/place/attack/toss/containers) already exists keyed on `LOBBY_DIMENSION`. Add two handlers.

- [ ] **Step 1: Add the damage-off handler** (any player in the lobby takes no damage — covers fall for the fall-catch, drown, PvP, mobs). Add:

```java
    /** No damage of any kind in the lobby (fall/PvP/drown/mob). Covers the fall-catch window and the
     *  "can't hit each other" rule for melee AND projectiles. Ops included — the lobby is a safe zone. */
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp
                && sp.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION) {
            event.setCanceled(true);
        }
    }
```

- [ ] **Step 2: Add the item-pickup block** (belt-and-braces; no ground items should exist, but never let a lobby player pick anything up):

```java
    /** No item pickup in the lobby. */
    public static void onItemPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer sp
                && sp.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION) {
            event.setCanPickup(net.minecraft.util.TriState.FALSE);
        }
    }
```

> If `ItemEntityPickupEvent.Pre` / `setCanPickup` differ in 21.1.234, use `PlayerEvent.ItemPickupEvent`
> or the available pre-pickup event and cancel it. Logic is unchanged.

- [ ] **Step 3: Compile.** `cd src/CoffeesAeroAuth && ./gradlew build`. Expected: `BUILD SUCCESSFUL` (handlers compile; registration is Task 6).

- [ ] **Step 4: Commit.**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java
git commit -m "auth: lobby is a no-damage, no-pickup safe zone"
```

---

### Task 5: ChatEvents — lobby-only chat routing

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/events/ChatEvents.java`

Today the formatted message goes to ALL players (loop at ~lines 64–66). Route by dimension: lobby↔lobby, world↔world.

- [ ] **Step 1: Compute the sender's channel and filter viewers.** Replace the broadcast loop (~lines 64–67):

Old:
```java
        for (ServerPlayer viewer : player.getServer().getPlayerList().getPlayers()) {
            viewer.sendSystemMessage(viewer.hasPermissions(2) ? adminVariant : formatted);
        }
        player.getServer().sendSystemMessage(adminVariant);   // console log keeps both names
```
New:
```java
        boolean senderInLobby =
            player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
        for (ServerPlayer viewer : player.getServer().getPlayerList().getPlayers()) {
            boolean viewerInLobby =
                viewer.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
            if (viewerInLobby != senderInLobby) continue;   // lobby and world are separate channels
            viewer.sendSystemMessage(viewer.hasPermissions(2) ? adminVariant : formatted);
        }
        player.getServer().sendSystemMessage(adminVariant);   // console log keeps both names
```

- [ ] **Step 2: Don't bridge lobby chat to Discord.** Wrap the existing Discord-bridge block (~lines 69–76) so it only fires for world chat:

```java
        // Bridge to Discord public channel — WORLD chat only (lobby chatter stays in the lobby).
        if (!senderInLobby && CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            String cleanBadge = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                ? "[✦ Verified]" : "[◈ Offline]";
            CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerChat(cleanBadge,
                (clanTag != null ? "[" + clanTag + "] " : "") + displayName, rawText);
        }
```

- [ ] **Step 3: Compile.** `cd src/CoffeesAeroAuth && ./gradlew build`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/events/ChatEvents.java
git commit -m "auth: lobby-only chat channel (lobby<->lobby, world<->world; no Discord bridge for lobby)"
```

---

### Task 6: CoffeesAeroAuth — register new listeners + force-load at startup

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java`

- [ ] **Step 1: Register the two new listeners.** Next to the existing `NeoForge.EVENT_BUS.addListener(...)` calls for `PlayerRestrictEvents` (search for `onRightClickBlock`/`onBlockBreak` registration), add:

```java
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.events.PlayerRestrictEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.events.PlayerRestrictEvents::onItemPickup);
```

- [ ] **Step 2: Force-load at startup.** `onServerStarting` already calls `ROOM_MANAGER.runStartupCleanup(PROFILE_STORE)` (line ~208), and Task 2 made that call `initSharedLobby()`. Confirm the ordering: `ROOM_MANAGER = new PrivateRoomManager(...)` (line ~206) precedes it. No new code needed here beyond confirming the call remains. (If `runStartupCleanup` was reordered, ensure `initSharedLobby()` runs after the server/dimension is available.)

- [ ] **Step 3: Compile.** `cd src/CoffeesAeroAuth && ./gradlew build`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java
git commit -m "auth: register lobby damage/pickup listeners; forceload shared lobby at startup"
```

---

### Task 7: Integration verification (boot + join — the real gate)

**Files:** none (verification only). No unit-test harness exists for a live NeoForge server mod; these are the acceptance checks from the spec §7.

- [ ] **Step 1: Boot test.** Put the built jar in a test server `mods/`, start. Expected: `Done (Xs)`, and a log line `[Lobby] Shared lobby ready — force-loaded N chunks around (0, 0).` No auth/lobby ERRORs.

- [ ] **Step 2: Freeze-fix check (primary).** Log a client in through the flow. Expected: you are placed in the lobby **near origin** (not X=1,000,000+), and there is **no** multi-second `Can't keep up! … ticks behind` spike on that login. (Compare to the 9–75 s stalls before.)

- [ ] **Step 3: Protected zone.** With two accounts in the lobby: confirm you can't damage each other, can't break/place, can't drop/pickup items, containers/backpacks stay shut, no fall damage.

- [ ] **Step 4: Fall-catch.** As an authed-in-lobby player, walk off the island edge → you return to the spawn pad, unharmed, no void death.

- [ ] **Step 5: Lobby-only chat.** A lobby player's message reaches only other lobby players; a world player's message does not show in the lobby; Discord shows world chat only.

- [ ] **Step 6: Returning player fast path.** A returning valid-session player still skips the lobby and lands at their `returnPos`.

- [ ] **Step 7: Version bump + deploy note.** Bump `src/CoffeesAeroAuth/gradle.properties` `mod_version`, rebuild, stage the jar for the Apex deploy (server-only, one restart). Operational one-time: paste the Middle Ages build into `auth_lobby` near origin (op + WorldEdit); the forceload persists it. Update Obaa `reference/current-stack-knowledge.md` decisions log + `consciousness/current-state.md`.

---

## Self-review notes
- **Spec coverage:** §4.1 force-load → T2/T6; §4.2 frozen ring + flow → T2/T3; §4.3 protected-zone additions → T4; §4.4 fall-catch → T3; §4.5 lobby-only chat → T5; §4.6 map (pasted + persisted) → T7 op step; §4.7 simplify scatter → T2; §4.8 migration (roomSlot dead) → T2 (no migration). All covered.
- **Type consistency:** `getFrozenSpotFor(UUID)`, `releaseFrozenSpot(UUID)`, `spawnPad()`, `initSharedLobby()`, `ensureSharedRoom()`, `buildRoomAt(level,bx,by,bz)` used consistently across T2/T3/T6.
- **Known verify-at-compile:** `setChunkForced`, `LivingIncomingDamageEvent`, `ItemEntityPickupEvent.Pre` (noted in header).
