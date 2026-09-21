# CoffeesAeroAuth 1.11.0 — Admin Tooling & Player Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship auth 1.11.0 with six admin/tracking features: a fresh-start wipe, player tracking, confiscation mode, an assembly-time exploit alert, `/invsee` + `/invsee_echest`, and hidden-op anonymity on leave.

**Architecture:** Every feature splits into a **pure core** (no Minecraft imports, unit-tested with JUnit 5) and a **Minecraft-facing shell** (event handlers, commands, mixins, SQL — verified by boot and in-game test). This mirrors the existing `PlayerDisplay` / `StaffBadges` split that `build.gradle` already documents, and it is the only way any of this is testable without a running server.

**Tech Stack:** Java 21 · NeoForge 21.1.230 · Minecraft 1.21.1 · Gradle · JUnit 5 · MySQL (HikariCP) · SpongePowered Mixin

**Spec:** `docs/superpowers/specs/2026-09-22-authmod-admin-and-tracking-design.md`

**Working directory for every command:** `D:\MC Project\untitled\src\CoffeesAeroAuth`

**Build tool note:** this project ships **only** `gradlew.bat` (there is no Unix `gradlew` script). Use `./gradlew.bat` from Git Bash, or `.\gradlew.bat` from PowerShell.

---

## Task Order & Rationale

| # | Task | Why here |
|---|---|---|
| 1 | F6 leave-line anonymity | Zero dependencies, smallest change, fixes a live privacy bug |
| 2 | Database schema | Every later task writes to these tables |
| 3 | F3 confiscation core (pure) | Pure, unit-testable, no MC |
| 4 | F3 enforcement + commands | Needs 2 and 3 |
| 5 | F4 exploit rules (pure) + config | Pure, unit-testable |
| 6 | F4 mixins + alert | Needs 5 |
| 7 | F5 invsee slot map (pure) | Pure, unit-testable |
| 8 | F5 menus + commands | Needs 7 |
| 9 | F2 tracking stores | Needs 2 |
| 10 | F2 `/authmod player` stat sheet | Needs 9 |
| 11 | F1 fresh start | Most destructive — last, after everything else is proven |
| 12 | Version bump + final verification | Ships it |

---

## File Structure

**New — pure cores (no Minecraft imports, unit-tested):**

| File | Responsibility |
|---|---|
| `moderation/Confiscation.java` | In-memory held-player set; the per-tick predicate |
| `exploit/ExploitRules.java` | Does this anchor id + block id list trip a rule? Cooldown bookkeeping |
| `invsee/InvseeSlotMap.java` | 45-slot view ↔ 41-slot `Inventory` index mapping |
| `admin/FreshStartRules.java` | Refusal decisions for the wipe, as pure booleans |
| `tracking/StatSheet.java` | Formats the stat block for `/authmod player` |

**New — Minecraft shells:**

| File | Responsibility |
|---|---|
| `mixin/LeaveMessageMixin.java` | Swallow/rebuild the vanilla leave line |
| `moderation/ConfiscationStore.java` | MySQL persistence for `confiscations` |
| `exploit/AssemblyScanner.java` | Walk an assembling structure, raise the watchdog alert |
| `mixin/SimAssemblyContraptionMixin.java` | Hook Simulated's assembly search |
| `mixin/CreateContraptionMixin.java` | Hook Create's assembly search |
| `invsee/PlayerInventoryView.java` | Live 45-slot `Container` over a player's `Inventory` |
| `invsee/InvseeMenu.java` | The `ChestMenu` subclass + marker interface |
| `invsee/InvseeManager.java` | Open tracking; close all views on target logout |
| `commands/InvseeCommands.java` | `/invsee`, `/invsee_echest` |
| `tracking/PlayerSessionLog.java` | One row per session on logout |
| `tracking/ActivitySampler.java` | 5-minute vanilla-stats sampler |
| `tracking/FootprintSampler.java` | Ships + claims, on the existing census schedule |
| `tracking/InfractionLog.java` | Append-only infraction rows |
| `admin/FreshStart.java` | Backup → delete → reset, mirroring `AccountTransfer` |

**Modified:** `CoffeesAeroAuth.java` · `db/DatabaseManager.java` · `config/AuthConfig.java` · `events/PlayerRestrictEvents.java` · `events/ChatEvents.java` · `commands/ProfileCommands.java` · `mixin/JoinMessageMixin.java` · `coffees_aero_auth.mixins.json` · `gradle.properties`

---

## Task 1: F6 — Hidden-op anonymity on leave

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/mixin/LeaveMessageMixin.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/mixin/JoinMessageMixin.java` (`require = 0` → `1`)
- Modify: `src/main/resources/coffees_aero_auth.mixins.json`

**Context:** `display/HiddenOps.isHidden(UUID)` already exists and persists across restarts. `mixin/JoinMessageMixin` already swallows the JOIN line for hidden ops, and `discord/DiscordBridge.onPlayerJoin`/`onPlayerLeave` already gate the PUBLIC feed on `isHidden(player)`. **The only gap is the in-game leave line** — vanilla `PlayerList.remove` broadcasts it with no guard.

> This task cannot be unit-tested. Mixins are applied by the loader at class-transform time, and the target class here belongs to Minecraft. Verification is compile + in-game, per Step 4.

- [ ] **Step 1: Create the leave mixin**

Create `src/main/java/com/coffeesaerosmp/auth/mixin/LeaveMessageMixin.java`:

```java
package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.display.HiddenOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Owns the vanilla "X left the game" line — the twin of {@link JoinMessageMixin}.
 *
 * <p>Before this existed a hidden op could join invisibly and then announce themselves to the whole
 * server by logging out, which defeated {@code /authmod hide} entirely. The public Discord feed was
 * already gated ({@code DiscordBridge.isHidden}); only the in-game line was not.
 *
 * <p>There is no cancellable event — {@code PlayerList#remove} calls {@code broadcastSystemMessage}
 * directly — so the call is redirected, exactly as the join line is.
 *
 * <p>🔴 {@code require = 1}, deliberately, unlike the join mixin's original {@code require = 0}.
 * A silently-unapplied mixin here means hidden ops ARE announced, with no error anywhere and no way
 * for the person relying on being invisible to know. Silent exposure is worse than a loud boot
 * failure. The accepted cost is a refused boot if a NeoForge update moves {@code remove}.
 */
@Mixin(PlayerList.class)
public abstract class LeaveMessageMixin {

    @Redirect(
        method = "remove",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
        require = 1)
    private void aeroauth$leaveLine(PlayerList list, Component message, boolean overlay,
                                    ServerPlayer player) {
        if (HiddenOps.isHidden(player.getUUID())) return;   // swallow the announcement entirely

        try {
            var seg = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(player),
                com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.JOIN,
                false);   // broadcast, so there is no viewer and no per-viewer reveal
            // Keep the vanilla translatable so the sentence stays localised and only the NAME
            // argument changes — same treatment the join line already gets, which also closes the
            // cosmetic gap where leaving dropped the player's badge, clan tag and colour.
            list.broadcastSystemMessage(
                Component.translatable("multiplayer.player.left",
                        Component.literal(seg.prefix() + seg.name()))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), overlay);
            return;
        } catch (Exception e) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                "[Display] leave line fell back to vanilla for {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        }
        list.broadcastSystemMessage(message, overlay);   // fallback: never lose the announcement
    }
}
```

- [ ] **Step 2: Register it and raise the join mixin to `require = 1`**

In `src/main/resources/coffees_aero_auth.mixins.json`, add `"LeaveMessageMixin"` to the `"server"` array immediately after `"JoinMessageMixin"`:

```json
    "server": [
        "ServerPacketListenerMixin",
        "ServerCommonCookieMixin",
        "PlayerInfoPacketAccessor",
        "ServerPlayerPlotGuardMixin",
        "ServerPlayerTeleportGuardMixin",
        "ContraptionColliderNpeFixMixin",
        "GameProfileCacheDisplayNameMixin",
        "JoinMessageMixin",
        "LeaveMessageMixin",
        "LobbyAdvancementMixin",
        "RecipeAdvancementListenerMixin"
    ],
```

In `src/main/java/com/coffeesaerosmp/auth/mixin/JoinMessageMixin.java`, change the `@Redirect` annotation's `require = 0` to `require = 1`, and replace the javadoc paragraph that begins `<p>{@code require = 0}` with:

```java
 * <p>🔴 {@code require = 1} since 1.11.0. This was {@code require = 0} — "if the target ever moves,
 * joins are announced the vanilla way rather than the server failing to boot" — and that is the
 * wrong trade for an anonymity feature: a silent failure means hidden ops are announced to everyone
 * with no error anywhere. If this refuses to boot after a NeoForge update, RE-TARGET the redirect;
 * do not restore {@code require = 0}, which restores the bug.
```

- [ ] **Step 3: Compile**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify in game (record the result — do not skip)**

This is the only verification that exists for this task:

1. Boot the server. **If it refuses to boot on a mixin apply failure, `JoinMessageMixin` had already been silently dead in production.** Re-target the redirect; do not restore `require = 0`.
2. With a second account watching chat: `/authmod hide`, then join and leave.
3. Confirm **no** chat line for either, and **no** post in the public Discord feed for either.
4. `/authmod hide` again (unhide), join and leave. Confirm both lines return, and that the leave line now carries the badge and clan tag the join line already had.
5. Confirm the watchdog channel received all four events. F6 must not silence the admin feed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/mixin/LeaveMessageMixin.java \
        src/main/java/com/coffeesaerosmp/auth/mixin/JoinMessageMixin.java \
        src/main/resources/coffees_aero_auth.mixins.json
git commit -m "feat(display): hidden ops are anonymous on leave, not just on join

The in-game leave line had no HiddenOps guard, so a hidden op could join
invisibly and announce themselves by logging out. Both PlayerList mixins
move to require=1: a silent mixin failure here exposes the people relying
on being hidden.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Database schema — five new tables

**Files:**
- Modify: `src/main/java/com/coffeesaerosmp/auth/db/DatabaseManager.java` (inside `createSchema()`, before the closing `CoffeesAeroAuth.LOGGER.info("[DB] Schema verified.");`)

**Context:** `createSchema()` already creates `players`, `trusted_ips`, `sessions`, `name_queue`, `ip_bans`, `server_flags` with `CREATE TABLE IF NOT EXISTS`, and adds columns with bare `ALTER TABLE` wrapped in `try { } catch { }` because **MySQL has no `ADD COLUMN IF NOT EXISTS`**. Follow that pattern exactly.

⚠️ These tables are visible to the creative test server, which shares this database.

- [ ] **Step 1: Add the five tables**

In `createSchema()`, immediately before the `CoffeesAeroAuth.LOGGER.info("[DB] Schema verified.");` line, insert:

```java
            // ── 1.11.0: admin tooling & player tracking ──────────────────────────────
            // ⚠ Shared with the creative test server, which points at the same database.
            // `confiscations` matters most: a player held on live is also held on test.
            // That fails safe, but it is deliberate, not a bug.

            // One row per session, written on logout. Epochs are UTC millis everywhere —
            // the lobby runs UTC and the SMP runs +05:30, so only rendering is localised.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS session_log (" +
                "  id           BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  uuid         CHAR(36)     NOT NULL," +
                "  login_epoch  BIGINT       NOT NULL," +
                "  logout_epoch BIGINT       NOT NULL," +
                "  duration_s   INT          NOT NULL," +
                "  ip           VARCHAR(45)  NULL," +
                "  server_role  VARCHAR(8)   NOT NULL," +
                "  reason       VARCHAR(32)  NULL," +
                "  INDEX idx_session_uuid (uuid)," +
                "  INDEX idx_session_logout (logout_epoch)" +
                ")");

            // Sampled every 5 minutes and on logout — never written per event.
            // Values OVERWRITE on upsert; a /authmod freshstart legitimately zeroes them.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_stats (" +
                "  uuid          CHAR(36) NOT NULL PRIMARY KEY," +
                "  blocks_mined  BIGINT   NOT NULL DEFAULT 0," +
                "  items_used    BIGINT   NOT NULL DEFAULT 0," +
                "  deaths        INT      NOT NULL DEFAULT 0," +
                "  mob_kills     INT      NOT NULL DEFAULT 0," +
                "  player_kills  INT      NOT NULL DEFAULT 0," +
                "  distance_cm   BIGINT   NOT NULL DEFAULT 0," +
                "  chat_messages BIGINT   NOT NULL DEFAULT 0," +
                "  commands_run  BIGINT   NOT NULL DEFAULT 0," +
                "  sampled_epoch BIGINT   NOT NULL DEFAULT 0" +
                ")");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_footprint (" +
                "  uuid           CHAR(36) NOT NULL PRIMARY KEY," +
                "  ships_owned    INT      NOT NULL DEFAULT 0," +
                "  chunks_claimed INT      NOT NULL DEFAULT 0," +
                "  sampled_epoch  BIGINT   NOT NULL DEFAULT 0" +
                ")");

            // A row present means HELD. Release deletes the row and writes a RELEASE
            // infraction, so history lives in `infractions` rather than a dead flag column.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS confiscations (" +
                "  uuid          CHAR(36)     NOT NULL PRIMARY KEY," +
                "  reason        VARCHAR(256) NULL," +
                "  actor         VARCHAR(64)  NOT NULL," +
                "  started_epoch BIGINT       NOT NULL" +
                ")");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS infractions (" +
                "  id      BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  uuid    CHAR(36)     NOT NULL," +
                "  type    VARCHAR(24)  NOT NULL," +
                "  detail  VARCHAR(512) NULL," +
                "  actor   VARCHAR(64)  NULL," +
                "  epoch   BIGINT       NOT NULL," +
                "  INDEX idx_infraction_uuid (uuid)," +
                "  INDEX idx_infraction_epoch (epoch)" +
                ")");
```

- [ ] **Step 2: Compile**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify at boot**

Boot the server and confirm the log contains `[DB] Schema verified.` with **no** `[DB] Schema creation failed` above it. Then confirm the tables exist:

```sql
SHOW TABLES LIKE 'session_log';
SHOW TABLES LIKE 'player_stats';
SHOW TABLES LIKE 'player_footprint';
SHOW TABLES LIKE 'confiscations';
SHOW TABLES LIKE 'infractions';
```

Expected: five rows returned, one per query.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/db/DatabaseManager.java
git commit -m "feat(db): add session_log, player_stats, player_footprint, confiscations, infractions

Additive only — no changes to the players table, so 1.10.12 can still run
against this schema and a rollback needs no schema work.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: F3 — Confiscation core (pure, unit-tested)

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/moderation/Confiscation.java`
- Test: `src/test/java/com/coffeesaerosmp/auth/moderation/ConfiscationTest.java`

**Why this is a separate file from persistence:** it imports **no Minecraft classes and no `AsyncIo`**. `AsyncIo` imports `CoffeesAeroAuth`, which imports Minecraft, which would make this untestable outside a running game — the exact constraint `build.gradle` documents. Persistence lives in `ConfiscationStore` (Task 4).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/coffeesaerosmp/auth/moderation/ConfiscationTest.java`:

```java
package com.coffeesaerosmp.auth.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfiscationTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void reset() {
        Confiscation.clearAll();
    }

    @Test
    void nobodyIsHeldByDefault() {
        assertFalse(Confiscation.isHeld(A));
        assertNull(Confiscation.get(A));
        assertTrue(Confiscation.all().isEmpty());
    }

    @Test
    void holdMakesThePlayerHeldAndRecordsWhoAndWhy() {
        Confiscation.hold(new Confiscation.Hold(A, "griefing spawn", "MrCoffeeBench", 1000L));
        assertTrue(Confiscation.isHeld(A));
        assertFalse(Confiscation.isHeld(B), "holding one player must not hold another");

        Confiscation.Hold h = Confiscation.get(A);
        assertNotNull(h);
        assertEquals("griefing spawn", h.reason());
        assertEquals("MrCoffeeBench", h.actor());
        assertEquals(1000L, h.startedEpoch());
    }

    @Test
    void releaseReturnsTheHoldAndClearsIt() {
        Confiscation.hold(new Confiscation.Hold(A, "r", "op", 1L));
        Confiscation.Hold released = Confiscation.release(A);

        assertNotNull(released);
        assertEquals("r", released.reason());
        assertFalse(Confiscation.isHeld(A));
    }

    @Test
    void releasingSomeoneNotHeldIsNullAndNotAnError() {
        assertNull(Confiscation.release(A));
    }

    @Test
    void holdingTwiceOverwritesRatherThanDuplicating() {
        Confiscation.hold(new Confiscation.Hold(A, "first", "op1", 1L));
        Confiscation.hold(new Confiscation.Hold(A, "second", "op2", 2L));

        assertEquals(1, Confiscation.all().size());
        assertEquals("second", Confiscation.get(A).reason());
        assertEquals("op2", Confiscation.get(A).actor());
    }

    /**
     * The predicate runs every tick for every player. A null uuid must be cheap and false rather
     * than an exception that would take the tick loop down.
     */
    @Test
    void nullUuidIsNotHeldRatherThanThrowing() {
        assertFalse(Confiscation.isHeld(null));
        assertNull(Confiscation.get(null));
    }

    @Test
    void loadAllReplacesTheWholeSetSoABootIsAuthoritative() {
        Confiscation.hold(new Confiscation.Hold(A, "stale", "op", 1L));
        Confiscation.loadAll(java.util.List.of(new Confiscation.Hold(B, "fresh", "op", 2L)));

        assertFalse(Confiscation.isHeld(A), "loadAll must not merge with pre-existing state");
        assertTrue(Confiscation.isHeld(B));
        assertEquals(1, Confiscation.all().size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.moderation.ConfiscationTest"`
Expected: FAIL — compilation error, `package com.coffeesaerosmp.auth.moderation does not exist`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/coffeesaerosmp/auth/moderation/Confiscation.java`:

```java
package com.coffeesaerosmp.auth.moderation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently confiscated ("held"): frozen, unable to interact, unable to run commands, and
 * required to talk to an admin before they get their game back.
 *
 * <h2>Pure by design</h2>
 * This class imports NOTHING from Minecraft and nothing from {@code AsyncIo}. {@link #isHeld} runs
 * every tick for every player, so it must be a plain map read — and keeping the persistence in
 * {@link ConfiscationStore} is also what makes this unit-testable without a running game (see the
 * note in {@code build.gradle} about the pure display core).
 *
 * <p>The in-memory map is the copy that gets READ. The {@code confiscations} table is the copy that
 * SURVIVES; it is written through {@code ConfiscationStore} and reloaded at boot with
 * {@link #loadAll}. A hold therefore outlives both a relog and a restart, which is the whole point —
 * release is manual and only manual.
 */
public final class Confiscation {

    /** One held player. {@code reason} may be null; {@code actor} is the admin who ran the command. */
    public record Hold(UUID uuid, String reason, String actor, long startedEpoch) {}

    private static final Map<UUID, Hold> HELD = new ConcurrentHashMap<>();

    private Confiscation() {}

    /** The per-tick predicate. Cheap, null-safe, never throws — it runs inside the tick loop. */
    public static boolean isHeld(UUID uuid) {
        return uuid != null && HELD.containsKey(uuid);
    }

    /** The hold for this player, or null if they are not held. */
    public static Hold get(UUID uuid) {
        return uuid == null ? null : HELD.get(uuid);
    }

    /** Every current hold. Snapshot — safe to iterate while the map changes. */
    public static Collection<Hold> all() {
        return List.copyOf(HELD.values());
    }

    /** Places (or replaces) a hold in memory. Persistence is the caller's job. */
    public static void hold(Hold hold) {
        HELD.put(hold.uuid(), hold);
    }

    /** Lifts a hold in memory. Returns the hold that was lifted, or null if none was in force. */
    public static Hold release(UUID uuid) {
        return uuid == null ? null : HELD.remove(uuid);
    }

    /**
     * Replaces the entire set from storage at boot. Deliberately REPLACES rather than merges — the
     * database is authoritative at startup, and a merge would resurrect a hold that was released
     * while this process was down.
     */
    public static void loadAll(Collection<Hold> holds) {
        HELD.clear();
        for (Hold h : holds) HELD.put(h.uuid(), h);
    }

    /** Test seam, and used by nothing in production. */
    public static void clearAll() {
        HELD.clear();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.moderation.ConfiscationTest"`
Expected: PASS — 7 tests, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/moderation/Confiscation.java \
        src/test/java/com/coffeesaerosmp/auth/moderation/ConfiscationTest.java
git commit -m "feat(moderation): pure in-memory confiscation state

Persistence is deliberately elsewhere: isHeld() runs every tick, and a class
that imports AsyncIo would pull in Minecraft and stop being unit-testable.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: F3 — Confiscation persistence, enforcement and commands

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/moderation/ConfiscationStore.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java` (`shouldBlock` at line 422; `onPlayerTick`)
- Modify: `src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java` (new `/authmod` branches)
- Modify: `src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java` (boot load; command-block listener)
- Modify: `src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java` (allow-list key)

- [ ] **Step 1: Write the persistence store**

Create `src/main/java/com/coffeesaerosmp/auth/moderation/ConfiscationStore.java`:

```java
package com.coffeesaerosmp.auth.moderation;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MySQL persistence for {@link Confiscation}. Writes are async; the boot read is not. */
public final class ConfiscationStore {

    private ConfiscationStore() {}

    /**
     * Loads every hold into memory. Call once, at server start, AFTER the schema exists.
     * A DB outage leaves the set empty — holds are then not enforced, which is the honest
     * failure direction: we cannot prove someone is held, so we do not freeze them.
     */
    public static void loadInto() {
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) {
            CoffeesAeroAuth.LOGGER.warn("[Confiscate] DB unavailable at boot — no holds loaded.");
            return;
        }
        List<Confiscation.Hold> out = new ArrayList<>();
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT uuid, reason, actor, started_epoch FROM confiscations")) {
            while (rs.next()) {
                try {
                    out.add(new Confiscation.Hold(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("reason"),
                        rs.getString("actor"),
                        rs.getLong("started_epoch")));
                } catch (IllegalArgumentException badUuid) {
                    CoffeesAeroAuth.LOGGER.warn("[Confiscate] skipping unparseable uuid row: {}",
                        rs.getString("uuid"));
                }
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Confiscate] load failed: {}", e.toString());
            return;
        }
        Confiscation.loadAll(out);
        CoffeesAeroAuth.LOGGER.info("[Confiscate] Loaded {} active hold(s).", out.size());
    }

    /** Persists a hold. Memory is updated by the caller first so enforcement is immediate. */
    public static void persist(Confiscation.Hold hold) {
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO confiscations (uuid, reason, actor, started_epoch) VALUES (?,?,?,?) " +
                     "ON DUPLICATE KEY UPDATE reason=VALUES(reason), actor=VALUES(actor), " +
                     "started_epoch=VALUES(started_epoch)")) {
                ps.setString(1, hold.uuid().toString());
                ps.setString(2, hold.reason());
                ps.setString(3, hold.actor());
                ps.setLong(4, hold.startedEpoch());
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Confiscate] persist failed for {}: {}",
                    hold.uuid(), e.toString());
            }
        });
    }

    /** Removes a hold row. */
    public static void delete(UUID uuid) {
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM confiscations WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Confiscate] delete failed for {}: {}", uuid, e.toString());
            }
        });
    }
}
```

- [ ] **Step 2: Wire the enforcement predicate**

In `src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java`, replace the body of `shouldBlock` (line 422) with:

```java
    private static boolean shouldBlock(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        // A confiscated player is blocked by every handler already registered against this
        // predicate — break, place, right-click block, right-click item, attack, pickup, drop,
        // container open. One line rather than a second "may not act" implementation that would
        // drift out of sync with this one.
        if (com.coffeesaerosmp.auth.moderation.Confiscation.isHeld(player.getUUID())) return true;
        return CoffeesAeroAuth.AUTH_MANAGER != null
            && !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID());
    }
```

- [ ] **Step 3: Add the movement freeze**

In the same file, in `onPlayerTick(EntityTickEvent.Pre event)`, insert immediately after the `CoffeesAeroAuth.AUTH_MANAGER.onTick(player);` line:

```java
        // Confiscation freeze. Same mechanism AuthManager already uses for unauthenticated
        // players: pin the position and zero the velocity every tick.
        //
        // 🔴 THIS IS WHY THE HANDLER MUST STAY ON EntityTickEvent (see the class javadoc above).
        // A teleport performed inside PlayerTickEvent is reverted by absMoveTo on the very next
        // line of ServerGamePacketListenerImpl.tick(), while leaving awaitingPositionFromClient
        // armed — which permanently breaks movement. That froze the whole lobby on 2026-09-07.
        // The unauthenticated freeze and this one now BOTH depend on that, so do not "optimise"
        // this handler onto PlayerTickEvent.
        com.coffeesaerosmp.auth.moderation.Confiscation.Hold held =
            com.coffeesaerosmp.auth.moderation.Confiscation.get(player.getUUID());
        if (held != null) {
            double[] at = HELD_POS.computeIfAbsent(player.getUUID(),
                k -> new double[]{player.getX(), player.getY(), player.getZ()});
            player.teleportTo(at[0], at[1], at[2]);
            player.setDeltaMovement(0, 0, 0);
            player.fallDistance = 0;
            if (player.tickCount % 200 == 0) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c§lCONFISCATED §7— you cannot act until an admin releases you."
                    + (held.reason() == null || held.reason().isBlank()
                        ? "" : "\n§7Reason: §f" + held.reason())
                    + "\n§7You can still talk in chat."));
            }
        } else {
            HELD_POS.remove(player.getUUID());
        }
```

And add this field to the top of the class, directly under `public class PlayerRestrictEvents {`:

```java
    /** Where each held player was pinned. Cleared the moment the hold lifts. */
    private static final java.util.Map<java.util.UUID, double[]> HELD_POS =
        new java.util.concurrent.ConcurrentHashMap<>();
```

- [ ] **Step 4: Add the command allow-list config key**

In `src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java`, declare the field next to the other `public static final` declarations near the top:

```java
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CONFISCATE_ALLOWED_COMMANDS;
```

And in the spec builder, immediately after the existing `b.push("home"); ... b.pop();` block, add:

```java
        b.push("moderation");
        CONFISCATE_ALLOWED_COMMANDS = b
            .comment("Root command literals a CONFISCATED player may still run (lowercase, no slash).",
                     "Everything else is blocked. Empty by default — a confiscated player is meant to",
                     "talk to the admin holding them, and chat is never blocked.")
            .defineListAllowEmpty("confiscateAllowedCommands",
                java.util.List.<String>of(),
                () -> "msg",
                o -> o instanceof String s && !s.isBlank());
        b.pop();
```

- [ ] **Step 5: Block commands for held players**

In `src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java`, inside the existing `NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.CommandEvent e) -> { ... });` registration at line 230, add at the very top of the lambda body:

```java
            // Confiscated players run nothing outside the allow-list. Chat is untouched —
            // they have to be able to answer the admin holding them.
            if (e.getParseResults().getContext().getSource().getEntity()
                    instanceof net.minecraft.server.level.ServerPlayer sp
                    && com.coffeesaerosmp.auth.moderation.Confiscation.isHeld(sp.getUUID())) {
                String root = e.getParseResults().getReader().getString().trim();
                if (root.startsWith("/")) root = root.substring(1);
                int sp2 = root.indexOf(' ');
                if (sp2 > 0) root = root.substring(0, sp2);
                if (!AuthConfig.CONFISCATE_ALLOWED_COMMANDS.get().contains(root.toLowerCase())) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§cYou are confiscated — commands are disabled. Speak to an admin in chat."));
                    e.setCanceled(true);
                    return;
                }
            }
```

- [ ] **Step 6: Load holds at boot**

In `CoffeesAeroAuth.java`, immediately after the existing `WATCHDOG.start(PROFILE_STORE);` line (line 402), add:

```java
        com.coffeesaerosmp.auth.moderation.ConfiscationStore.loadInto();
```

- [ ] **Step 7: Add the commands**

In `src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java`, inside the `Commands.literal("authmod")` tree (the one starting at line 140), add these branches:

```java
            // /authmod confiscate <player> [reason]  ·  /authmod confiscate list
            // /authmod release <player>
            //
            // Held state persists to MySQL and reloads at boot, so it survives a relog AND a
            // restart. Release is manual and only manual — owner decision, 2026-09-22.
            .then(Commands.literal("confiscate")
                .then(Commands.literal("list")
                    .executes(ctx -> confiscateList(ctx.getSource())))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> confiscate(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "player"), null))
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> confiscate(ctx.getSource(),
                            EntityArgument.getPlayer(ctx, "player"),
                            StringArgumentType.getString(ctx, "reason"))))))
            .then(Commands.literal("release")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> release(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "player")))))
```

And add these methods to the same class:

```java
    /**
     * Freezes a player completely until an admin releases them.
     *
     * <p>🔴 An op (permission 4) can never be confiscated. Without this an admin could freeze
     * themselves or another admin out of their own server, with no in-game way back.
     */
    private static int confiscate(CommandSourceStack src, ServerPlayer target, String reason) {
        if (target.hasPermissions(4)) {
            src.sendFailure(Component.literal(
                "§cRefusing: " + target.getGameProfile().getName()
                + " is an op. Confiscating an op could lock you out of your own server."));
            return 0;
        }
        if (Confiscation.isHeld(target.getUUID())) {
            src.sendFailure(Component.literal("§c"
                + target.getGameProfile().getName() + " is already confiscated."));
            return 0;
        }
        String actor = src.getTextName();
        Confiscation.Hold hold = new Confiscation.Hold(
            target.getUUID(), reason, actor, System.currentTimeMillis());
        Confiscation.hold(hold);
        ConfiscationStore.persist(hold);
        InfractionLog.record(target.getUUID(), "CONFISCATE",
            reason == null ? "(no reason given)" : reason, actor);

        target.sendSystemMessage(Component.literal(
            "§c§lYou have been CONFISCATED by " + actor + "."
            + (reason == null || reason.isBlank() ? "" : "\n§7Reason: §f" + reason)
            + "\n§7You cannot move or interact. You CAN talk — speak to the admin."));
        src.sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName()
            + " is now confiscated. Release with §f/authmod release "
            + target.getGameProfile().getName()), true);
        return 1;
    }

    private static int release(CommandSourceStack src, ServerPlayer target) {
        Confiscation.Hold was = Confiscation.release(target.getUUID());
        if (was == null) {
            src.sendFailure(Component.literal("§c"
                + target.getGameProfile().getName() + " is not confiscated."));
            return 0;
        }
        ConfiscationStore.delete(target.getUUID());
        InfractionLog.record(target.getUUID(), "RELEASE",
            "held " + ((System.currentTimeMillis() - was.startedEpoch()) / 1000L) + "s",
            src.getTextName());

        target.sendSystemMessage(Component.literal(
            "§aYou have been released. Normal play resumes."));
        src.sendSuccess(() -> Component.literal("§aReleased "
            + target.getGameProfile().getName() + "."), true);
        return 1;
    }

    private static int confiscateList(CommandSourceStack src) {
        var holds = Confiscation.all();
        if (holds.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7Nobody is confiscated."), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal("§6Confiscated (" + holds.size() + "):"), false);
        long now = System.currentTimeMillis();
        for (Confiscation.Hold h : holds) {
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE == null
                ? null : CoffeesAeroAuth.PROFILE_STORE.get(h.uuid());
            String name = p != null ? p.username : h.uuid().toString();
            long mins = (now - h.startedEpoch()) / 60000L;
            src.sendSuccess(() -> Component.literal("§7 · §f" + name + " §7by §f" + h.actor()
                + " §7(" + mins + "m ago)"
                + (h.reason() == null || h.reason().isBlank() ? "" : " §8— " + h.reason())), false);
        }
        return 1;
    }
```

Add these imports to `ProfileCommands.java` if not already present:

```java
import com.coffeesaerosmp.auth.moderation.Confiscation;
import com.coffeesaerosmp.auth.moderation.ConfiscationStore;
import com.coffeesaerosmp.auth.tracking.InfractionLog;
```

> `InfractionLog` is created in Task 9. If you are executing tasks strictly in order, create it now as the stub below and let Task 9 fill in the body — or reorder so Task 9's Step 1 runs first. Do **not** leave the import dangling.

```java
package com.coffeesaerosmp.auth.tracking;

import java.util.UUID;

/** Filled in by Task 9. */
public final class InfractionLog {
    private InfractionLog() {}
    public static void record(UUID uuid, String type, String detail, String actor) { }
}
```

- [ ] **Step 8: Compile**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Verify in game**

1. `/authmod confiscate <testaccount> testing the hold`
2. As the test account confirm: cannot walk, cannot break, cannot place, cannot open a chest, cannot attack, cannot run `/spawn`.
3. Confirm chat **still works**.
4. Relog → still held. Restart the server → still held (this proves the DB round trip).
5. `/authmod confiscate list` shows them with the reason and elapsed time.
6. `/authmod release <testaccount>` → normal play resumes with no restart.
7. `/authmod confiscate <an op>` → refused.
8. 🔴 **Then confirm an ordinary authenticated player can still move.** That is the 09-07 lobby-freeze regression, and watching the held player cannot catch it.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/moderation/ConfiscationStore.java \
        src/main/java/com/coffeesaerosmp/auth/tracking/InfractionLog.java \
        src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java \
        src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java \
        src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java \
        src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java
git commit -m "feat(moderation): confiscation mode — freeze, block, persist, release

Enforcement is one predicate ORed into the existing shouldBlock, so every
already-registered handler inherits it. The freeze reuses AuthManager's
EntityTickEvent path verbatim; ops cannot be confiscated.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: F4 — Exploit rules (pure, unit-tested) + config

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/exploit/ExploitRules.java`
- Test: `src/test/java/com/coffeesaerosmp/auth/exploit/ExploitRulesTest.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java`

**Background:** "Swivel Bearing" is `simulated:swivel_bearing`, from the `simulated` mod nested inside `create-aeronautics-bundled-1.21.1-1.3.2.jar`. It assembles a **Sable sub-level**, not a Create `Contraption`. The exploit to detect is `create:item_drain` assembled onto it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/coffeesaerosmp/auth/exploit/ExploitRulesTest.java`:

```java
package com.coffeesaerosmp.auth.exploit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExploitRulesTest {

    private static final Set<String> FLAGGED   = Set.of("create:item_drain");
    private static final Set<String> ASSEMBLERS = Set.of("simulated:swivel_bearing");

    @BeforeEach
    void reset() {
        ExploitRules.clearCooldowns();
    }

    @Test
    void watchesOnlyConfiguredAssemblers() {
        assertTrue(ExploitRules.watches(ASSEMBLERS, "simulated:swivel_bearing"));
        assertFalse(ExploitRules.watches(ASSEMBLERS, "create:mechanical_bearing"));
    }

    @Test
    void wildcardAssemblerWatchesEverything() {
        Set<String> any = Set.of("*");
        assertTrue(ExploitRules.watches(any, "create:mechanical_bearing"));
        assertTrue(ExploitRules.watches(any, "aeronautics:propeller_bearing"));
    }

    @Test
    void anEmptyFlaggedListDisablesDetectionEntirely() {
        assertFalse(ExploitRules.watches(Set.of(), "simulated:swivel_bearing"),
            "no assemblers configured means nothing is watched");
    }

    @Test
    void isFlaggedMatchesExactBlockIds() {
        assertTrue(ExploitRules.isFlagged(FLAGGED, "create:item_drain"));
        assertFalse(ExploitRules.isFlagged(FLAGGED, "create:basin"));
        assertFalse(ExploitRules.isFlagged(FLAGGED, null));
    }

    @Test
    void parseIdsLowercasesTrimsAndDropsBlanks() {
        assertEquals(Set.of("create:item_drain", "create:basin"),
            ExploitRules.parseIds(List.of("  Create:Item_Drain ", "create:basin", "", "   ")));
        assertEquals(Set.of(), ExploitRules.parseIds(null));
    }

    /**
     * A bearing in a redstone loop re-assembles constantly. Without a cooldown that is an
     * admin-role ping every few seconds.
     */
    @Test
    void firstAlertPassesAndRepeatsInsideTheCooldownAreSuppressed() {
        assertTrue(ExploitRules.shouldAlert("k1", 1_000L, 600));
        assertFalse(ExploitRules.shouldAlert("k1", 1_000L + 599_000L, 600));
    }

    @Test
    void theAlertFiresAgainOnceTheCooldownExpires() {
        assertTrue(ExploitRules.shouldAlert("k1", 1_000L, 600));
        assertTrue(ExploitRules.shouldAlert("k1", 1_000L + 600_001L, 600));
    }

    @Test
    void cooldownsAreKeyedIndependentlyPerAnchorAndBlock() {
        assertTrue(ExploitRules.shouldAlert("k1", 1_000L, 600));
        assertTrue(ExploitRules.shouldAlert("k2", 1_000L, 600),
            "a different anchor must not inherit another anchor's cooldown");
    }

    @Test
    void aZeroCooldownNeverSuppresses() {
        assertTrue(ExploitRules.shouldAlert("k1", 1_000L, 0));
        assertTrue(ExploitRules.shouldAlert("k1", 1_001L, 0));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.exploit.ExploitRulesTest"`
Expected: FAIL — `package com.coffeesaerosmp.auth.exploit does not exist`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/coffeesaerosmp/auth/exploit/ExploitRules.java`:

```java
package com.coffeesaerosmp.auth.exploit;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decision half of the assembly-exploit detector: config parsing, matching, and alert
 * rate-limiting. Pure — no Minecraft imports — so it is unit-testable without a running game.
 * {@link AssemblyScanner} owns everything that touches a level.
 *
 * <p>The rule is a matrix: an ANCHOR block (the thing doing the assembling, e.g. a Swivel Bearing)
 * crossed with FLAGGED blocks (the thing we care about being assembled, e.g. an Item Drain). Both
 * sides are config lists, so the next exploit is a config edit and a restart rather than a rebuild.
 */
public final class ExploitRules {

    /** Anchor id -> epoch millis of the last alert for that key. */
    private static final Map<String, Long> LAST_ALERT = new ConcurrentHashMap<>();

    private ExploitRules() {}

    /** Normalises a config list into a lookup set: trimmed, lowercased, blanks dropped. */
    public static Set<String> parseIds(List<? extends String> raw) {
        Set<String> out = new HashSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Should an assembly anchored on this block be scanned at all? This is the cheap early exit
     * that runs on EVERY contraption assembly on the server, so it must stay a set lookup.
     * The literal {@code "*"} in the assembler list means "every assembler".
     */
    public static boolean watches(Set<String> assemblers, String anchorId) {
        if (assemblers == null || assemblers.isEmpty() || anchorId == null) return false;
        return assemblers.contains("*")
            || assemblers.contains(anchorId.toLowerCase(Locale.ROOT));
    }

    /** Is this block one we alert on? */
    public static boolean isFlagged(Set<String> flagged, String blockId) {
        return blockId != null && flagged != null
            && flagged.contains(blockId.toLowerCase(Locale.ROOT));
    }

    /**
     * True if an alert for {@code key} may fire now. A Swivel Bearing driven by a redstone clock
     * re-assembles constantly, and without this every one of those would ping the admin role.
     *
     * @param key             identity of the thing being alerted on — anchor position + block id
     * @param nowMillis       current epoch millis
     * @param cooldownSeconds 0 disables suppression entirely
     */
    public static boolean shouldAlert(String key, long nowMillis, int cooldownSeconds) {
        if (cooldownSeconds <= 0) return true;
        Long last = LAST_ALERT.get(key);
        if (last != null && nowMillis - last <= cooldownSeconds * 1000L) return false;
        LAST_ALERT.put(key, nowMillis);
        return true;
    }

    /** Test seam. */
    public static void clearCooldowns() {
        LAST_ALERT.clear();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.exploit.ExploitRulesTest"`
Expected: PASS — 9 tests.

- [ ] **Step 5: Add the config keys**

In `AuthConfig.java`, declare alongside the other fields:

```java
    public static final ModConfigSpec.BooleanValue EXPLOIT_DETECT_ENABLED;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXPLOIT_FLAGGED_BLOCKS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXPLOIT_ASSEMBLER_BLOCKS;
    public static final ModConfigSpec.IntValue     EXPLOIT_SCAN_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue     EXPLOIT_ALERT_COOLDOWN_SECONDS;
```

And in the builder, after the `b.push("moderation"); ... b.pop();` block from Task 4:

```java
        b.push("exploit");
        EXPLOIT_DETECT_ENABLED = b
            .comment("Alert admins when a flagged block is assembled onto a watched bearing.",
                     "ALERT ONLY — nothing is blocked and no assembly is refused.")
            .define("exploitDetectEnabled", true);
        EXPLOIT_FLAGGED_BLOCKS = b
            .comment("Block ids that raise an alert when found in an assembling structure.",
                     "Empty disables detection. Unknown ids are logged once at boot and ignored.")
            .defineListAllowEmpty("exploitFlaggedBlocks",
                java.util.List.of("create:item_drain"),
                () -> "create:item_drain",
                o -> o instanceof String s && !s.isBlank());
        EXPLOIT_ASSEMBLER_BLOCKS = b
            .comment("Anchor block ids to watch. The literal \"*\" watches every assembler.",
                     "simulated:swivel_bearing is the Swivel Bearing from the Create: Aeronautics bundle.")
            .defineListAllowEmpty("exploitAssemblerBlocks",
                java.util.List.of("simulated:swivel_bearing"),
                () -> "simulated:swivel_bearing",
                o -> o instanceof String s && !s.isBlank());
        EXPLOIT_SCAN_MAX_BLOCKS = b
            .comment("Stop scanning an assembling structure after this many blocks.",
                     "Bounds the tick cost on very large ships. A capped scan is logged.")
            .defineInRange("exploitScanMaxBlocks", 20000, 100, 500000);
        EXPLOIT_ALERT_COOLDOWN_SECONDS = b
            .comment("Minimum seconds between alerts for the same anchor + block.",
                     "A bearing on a redstone clock would otherwise ping admins continuously.")
            .defineInRange("exploitAlertCooldownSeconds", 600, 0, 86400);
        b.pop();
```

- [ ] **Step 6: Compile and commit**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

```bash
git add src/main/java/com/coffeesaerosmp/auth/exploit/ExploitRules.java \
        src/test/java/com/coffeesaerosmp/auth/exploit/ExploitRulesTest.java \
        src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java
git commit -m "feat(exploit): configurable flagged-block x assembler rules with alert cooldown

Pure matching + rate limiting, unit tested. Defaults are create:item_drain
on simulated:swivel_bearing.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: F4 — Assembly scanner and mixins

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/exploit/AssemblyScanner.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/mixin/SimAssemblyContraptionMixin.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/mixin/CreateContraptionMixin.java`
- Modify: `src/main/resources/coffees_aero_auth.mixins.json`

**Verified hook points (checked with `javap` against the shipped jars):**

| Target | Signature | Accessors |
|---|---|---|
| `dev.simulated_team.simulated.util.assembly.SimAssemblyContraption` | `public boolean searchMovedStructure(Level, BlockPos)` | `public final BlockPos anchor`, `public Collection<BlockPos> getBlocks()` |
| `com.simibubi.create.content.contraptions.Contraption` | `public boolean searchMovedStructure(Level, BlockPos, Direction)` | `public BlockPos anchor`, `protected Map<BlockPos, StructureBlockInfo> blocks` |

Both return **before** any block is moved, so the structure is still readable in the main level by position.

- [ ] **Step 1: Write the scanner**

Create `src/main/java/com/coffeesaerosmp/auth/exploit/AssemblyScanner.java`:

```java
package com.coffeesaerosmp.auth.exploit;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Raises a watchdog alert when a flagged block is assembled onto a watched bearing.
 *
 * <h2>Why assembly time</h2>
 * Both hooks fire from {@code searchMovedStructure}, which walks the structure and returns BEFORE
 * any block moves. At that moment every position is still readable in the main level, so one pass
 * over the already-computed position set answers the question with no extra world scan.
 *
 * <h2>⚠️ Attribution is best-effort, and the alert says so</h2>
 * {@code SwivelBearingBlockEntity.assemble()} takes no player — assembly is triggered by rotation,
 * redstone or {@code assembleNextTick}, and the client's {@code AssemblePacket} is only one of
 * several routes in. There is no reliable "who did this" available here, so the alert reports
 * NEARBY PLAYERS under a field named {@code Nearby} and never names a culprit.
 */
public final class AssemblyScanner {

    /** Radius in blocks around the anchor used to list who was standing there. */
    private static final double NEARBY_RADIUS = 32.0;

    private AssemblyScanner() {}

    /**
     * Entry point for both mixins. Must be cheap for the overwhelmingly common case of an assembly
     * nobody cares about — that is the first three lines.
     *
     * @param level     the level the structure is being assembled in
     * @param anchor    the assembling block's position
     * @param positions every position in the structure
     */
    public static void scan(Level level, BlockPos anchor, Collection<BlockPos> positions) {
        try {
            if (level == null || level.isClientSide() || anchor == null || positions == null) return;
            if (!AuthConfig.EXPLOIT_DETECT_ENABLED.get()) return;

            Set<String> assemblers = ExploitRules.parseIds(AuthConfig.EXPLOIT_ASSEMBLER_BLOCKS.get());
            String anchorId = idOf(level, anchor);
            if (!ExploitRules.watches(assemblers, anchorId)) return;

            Set<String> flagged = ExploitRules.parseIds(AuthConfig.EXPLOIT_FLAGGED_BLOCKS.get());
            if (flagged.isEmpty()) return;

            int cap = AuthConfig.EXPLOIT_SCAN_MAX_BLOCKS.get();
            int scanned = 0;
            boolean capped = false;
            java.util.Map<String, Integer> hits = new java.util.LinkedHashMap<>();

            for (BlockPos p : positions) {
                if (scanned++ >= cap) { capped = true; break; }
                String id = idOf(level, p);
                if (ExploitRules.isFlagged(flagged, id)) {
                    hits.merge(id, 1, Integer::sum);
                }
            }
            if (capped) {
                CoffeesAeroAuth.LOGGER.warn(
                    "[Exploit] scan capped at {} blocks for {} at {} — raise exploitScanMaxBlocks "
                    + "if this structure is legitimate.", cap, anchorId, anchor.toShortString());
            }
            if (hits.isEmpty()) return;

            String hitSummary = hits.entrySet().stream()
                .map(e -> e.getKey() + " ×" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("?");

            String key = anchor.toShortString() + "|" + String.join(",", hits.keySet());
            if (!ExploitRules.shouldAlert(key, System.currentTimeMillis(),
                    AuthConfig.EXPLOIT_ALERT_COOLDOWN_SECONDS.get())) {
                return;
            }

            // 🔑 Severity.HIGH is load-bearing. WatchdogManager attaches the "<@&adminRole>"
            // mention with allowed_mentions ONLY when severity >= HIGH. Embeds alone notify
            // nobody, so dropping this to MEDIUM would silently remove the admin ping.
            if (CoffeesAeroAuth.WATCHDOG != null) {
                CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(
                    Severity.HIGH,
                    "Flagged block assembled",
                    "None — alert only. Investigate in game.",
                    "Flagged",   hitSummary,
                    "Assembler", anchorId,
                    "Location",  level.dimension().location() + "  "
                                 + anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ(),
                    "Teleport",  "/execute in " + level.dimension().location()
                                 + " run tp @s " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ(),
                    "Nearby",    nearbyPlayers(level, anchor),
                    "Structure", positions.size() + " blocks"));
            }
            CoffeesAeroAuth.LOGGER.warn("[Exploit] {} assembled on {} at {} in {} — alerted.",
                hitSummary, anchorId, anchor.toShortString(), level.dimension().location());

        } catch (Throwable t) {
            // This runs inside contraption assembly. A detector must never be able to break a
            // player's ship, so every failure degrades to a log line.
            CoffeesAeroAuth.LOGGER.error("[Exploit] scan failed (assembly unaffected): {}", t.toString());
        }
    }

    /** Registry id of the block at a position, lowercase, or null when unavailable. */
    private static String idOf(Level level, BlockPos pos) {
        try {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
            return key == null ? null : key.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Players within {@link #NEARBY_RADIUS} of the anchor. This is CONTEXT, not blame — see the
     * class javadoc. Returns "—" when nobody is close, which is itself informative: it means the
     * assembly was almost certainly redstone-driven.
     */
    private static String nearbyPlayers(Level level, BlockPos anchor) {
        try {
            List<String> names = new ArrayList<>();
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                if (p.level() != level) continue;
                if (p.distanceToSqr(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5)
                        <= NEARBY_RADIUS * NEARBY_RADIUS) {
                    names.add(p.getGameProfile().getName());
                }
            }
            return names.isEmpty() ? "— (nobody within 32 blocks)" : String.join(", ", names);
        } catch (Exception e) {
            return "— (lookup failed)";
        }
    }
}
```

- [ ] **Step 2: Write the Simulated mixin**

Create `src/main/java/com/coffeesaerosmp/auth/mixin/SimAssemblyContraptionMixin.java`:

```java
package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.exploit.AssemblyScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks Simulated's assembly search — the path a SWIVEL BEARING and the Physics Assembler take.
 *
 * <p>Simulated is nested inside {@code create-aeronautics-bundled}, not a separate pack entry, and
 * a swivel-bearing assembly produces a SABLE SUB-LEVEL rather than a Create {@code Contraption} —
 * which is why Create's hook alone would never see it.
 *
 * <p>Verified against the shipped jar: {@code searchMovedStructure(Level, BlockPos)} is public and
 * returns before any block is moved, and both {@code anchor} and {@code getBlocks()} are public.
 */
@Mixin(targets = "dev.simulated_team.simulated.util.assembly.SimAssemblyContraption", remap = false)
public abstract class SimAssemblyContraptionMixin {

    @org.spongepowered.asm.mixin.Shadow(remap = false)
    public BlockPos anchor;

    @Inject(method = "searchMovedStructure", at = @At("RETURN"), require = 1, remap = false)
    private void aeroauth$scanAssembly(Level level, BlockPos pos,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;   // failed assembly — nothing moved
        @SuppressWarnings("unchecked")
        java.util.Collection<BlockPos> blocks =
            ((dev.simulated_team.simulated.util.assembly.SimAssemblyContraption) (Object) this).getBlocks();
        AssemblyScanner.scan(level, this.anchor != null ? this.anchor : pos, blocks);
    }
}
```

> If the compile fails because `dev.simulated_team.simulated` is not on the compile classpath, replace the cast with reflection on `getBlocks()` rather than adding a compile dependency on a jar-in-jar mod:
> ```java
>         java.util.Collection<BlockPos> blocks;
>         try {
>             Object self = this;
>             var m = self.getClass().getMethod("getBlocks");
>             blocks = (java.util.Collection<BlockPos>) m.invoke(self);
>         } catch (Exception e) {
>             return;   // API moved — degrade to no detection rather than breaking assembly
>         }
> ```

- [ ] **Step 3: Write the Create mixin**

Create `src/main/java/com/coffeesaerosmp/auth/mixin/CreateContraptionMixin.java`:

```java
package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.exploit.AssemblyScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Hooks Create's assembly search — every Create-family bearing, including Create: Aeronautics'
 * Propeller Bearing and Offroad's Borehead Bearing, since all of them extend {@code Contraption}.
 *
 * <p>Verified against create-1.21.1-6.0.10: {@code anchor} is public and {@code blocks} is
 * protected, so {@code @Shadow} reaches both from a mixin on the class itself.
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.Contraption", remap = false)
public abstract class CreateContraptionMixin {

    @Shadow(remap = false)
    public BlockPos anchor;

    @Shadow(remap = false)
    protected Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks;

    @Inject(method = "searchMovedStructure", at = @At("RETURN"), require = 1, remap = false)
    private void aeroauth$scanAssembly(Level level, BlockPos pos, Direction dir,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;   // failed assembly — nothing moved
        if (this.blocks == null || this.blocks.isEmpty()) return;
        AssemblyScanner.scan(level, this.anchor != null ? this.anchor : pos, this.blocks.keySet());
    }
}
```

- [ ] **Step 4: Register both mixins**

In `src/main/resources/coffees_aero_auth.mixins.json`, add to the `"server"` array after `"LeaveMessageMixin"`:

```json
        "SimAssemblyContraptionMixin",
        "CreateContraptionMixin",
```

- [ ] **Step 5: Compile**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`. If it fails on the Simulated cast, apply the reflection fallback from Step 2.

- [ ] **Step 6: Verify in game**

🔴 **A clean boot does NOT validate these mixins.** Both target classes load lazily, on the first contraption assembly of the run. The boot test must be followed by actually assembling something.

1. Boot. Confirm no mixin apply error.
2. Build a Swivel Bearing with an **Item Drain** in the attached structure. Assemble it.
3. Confirm the watchdog channel receives a **HIGH** alert that **pings the admin role**, with the correct dimension + XYZ and a `Nearby` field.
4. Disassemble and re-assemble within 10 minutes → **no** second ping.
5. Assemble a normal contraption with no item drain → **silence**.
6. Assemble a Create Mechanical Bearing with an item drain → **silence** (it is not in `exploitAssemblerBlocks`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/exploit/AssemblyScanner.java \
        src/main/java/com/coffeesaerosmp/auth/mixin/SimAssemblyContraptionMixin.java \
        src/main/java/com/coffeesaerosmp/auth/mixin/CreateContraptionMixin.java \
        src/main/resources/coffees_aero_auth.mixins.json
git commit -m "feat(exploit): alert admins when an Item Drain is assembled on a Swivel Bearing

Two hooks on searchMovedStructure, both firing before any block moves.
Severity.HIGH is deliberate — it is the threshold at which WatchdogManager
attaches the admin-role mention. Attribution is best-effort and the alert
says so: it lists nearby players, never a culprit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: F5 — Invsee slot map (pure, unit-tested)

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/invsee/InvseeSlotMap.java`
- Test: `src/test/java/com/coffeesaerosmp/auth/invsee/InvseeSlotMapTest.java`

**Problem:** `Inventory` implements `Container` with **41** slots (36 main + 4 armor + 1 offhand), which is not a vanilla menu size. A 45-slot (five-row) view needs a mapping, with 41–44 inert.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/coffeesaerosmp/auth/invsee/InvseeSlotMapTest.java`:

```java
package com.coffeesaerosmp.auth.invsee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvseeSlotMapTest {

    @Test
    void theViewIsFortyFiveSlots() {
        assertEquals(45, InvseeSlotMap.VIEW_SIZE);
    }

    @Test
    void mainInventorySlotsMapStraightThrough() {
        for (int i = 0; i <= 35; i++) {
            assertEquals(i, InvseeSlotMap.toInventoryIndex(i), "main slot " + i);
        }
    }

    @Test
    void armourAndOffhandMapToTheirVanillaIndices() {
        assertEquals(36, InvseeSlotMap.toInventoryIndex(36));
        assertEquals(37, InvseeSlotMap.toInventoryIndex(37));
        assertEquals(38, InvseeSlotMap.toInventoryIndex(38));
        assertEquals(39, InvseeSlotMap.toInventoryIndex(39));
        assertEquals(40, InvseeSlotMap.toInventoryIndex(40), "offhand");
    }

    @Test
    void thePaddingSlotsMapToNothing() {
        for (int i = 41; i <= 44; i++) {
            assertEquals(-1, InvseeSlotMap.toInventoryIndex(i), "padding slot " + i);
        }
    }

    @Test
    void outOfRangeSlotsMapToNothingRatherThanThrowing() {
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(-1));
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(45));
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(9999));
    }

    @Test
    void inertSlotsAreExactlyTheUnmappedOnes() {
        assertFalse(InvseeSlotMap.isInert(0));
        assertFalse(InvseeSlotMap.isInert(40));
        assertTrue(InvseeSlotMap.isInert(41));
        assertTrue(InvseeSlotMap.isInert(44));
        assertTrue(InvseeSlotMap.isInert(-5));
    }

    @Test
    void everyMappedSlotIsDistinctSoNoTwoViewSlotsShareAStack() {
        boolean[] seen = new boolean[41];
        for (int v = 0; v < InvseeSlotMap.VIEW_SIZE; v++) {
            int idx = InvseeSlotMap.toInventoryIndex(v);
            if (idx < 0) continue;
            assertFalse(seen[idx], "inventory index " + idx + " is reachable from two view slots");
            seen[idx] = true;
        }
        for (int i = 0; i < 41; i++) {
            assertTrue(seen[i], "inventory index " + i + " is unreachable from the view");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.invsee.InvseeSlotMapTest"`
Expected: FAIL — `package com.coffeesaerosmp.auth.invsee does not exist`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/coffeesaerosmp/auth/invsee/InvseeSlotMap.java`:

```java
package com.coffeesaerosmp.auth.invsee;

/**
 * Maps the 45 slots of a five-row chest view onto the 41 slots of a player {@code Inventory}.
 *
 * <p>{@code Inventory} is a {@code Container} of 41 (36 main + 4 armor + 1 offhand), which is not a
 * vanilla menu size — every stock chest menu is a multiple of 9. Rather than invent a custom screen
 * (which would need a client mod), the view is a standard five-row (45-slot) chest and the last four slots are
 * inert padding.
 *
 * <p>Pure integer arithmetic: no Minecraft imports, so the mapping is unit-testable. The identity
 * mapping is deliberate — {@code Inventory.getItem} already orders main 0–35, armor 36–39, offhand
 * 40, so no translation is needed and inventing one would only create a chance to get it wrong.
 */
public final class InvseeSlotMap {

    /** Five rows of nine = 45. */
    public static final int VIEW_SIZE = 45;

    /** Slots backed by the real inventory: 0–40. */
    public static final int BACKED_SIZE = 41;

    private InvseeSlotMap() {}

    /**
     * The {@code Inventory} index behind a view slot, or {@code -1} if the slot is padding or out
     * of range. Never throws — this is called from menu click handling.
     */
    public static int toInventoryIndex(int viewSlot) {
        if (viewSlot < 0 || viewSlot >= BACKED_SIZE) return -1;
        return viewSlot;
    }

    /** True for slots that must render as a barrier and swallow clicks. */
    public static boolean isInert(int viewSlot) {
        return toInventoryIndex(viewSlot) < 0;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.invsee.InvseeSlotMapTest"`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/invsee/InvseeSlotMap.java \
        src/test/java/com/coffeesaerosmp/auth/invsee/InvseeSlotMapTest.java
git commit -m "feat(invsee): 45-slot view mapping over the 41-slot player Inventory

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: F5 — Invsee containers, menus and commands

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/invsee/PlayerInventoryView.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/invsee/InvseeMenu.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/invsee/InvseeManager.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/commands/InvseeCommands.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java` (menu-slam exemption)
- Modify: `src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java` (command registration, logout hook)

- [ ] **Step 1: Write the live inventory view**

Create `src/main/java/com/coffeesaerosmp/auth/invsee/PlayerInventoryView.java`:

```java
package com.coffeesaerosmp.auth.invsee;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A live 45-slot {@link Container} over another player's real {@code Inventory}.
 *
 * <p>Reads and writes pass STRAIGHT THROUGH — an admin removing an item removes it from the player,
 * and the player sees it go. Slots 41–44 are inert padding (see {@link InvseeSlotMap}) and render
 * as barriers.
 */
public class PlayerInventoryView implements Container {

    private final ServerPlayer target;

    public PlayerInventoryView(ServerPlayer target) {
        this.target = target;
    }

    public ServerPlayer target() {
        return target;
    }

    private static ItemStack barrier() {
        ItemStack s = new ItemStack(Items.BARRIER);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("§8—"));
        return s;
    }

    @Override public int getContainerSize() { return InvseeSlotMap.VIEW_SIZE; }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < InvseeSlotMap.BACKED_SIZE; i++) {
            if (!target.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        return idx < 0 ? barrier() : target.getInventory().getItem(idx);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        return idx < 0 ? ItemStack.EMPTY : target.getInventory().removeItem(idx, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        return idx < 0 ? ItemStack.EMPTY : target.getInventory().removeItemNoUpdate(idx);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        if (idx < 0) return;                       // padding swallows writes
        target.getInventory().setItem(idx, stack);
    }

    /**
     * Pushes the change to the TARGET's own client. Without this the admin sees the edit and the
     * player does not, until something else happens to resync them.
     */
    @Override
    public void setChanged() {
        target.getInventory().setChanged();
        target.containerMenu.broadcastChanges();
        target.inventoryMenu.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        // Valid only while the target is still actually connected.
        return !target.hasDisconnected();
    }

    @Override
    public void clearContent() {
        // Deliberately unsupported: "clear this player's inventory" is not an invsee operation,
        // and a stray call here would be silent mass item loss.
    }
}
```

- [ ] **Step 2: Write the menu**

Create `src/main/java/com/coffeesaerosmp/auth/invsee/InvseeMenu.java`:

```java
package com.coffeesaerosmp.auth.invsee;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;

/**
 * The container menu behind {@code /invsee} and {@code /invsee_echest}.
 *
 * <p>Implements {@link Marker} so {@code PlayerRestrictEvents}' lobby menu-slam guard can exempt it
 * — that guard closes any menu that is not the viewer's own inventory for anyone below permission
 * 4, which would otherwise slam an invsee shut on the next tick.
 *
 * <p>{@code readOnly} is set for OFFLINE snapshots. An offline view must never write back: the
 * backing container is a detached copy, and persisting it would mean writing another player's
 * {@code .dat} behind their back.
 */
public class InvseeMenu extends ChestMenu {

    /** Marker for the lobby menu-slam exemption. */
    public interface Marker {}

    public static class Marked extends InvseeMenu implements Marker {
        public Marked(MenuType<?> type, int id, Inventory viewer, Container backing,
                      int rows, boolean readOnly) {
            super(type, id, viewer, backing, rows, readOnly);
        }
    }

    private final boolean readOnly;

    protected InvseeMenu(MenuType<?> type, int id, Inventory viewer, Container backing,
                         int rows, boolean readOnly) {
        super(type, id, viewer, backing, rows);
        this.readOnly = readOnly;
    }

    public static InvseeMenu.Marked fiveRows(int id, Inventory viewer, Container backing, boolean readOnly) {
        return new InvseeMenu.Marked(MenuType.GENERIC_9x5, id, viewer, backing, 5, readOnly);
    }

    public static InvseeMenu.Marked threeRows(int id, Inventory viewer, Container backing, boolean readOnly) {
        return new InvseeMenu.Marked(MenuType.GENERIC_9x3, id, viewer, backing, 3, readOnly);
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (readOnly) return;                              // snapshot view — swallow every click
        if (slot >= 0 && slot < this.getContainer().getContainerSize()
                && InvseeSlotMap.isInert(slot)) {
            return;                                        // padding slot — swallow
        }
        super.clicked(slot, button, type, player);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.getContainer().stillValid(player);
    }
}
```

> `MenuType.GENERIC_9x5` gives exactly the 45 slots `InvseeSlotMap.VIEW_SIZE` declares (5 rows × 9). If `getContainer()` is not accessible on `ChestMenu` in this mapping, use the accessor name your IDE reports and adjust — it exists as `getContainer()` in 1.21.1 Mojmap.

- [ ] **Step 3: Write the open/lifecycle manager**

Create `src/main/java/com/coffeesaerosmp/auth/invsee/InvseeManager.java`:

```java
package com.coffeesaerosmp.auth.invsee;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens invsee views and closes them when the target leaves.
 *
 * <p>A live view is backed by the target's real {@code Inventory}. If the target disconnects while
 * a menu is open, that menu is writing into an object vanilla is about to discard — so every viewer
 * is closed on logout. {@code PlayerLoggedOutEvent} firing BEFORE the save is exactly right here:
 * closing before the save is what we want.
 */
public final class InvseeManager {

    /** target uuid -> viewers currently looking at them. */
    private static final Map<UUID, Set<UUID>> VIEWERS = new ConcurrentHashMap<>();

    private InvseeManager() {}

    public static void trackOpen(UUID target, UUID viewer) {
        VIEWERS.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(viewer);
    }

    /** Closes every open invsee menu pointed at this player. Call from the logout handler. */
    public static void closeViewersOf(MinecraftServer server, UUID target) {
        Set<UUID> viewers = VIEWERS.remove(target);
        if (viewers == null || server == null) return;
        for (UUID v : viewers) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(v);
            if (viewer != null && viewer.containerMenu instanceof InvseeMenu.Marker) {
                viewer.closeContainer();
                viewer.sendSystemMessage(Component.literal(
                    "§7The player you were viewing disconnected — view closed."));
            }
        }
    }

    public static void forgetViewer(UUID viewer) {
        VIEWERS.values().forEach(s -> s.remove(viewer));
    }

    /**
     * Reads an OFFLINE player's inventory or ender chest out of their {@code .dat} into a detached
     * snapshot.
     *
     * <p>🔴 One-directional by design. This is never written back — writing an offline {@code .dat}
     * is how inventories get duped or lost.
     *
     * @param enderChest true to read {@code EnderItems}, false to read {@code Inventory}
     * @return the snapshot, or null if there is no playerdata for this uuid
     */
    public static Container offlineSnapshot(MinecraftServer server, UUID uuid, boolean enderChest) {
        Path dat = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
        if (!Files.exists(dat)) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList(enderChest ? "EnderItems" : "Inventory", 10);
            HolderLookup.Provider lookup = server.registryAccess();

            int size = enderChest ? 27 : InvseeSlotMap.VIEW_SIZE;
            SimpleContainer snap = new SimpleContainer(size);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompound(i);
                int slot = item.getByte("Slot") & 255;
                if (slot >= size) continue;
                ItemStack stack = ItemStack.parse(lookup, item).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) snap.setItem(slot, stack);
            }
            return snap;
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Invsee] offline read failed for {}: {}", uuid, e.toString());
            return null;
        }
    }
}
```

- [ ] **Step 4: Write the commands**

Create `src/main/java/com/coffeesaerosmp/auth/commands/InvseeCommands.java`:

```java
package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.admin.AccountTransfer;
import com.coffeesaerosmp.auth.invsee.InvseeManager;
import com.coffeesaerosmp.auth.invsee.InvseeMenu;
import com.coffeesaerosmp.auth.invsee.PlayerInventoryView;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.UUID;

/**
 * {@code /invsee <player>} and {@code /invsee_echest <player>}.
 *
 * <p>Live and editable when the target is online; a read-only snapshot when they are not. Both
 * commands take a NAME rather than an {@code EntityArgument}, because the offline case is half the
 * point and an online-only argument type would reject it.
 */
public final class InvseeCommands {

    private InvseeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsee")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> open(ctx.getSource(),
                    StringArgumentType.getString(ctx, "player"), false))));

        dispatcher.register(Commands.literal("invsee_echest")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> open(ctx.getSource(),
                    StringArgumentType.getString(ctx, "player"), true))));
    }

    private static int open(CommandSourceStack src, String name, boolean enderChest) {
        ServerPlayer viewer;
        try {
            viewer = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("§cOnly a player can open an inventory view."));
            return 0;
        }

        // An admin looking inside someone's belongings belongs on the record, the same as any
        // other moderation action.
        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.recordAdminCommand(viewer,
                (enderChest ? "/invsee_echest " : "/invsee ") + name);
        }

        ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(name);
        if (target != null) {
            Container backing = enderChest
                ? target.getEnderChestInventory()
                : new PlayerInventoryView(target);
            String title = "§a" + name + (enderChest ? " — Ender Chest" : " — Inventory");
            openMenu(viewer, backing, title, enderChest, false);
            InvseeManager.trackOpen(target.getUUID(), viewer.getUUID());
            return 1;
        }

        UUID uuid = AccountTransfer.offlineUuid(name);
        Container snap = InvseeManager.offlineSnapshot(src.getServer(), uuid, enderChest);
        if (snap == null) {
            src.sendFailure(Component.literal("§cNo playerdata for §f" + name
                + "§c — check the spelling. (uuid " + uuid + ")"));
            return 0;
        }
        openMenu(viewer, snap,
            "§c[READ-ONLY] §7" + name + (enderChest ? " — Ender Chest" : " — Inventory"),
            enderChest, true);
        viewer.sendSystemMessage(Component.literal(
            "§7Offline snapshot — edits are disabled. Writing an offline .dat is how inventories "
            + "get duped or lost."));
        return 1;
    }

    private static void openMenu(ServerPlayer viewer, Container backing, String title,
                                 boolean threeRows, boolean readOnly) {
        viewer.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal(title); }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return threeRows
                    ? InvseeMenu.threeRows(id, inv, backing, readOnly)
                    : InvseeMenu.fiveRows(id, inv, backing, readOnly);
            }
        });
    }
}
```

- [ ] **Step 5: Exempt invsee menus from the lobby menu-slam guard**

In `PlayerRestrictEvents.onPlayerTick`, change the container-lockdown condition to also exempt our menu:

```java
        if (player.containerMenu != player.inventoryMenu && lobbyLocked(player)
                && !isEasyNpcMenu(player.containerMenu)
                && !(player.containerMenu instanceof com.coffeesaerosmp.auth.invsee.InvseeMenu.Marker)) {
            player.closeContainer();
        }
```

- [ ] **Step 6: Register the commands and the logout hook**

In `CoffeesAeroAuth.java`, in `onRegisterCommands` (line 704), add after the existing registrations:

```java
        com.coffeesaerosmp.auth.commands.InvseeCommands.register(event.getDispatcher());
```

And in the existing `PlayerLoggedOutEvent` handler (or register one alongside the others if none exists yet), add:

```java
        com.coffeesaerosmp.auth.invsee.InvseeManager.closeViewersOf(
            event.getEntity().getServer(), event.getEntity().getUUID());
        com.coffeesaerosmp.auth.invsee.InvseeManager.forgetViewer(event.getEntity().getUUID());
```

- [ ] **Step 7: Compile**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Verify in game**

1. `/invsee <online player>` → move an item out and back in; confirm **they see it change**.
2. `/invsee_echest <online player>` → same, 27 slots.
3. `/invsee <offline player>` → opens with the `[READ-ONLY]` title; clicks do nothing.
4. Open an invsee **while in the lobby** at permission 3 → confirm it is **not** slammed shut.
5. Open a live view, then have the target log out → the menu closes cleanly with a message; no crash, no item loss.
6. Confirm the watchdog channel logged each open as an admin command.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/invsee/ \
        src/main/java/com/coffeesaerosmp/auth/commands/InvseeCommands.java \
        src/main/java/com/coffeesaerosmp/auth/events/PlayerRestrictEvents.java \
        src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java
git commit -m "feat(invsee): /invsee and /invsee_echest, live online and read-only offline

Offline views are never written back. Live views close when the target
disconnects, and are exempted from the lobby menu-slam guard.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: F2 — Tracking stores

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/tracking/PlayerSessionLog.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/tracking/ActivitySampler.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/tracking/FootprintSampler.java`
- Rewrite: `src/main/java/com/coffeesaerosmp/auth/tracking/InfractionLog.java` (stub from Task 4)
- Modify: `src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/events/ChatEvents.java`

🔴 **Every write goes through `AsyncIo.submit`. Nothing here may touch the database on the join path.**

- [ ] **Step 1: Write the infraction log (replacing the Task 4 stub)**

Replace `src/main/java/com/coffeesaerosmp/auth/tracking/InfractionLog.java` with:

```java
package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Append-only per-player infraction history.
 *
 * <p>Written from call sites that ALREADY EXIST — chat-filter hits, watchdog events, confiscations,
 * warns, bans, name rejections. No new event subscriptions: a per-event listener is how this server
 * lost 7.89% of the server thread to recipe advancements.
 */
public final class InfractionLog {

    private InfractionLog() {}

    /**
     * @param type one of CHAT_FILTER, WATCHDOG, CONFISCATE, RELEASE, WARN, BAN, NAME_REJECT
     * @param actor admin name, or "system"
     */
    public static void record(UUID uuid, String type, String detail, String actor) {
        if (uuid == null || type == null) return;
        long now = System.currentTimeMillis();
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO infractions (uuid, type, detail, actor, epoch) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, type);
                ps.setString(3, detail == null ? null
                    : detail.length() > 512 ? detail.substring(0, 512) : detail);
                ps.setString(4, actor == null ? "system" : actor);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Infraction] write failed: {}", e.toString());
            }
        });
    }

    /** How many infractions this player has. Blocking — call off the server thread. */
    public static int count(UUID uuid) {
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return 0;
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM infractions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Infraction] count failed: {}", e.toString());
            return 0;
        }
    }
}
```

- [ ] **Step 2: Write the session log**

Create `src/main/java/com/coffeesaerosmp/auth/tracking/PlayerSessionLog.java`:

```java
package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * One row per session, written on logout.
 *
 * <p>Safe to write from {@code PlayerLoggedOutEvent} — that event fires before vanilla saves the
 * {@code .dat}, but this is our own table, not the player file.
 *
 * <p>Duration comes from the profile's {@code sessionStartEpoch}, the same field the existing
 * playtime consumers read. It is never recomputed independently, because a second definition of
 * "how long were they on" is a second thing to drift.
 */
public final class PlayerSessionLog {

    private PlayerSessionLog() {}

    public static void recordLogout(ServerPlayer player, String reason) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return;
        var profile = store.get(uuid);
        if (profile == null || profile.sessionStartEpoch <= 0) return;   // never authenticated

        long start = profile.sessionStartEpoch;
        long end   = System.currentTimeMillis();
        int  secs  = (int) Math.max(0, (end - start) / 1000L);
        String ip  = ipOf(player);
        String role = LobbyHandoff.isLobbyRole() ? "LOBBY" : "SMP";

        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO session_log (uuid, login_epoch, logout_epoch, duration_s, ip, " +
                     "server_role, reason) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, start);
                ps.setLong(3, end);
                ps.setInt(4, secs);
                ps.setString(5, ip);
                ps.setString(6, role);
                ps.setString(7, reason);
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Tracking] session write failed for {}: {}",
                    uuid, e.toString());
            }
        });
    }

    private static String ipOf(ServerPlayer player) {
        try {
            String raw = player.connection.getRemoteAddress().toString();
            if (raw.startsWith("/")) raw = raw.substring(1);
            int colon = raw.lastIndexOf(':');
            return colon > 0 ? raw.substring(0, colon) : raw;
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 3: Write the activity sampler**

Create `src/main/java/com/coffeesaerosmp/auth/tracking/ActivitySampler.java`:

```java
package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples vanilla stats every 5 minutes and on logout, and upserts one row per player.
 *
 * <h2>Sampled, never event-hooked — on purpose</h2>
 * A per-event listener on inventory or world activity is exactly the shape that cost this server
 * 7.89% of the server thread on recipe-advancement listeners. Sampling costs one map read per
 * online player per five minutes.
 *
 * <p>Chat and command counts are our own, incremented in handlers that already run on every message
 * and every command, so they add a {@code long++} rather than a new subscription.
 */
public final class ActivitySampler {

    private static final Map<UUID, AtomicLong> CHAT     = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicLong> COMMANDS = new ConcurrentHashMap<>();

    private ActivitySampler() {}

    public static void onChat(UUID uuid)    { CHAT.computeIfAbsent(uuid, k -> new AtomicLong()).incrementAndGet(); }
    public static void onCommand(UUID uuid) { COMMANDS.computeIfAbsent(uuid, k -> new AtomicLong()).incrementAndGet(); }

    /** Samples every online player. Call on the server thread. */
    public static void sampleAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sample(p);
    }

    /** Samples one player. Call on the server thread — it reads the live stats map. */
    public static void sample(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        try {
            var stats = player.getStats();

            long mined = 0;
            for (var block : BuiltInRegistries.BLOCK) {
                mined += stats.getValue(Stats.BLOCK_MINED.get(block));
            }
            long used = 0;
            for (var item : BuiltInRegistries.ITEM) {
                used += stats.getValue(Stats.ITEM_USED.get(item));
            }
            int deaths      = stats.getValue(Stats.CUSTOM, Stats.DEATHS);
            int mobKills    = stats.getValue(Stats.CUSTOM, Stats.MOB_KILLS);
            int playerKills = stats.getValue(Stats.CUSTOM, Stats.PLAYER_KILLS);
            long distance   = (long) stats.getValue(Stats.CUSTOM, Stats.WALK_ONE_CM)
                            + stats.getValue(Stats.CUSTOM, Stats.SPRINT_ONE_CM)
                            + stats.getValue(Stats.CUSTOM, Stats.FLY_ONE_CM);

            long chat = CHAT.getOrDefault(uuid, new AtomicLong()).get();
            long cmds = COMMANDS.getOrDefault(uuid, new AtomicLong()).get();
            long now  = System.currentTimeMillis();

            AsyncIo.submit(() -> {
                if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO player_stats (uuid, blocks_mined, items_used, deaths, " +
                         "mob_kills, player_kills, distance_cm, chat_messages, commands_run, " +
                         "sampled_epoch) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                         // OVERWRITE, never GREATEST(old,new): after a /authmod freshstart the
                         // vanilla counters legitimately drop to zero, and a max-merge would keep
                         // ghost numbers forever.
                         "ON DUPLICATE KEY UPDATE blocks_mined=VALUES(blocks_mined), " +
                         "items_used=VALUES(items_used), deaths=VALUES(deaths), " +
                         "mob_kills=VALUES(mob_kills), player_kills=VALUES(player_kills), " +
                         "distance_cm=VALUES(distance_cm), " +
                         "chat_messages=chat_messages+VALUES(chat_messages), " +
                         "commands_run=commands_run+VALUES(commands_run), " +
                         "sampled_epoch=VALUES(sampled_epoch)")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, mined);
                    ps.setLong(3, used);
                    ps.setInt(4, deaths);
                    ps.setInt(5, mobKills);
                    ps.setInt(6, playerKills);
                    ps.setLong(7, distance);
                    ps.setLong(8, chat);
                    ps.setLong(9, cmds);
                    ps.setLong(10, now);
                    ps.executeUpdate();
                } catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.error("[Tracking] stats write failed for {}: {}",
                        uuid, e.toString());
                }
            });
            // Counters are deltas since the last sample — zero them once handed off.
            CHAT.remove(uuid);
            COMMANDS.remove(uuid);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Tracking] sample failed for {}: {}", uuid, e.toString());
        }
    }
}
```

- [ ] **Step 4: Write the footprint sampler**

Create `src/main/java/com/coffeesaerosmp/auth/tracking/FootprintSampler.java`:

```java
package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Records each player's world footprint: ships owned and chunks claimed.
 *
 * <p>Piggybacks the schedule the ship census already runs on rather than adding a timer. Where
 * ownership cannot be resolved the count is OMITTED rather than guessed — {@code SableShips} already
 * degrades every lookup failure to "unknown" and logs once, and that honesty is inherited here.
 */
public final class FootprintSampler {

    private FootprintSampler() {}

    public static void sampleAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID uuid = p.getUUID();
            int ships  = shipsOwnedBy(server, uuid);
            int chunks = chunksClaimedBy(server, uuid);
            if (ships < 0 && chunks < 0) continue;      // nothing resolvable — write nothing
            long now = System.currentTimeMillis();

            AsyncIo.submit(() -> {
                if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO player_footprint (uuid, ships_owned, chunks_claimed, sampled_epoch) " +
                         "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE ships_owned=VALUES(ships_owned), " +
                         "chunks_claimed=VALUES(chunks_claimed), sampled_epoch=VALUES(sampled_epoch)")) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, Math.max(0, ships));
                    ps.setInt(3, Math.max(0, chunks));
                    ps.setLong(4, now);
                    ps.executeUpdate();
                } catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.error("[Tracking] footprint write failed for {}: {}",
                        uuid, e.toString());
                }
            });
        }
    }

    /**
     * Ships attributable to this player. Returns -1 when AeroClaims is absent or the lookup fails,
     * which the caller treats as "do not write a number" rather than "zero".
     */
    private static int shipsOwnedBy(MinecraftServer server, UUID uuid) {
        try {
            Class<?> mgr = Class.forName("com.markae.aeroclaims.AeroClaimManager");
            var m = mgr.getMethod("getClaimCountFor", UUID.class);
            Object r = m.invoke(null, uuid);
            return r instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Chunks claimed. -1 when no claim provider answers. */
    private static int chunksClaimedBy(MinecraftServer server, UUID uuid) {
        try {
            Class<?> mgr = Class.forName("com.markae.aeroclaims.AeroClaimManager");
            var m = mgr.getMethod("getChunkCountFor", UUID.class);
            Object r = m.invoke(null, uuid);
            return r instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
```

> ⚠️ `AeroClaimManager`'s method names are **not verified** — they are reached by reflection precisely so that a wrong guess degrades to `-1` ("unknown") instead of failing to compile or crashing. During implementation, confirm the real signatures from `server-mods/aeroclaims-0.9.1.jar` with `javap` and correct the two method names. If no such accessor exists, leave both returning `-1` and note in the commit that footprint is unpopulated pending an AeroClaims API.

- [ ] **Step 5: Wire the samplers and counters**

In `CoffeesAeroAuth.java`, next to the other repeating tasks started at server start, add a 5-minute sampler (`Repeating` already exists in `util/`; follow the pattern used for the existing periodic tasks in that file):

```java
        // Player-activity sampling. 5 minutes, server thread, read-only against vanilla stats.
        com.coffeesaerosmp.auth.util.Repeating.everySeconds(300, () -> {
            com.coffeesaerosmp.auth.tracking.ActivitySampler.sampleAll(SERVER);
            com.coffeesaerosmp.auth.tracking.FootprintSampler.sampleAll(SERVER);
        });
```

> Match the actual `Repeating` API in `util/Repeating.java`; if its method is named differently, use the same call shape the existing periodic tasks in `CoffeesAeroAuth.java` use.

In the `PlayerLoggedOutEvent` handler, add before the invsee lines from Task 8:

```java
        com.coffeesaerosmp.auth.tracking.ActivitySampler.sample(
            (net.minecraft.server.level.ServerPlayer) event.getEntity());
        com.coffeesaerosmp.auth.tracking.PlayerSessionLog.recordLogout(
            (net.minecraft.server.level.ServerPlayer) event.getEntity(), "QUIT");
```

In `events/ChatEvents.java`, in the handler that already runs for every chat message, add:

```java
        com.coffeesaerosmp.auth.tracking.ActivitySampler.onChat(player.getUUID());
```

In the existing `CommandEvent` listener in `CoffeesAeroAuth.java` (line 230), after the confiscation block from Task 4, add:

```java
            if (e.getParseResults().getContext().getSource().getEntity()
                    instanceof net.minecraft.server.level.ServerPlayer cp) {
                com.coffeesaerosmp.auth.tracking.ActivitySampler.onCommand(cp.getUUID());
            }
```

- [ ] **Step 6: Compile**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Verify in game**

1. Join, mine a few blocks, send a chat message, run a command, die once, log out.
2. `SELECT * FROM session_log ORDER BY id DESC LIMIT 1;` → one row with a sane `duration_s`, your IP and `server_role='SMP'`.
3. `SELECT * FROM player_stats WHERE uuid='<your uuid>';` → non-zero `blocks_mined`, `deaths=1`, `chat_messages>=1`, `commands_run>=1`.
4. Confirm **no** new DB query appears on the join path — joins should be no slower than before.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/tracking/ \
        src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java \
        src/main/java/com/coffeesaerosmp/auth/events/ChatEvents.java
git commit -m "feat(tracking): session history, sampled activity counters, footprint, infractions

Sampled every 5 minutes rather than event-hooked, and every write goes
through AsyncIo. Nothing touches the database on the join path.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 10: F2 — `/authmod player <name>` stat sheet

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/tracking/StatSheet.java`
- Test: `src/test/java/com/coffeesaerosmp/auth/tracking/StatSheetTest.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/coffeesaerosmp/auth/tracking/StatSheetTest.java`:

```java
package com.coffeesaerosmp.auth.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatSheetTest {

    @Test
    void durationRendersAsHoursAndMinutes() {
        assertEquals("0m",      StatSheet.duration(0));
        assertEquals("59m",     StatSheet.duration(59 * 60));
        assertEquals("1h 0m",   StatSheet.duration(3600));
        assertEquals("2h 30m",  StatSheet.duration(2 * 3600 + 30 * 60));
        assertEquals("100h 1m", StatSheet.duration(100 * 3600 + 60));
    }

    @Test
    void negativeDurationsClampRatherThanRenderNonsense() {
        assertEquals("0m", StatSheet.duration(-1));
        assertEquals("0m", StatSheet.duration(Integer.MIN_VALUE));
    }

    @Test
    void distanceRendersCentimetresAsKilometres() {
        assertEquals("0.00 km",  StatSheet.distance(0));
        assertEquals("0.01 km",  StatSheet.distance(1_000));
        assertEquals("1.00 km",  StatSheet.distance(100_000));
        assertEquals("12.34 km", StatSheet.distance(1_234_000));
    }

    /** The sidebar level is playtime-only: Lv = 1 + floor(2 * sqrt(hours)). Owner decision 08-19. */
    @Test
    void levelIsPlaytimeOnly() {
        assertEquals(1, StatSheet.level(0));
        assertEquals(3, StatSheet.level(3600));            // 1h  -> 1 + 2*1 = 3
        assertEquals(5, StatSheet.level(4 * 3600));        // 4h  -> 1 + 2*2 = 5
        assertEquals(7, StatSheet.level(9 * 3600));        // 9h  -> 1 + 2*3 = 7
    }

    @Test
    void levelNeverGoesBelowOne() {
        assertEquals(1, StatSheet.level(-5));
    }

    @Test
    void countsGetThousandsSeparators() {
        assertEquals("0",         StatSheet.count(0));
        assertEquals("999",       StatSheet.count(999));
        assertEquals("1,000",     StatSheet.count(1000));
        assertEquals("1,234,567", StatSheet.count(1234567));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.tracking.StatSheetTest"`
Expected: FAIL — `cannot find symbol: class StatSheet`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/coffeesaerosmp/auth/tracking/StatSheet.java`:

```java
package com.coffeesaerosmp.auth.tracking;

import java.util.Locale;

/**
 * Formatting for the {@code /authmod player} stat sheet. Pure — no Minecraft imports — so every
 * rule here is unit-testable.
 */
public final class StatSheet {

    private StatSheet() {}

    /** Seconds as "2h 30m". Clamps negatives to zero rather than rendering nonsense. */
    public static String duration(long seconds) {
        if (seconds <= 0) return "0m";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    /** Centimetres as kilometres, two decimals. */
    public static String distance(long centimetres) {
        return String.format(Locale.ROOT, "%.2f km", Math.max(0, centimetres) / 100_000.0);
    }

    /**
     * Sidebar level. PLAYTIME ONLY — {@code 1 + floor(2 * sqrt(hours))}. Owner decision 2026-08-19;
     * do not add other inputs here without changing the sidebar too, or the two will disagree.
     */
    public static int level(long playtimeSeconds) {
        if (playtimeSeconds <= 0) return 1;
        double hours = playtimeSeconds / 3600.0;
        return 1 + (int) Math.floor(2 * Math.sqrt(hours));
    }

    /** Thousands separators, always with '.' grouping as ','. */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.tracking.StatSheetTest"`
Expected: PASS — 6 tests.

- [ ] **Step 5: Extend `/authmod player`**

In `ProfileCommands.java`, find the existing `Commands.literal("player")` branch (line 210) and extend the method it calls so that, after the existing profile output, it queries and appends the stat sheet. Run the four `SELECT`s **off the server thread** and post back with `server.execute`:

```java
    /**
     * Appends the 1.11.0 stat sheet to /authmod player.
     *
     * <p>🔴 Four SELECTs. They run on the AsyncIo thread and the formatted result is posted back
     * with server.execute — an admin command must not block the tick loop on the database, even a
     * local one.
     */
    private static void appendStatSheet(CommandSourceStack src, java.util.UUID uuid) {
        var server = src.getServer();
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            StringBuilder out = new StringBuilder();
            try (java.sql.Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {

                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "SELECT blocks_mined, items_used, deaths, mob_kills, player_kills, " +
                        "distance_cm, chat_messages, commands_run FROM player_stats WHERE uuid=?")) {
                    ps.setString(1, uuid.toString());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.append("\n§6Activity§7: mined §f").append(StatSheet.count(rs.getLong(1)))
                               .append(" §7· used §f").append(StatSheet.count(rs.getLong(2)))
                               .append(" §7· deaths §f").append(rs.getInt(3))
                               .append(" §7· mobs §f").append(rs.getInt(4))
                               .append(" §7· PvP §f").append(rs.getInt(5))
                               .append("\n§7  travelled §f").append(StatSheet.distance(rs.getLong(6)))
                               .append(" §7· chat §f").append(StatSheet.count(rs.getLong(7)))
                               .append(" §7· commands §f").append(StatSheet.count(rs.getLong(8)));
                        } else {
                            out.append("\n§8No activity sampled yet.");
                        }
                    }
                }

                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "SELECT ships_owned, chunks_claimed FROM player_footprint WHERE uuid=?")) {
                    ps.setString(1, uuid.toString());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.append("\n§6Footprint§7: ships §f").append(rs.getInt(1))
                               .append(" §7· chunks §f").append(rs.getInt(2));
                        }
                    }
                }

                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "SELECT login_epoch, duration_s, server_role FROM session_log " +
                        "WHERE uuid=? ORDER BY logout_epoch DESC LIMIT 5")) {
                    ps.setString(1, uuid.toString());
                    try (var rs = ps.executeQuery()) {
                        boolean any = false;
                        while (rs.next()) {
                            if (!any) { out.append("\n§6Recent sessions§7:"); any = true; }
                            out.append("\n§7 · §f").append(StatSheet.duration(rs.getInt(2)))
                               .append(" §8(").append(rs.getString(3)).append(")");
                        }
                    }
                }

                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "SELECT type, detail, epoch FROM infractions WHERE uuid=? " +
                        "ORDER BY epoch DESC LIMIT 3")) {
                    ps.setString(1, uuid.toString());
                    try (var rs = ps.executeQuery()) {
                        boolean any = false;
                        while (rs.next()) {
                            if (!any) { out.append("\n§cInfractions§7:"); any = true; }
                            out.append("\n§7 · §f").append(rs.getString(1))
                               .append(" §8").append(rs.getString(2) == null ? "" : rs.getString(2));
                        }
                    }
                }
            } catch (Exception e) {
                out.append("\n§cStat lookup failed: ").append(e.getMessage());
            }
            String text = out.toString();
            server.execute(() -> src.sendSuccess(() -> Component.literal(text), false));
        });
    }
```

Call `appendStatSheet(ctx.getSource(), uuid);` at the end of the existing `/authmod player` handler, where `uuid` is the resolved target uuid.

- [ ] **Step 6: Compile and verify**

Run: `./gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

In game: `/authmod player <name>` → the existing profile output plus Activity, Footprint, Recent sessions and Infractions. Confirm the server does not hitch while it runs.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/tracking/StatSheet.java \
        src/test/java/com/coffeesaerosmp/auth/tracking/StatSheetTest.java \
        src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java
git commit -m "feat(tracking): stat sheet on /authmod player

Four SELECTs run on AsyncIo and post back with server.execute — an admin
command must not block the tick loop on the database.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 11: F1 — Fresh start

**Files:**
- Create: `src/main/java/com/coffeesaerosmp/auth/admin/FreshStartRules.java`
- Test: `src/test/java/com/coffeesaerosmp/auth/admin/FreshStartRulesTest.java`
- Create: `src/main/java/com/coffeesaerosmp/auth/admin/FreshStart.java`
- Modify: `src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java`

**Last on purpose.** This is the only destructive feature, and it goes in after everything else is proven.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/coffeesaerosmp/auth/admin/FreshStartRulesTest.java`:

```java
package com.coffeesaerosmp.auth.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreshStartRulesTest {

    @Test
    void refusesWhenTheProfileDoesNotExist() {
        String why = FreshStartRules.refusalReason(false, true, false);
        assertNotNull(why);
        assertTrue(why.toLowerCase().contains("no profile"), why);
    }

    @Test
    void refusesWhenTheDatabaseIsDown() {
        String why = FreshStartRules.refusalReason(true, false, false);
        assertNotNull(why);
        assertTrue(why.toLowerCase().contains("mysql"), why);
    }

    @Test
    void refusesWhenTheTargetIsStillOnline() {
        String why = FreshStartRules.refusalReason(true, true, true);
        assertNotNull(why);
        assertTrue(why.toLowerCase().contains("online"), why);
    }

    @Test
    void allowsWhenTheProfileExistsTheDbIsUpAndTheyAreOffline() {
        assertNull(FreshStartRules.refusalReason(true, true, false));
    }

    /**
     * A missing profile is checked BEFORE the DB state, because "no profile" is almost always a
     * typo and telling the admin "MySQL is down" for a typo sends them chasing the wrong thing.
     */
    @Test
    void theMissingProfileMessageWinsOverTheDbMessage() {
        String why = FreshStartRules.refusalReason(false, false, false);
        assertTrue(why.toLowerCase().contains("no profile"), why);
    }

    @Test
    void theBackupFolderNameIsSortableAndCarriesTheName() {
        String f = FreshStartRules.backupFolderName("Bronze", 1_700_000_000_000L);
        assertTrue(f.startsWith("Bronze-"), f);
        assertTrue(f.matches("Bronze-\\d{8}-\\d{6}"), f);
    }

    @Test
    void theBackupFolderNameSanitisesUnsafeCharacters() {
        String f = FreshStartRules.backupFolderName("bad/name:here", 1_700_000_000_000L);
        assertFalse(f.contains("/"), f);
        assertFalse(f.contains(":"), f);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.admin.FreshStartRulesTest"`
Expected: FAIL — `cannot find symbol: class FreshStartRules`.

- [ ] **Step 3: Write the rules**

Create `src/main/java/com/coffeesaerosmp/auth/admin/FreshStartRules.java`:

```java
package com.coffeesaerosmp.auth.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The decision half of {@code /authmod freshstart}: when to refuse, and what to call the backup.
 * Pure, so every refusal path is unit-tested rather than discovered in production on a real player.
 */
public final class FreshStartRules {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private FreshStartRules() {}

    /**
     * Why this wipe must not run, or null if it may.
     *
     * <p>Order matters: a missing profile is reported FIRST, because it is almost always a typo and
     * reporting "MySQL is down" instead sends the admin chasing an outage that is not happening.
     *
     * @param profileExists does a profile exist for the resolved uuid
     * @param dbUp          is MySQL available
     * @param stillOnline   is the target still in the player list
     */
    public static String refusalReason(boolean profileExists, boolean dbUp, boolean stillOnline) {
        if (!profileExists) {
            return "No profile for that name — check the spelling. Nothing was changed.";
        }
        if (!dbUp) {
            // A wipe that clears files but cannot clear profile columns leaves a half-wiped player,
            // and the flat-file fallback would then disagree with the database.
            return "MySQL is DOWN. Refusing — a wipe now would clear the files but not the profile "
                 + "columns, leaving a half-wiped account. Retry when DB: UP.";
        }
        if (stillOnline) {
            // Vanilla holds a logged-in player's .dat and rewrites it on disconnect, so anything
            // we delete underneath them is silently restored.
            return "Target is still ONLINE. They hold their playerdata open and rewrite it on "
                 + "disconnect, which would undo the wipe. Retry once they are gone.";
        }
        return null;
    }

    /** Sortable, name-carrying backup folder: {@code Bronze-20261122-031000}. */
    public static String backupFolderName(String playerName, long epochMillis) {
        String safe = playerName.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe + "-" + STAMP.format(Instant.ofEpochMilli(epochMillis));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat test --tests "com.coffeesaerosmp.auth.admin.FreshStartRulesTest"`
Expected: PASS — 7 tests.

- [ ] **Step 5: Write the executor**

Create `src/main/java/com/coffeesaerosmp/auth/admin/FreshStart.java`:

```java
package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wipes a player's PROGRESS while keeping their ACCOUNT.
 *
 * <p>Deleted: inventory, ender chest (it rides inside the {@code .dat} as {@code EnderItems}), XP,
 * advancements, vanilla stats, position. Reset in MySQL: playtime, season playtime, first-join and
 * starter-bonus flags, return position. KEPT: password, display name, Discord link, account type,
 * name approval, join date, first IP, skin, trusted IPs.
 *
 * <p>⚠️ Resetting playtime resets their LEVEL — the sidebar level is playtime-only, so a fresh start
 * drops them to Lv 1. Intended (playtime is progress), but it is the most visible side effect and
 * the dry run says so out loud.
 *
 * <p>🔴 <b>This does NOT touch third-party mod data.</b> FTB Teams membership, FTB Chunks claims,
 * Waystones, graves and AeroClaims ship claims live in other mods' storage and are not safely
 * reachable from here. The command prints a manual checklist instead of pretending otherwise.
 */
public final class FreshStart {

    /** Per-player vanilla stores, mirroring {@code AccountTransfer}'s SUBS table. */
    private record Sub(String label, String ext, LevelResource res) {
        Path dir(MinecraftServer s) { return s.getWorldPath(res); }
    }

    private static final Sub[] SUBS = {
        new Sub("playerdata",   ".dat",      LevelResource.PLAYER_DATA_DIR),
        new Sub("playerdata",   ".dat_old",  LevelResource.PLAYER_DATA_DIR),
        new Sub("advancements", ".json",     LevelResource.PLAYER_ADVANCEMENTS_DIR),
        new Sub("stats",        ".json",     LevelResource.PLAYER_STATS_DIR),
    };

    public record Result(boolean ok, List<String> lines) {
        static Result fail(String why) {
            List<String> l = new ArrayList<>();
            l.add("§c" + why);
            return new Result(false, l);
        }
    }

    private FreshStart() {}

    /** Dry run: inspects and explains, writes nothing. Server thread only (reads PlayerList). */
    public static Result plan(MinecraftServer server, String name) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("FreshStart must run on the server thread.");
        }
        UUID uuid = AccountTransfer.offlineUuid(name);
        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return Result.fail("Profile store is not ready.");

        PlayerProfile p = store.get(uuid);
        boolean dbUp = CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
        boolean online = server.getPlayerList().getPlayer(uuid) != null;

        // The online check is informational in the plan — execute() kicks them first.
        String why = FreshStartRules.refusalReason(p != null, dbUp, false);
        if (why != null) return Result.fail(why);

        List<String> out = new ArrayList<>();
        out.add("§7target §f" + name + " §8" + uuid);
        out.add("§7account §f" + p.accountType + "§7, playtime §f" + (p.totalPlaytimeSeconds / 3600)
                + "h§7, display §f" + p.displayName);
        if (online) out.add("§e player is ONLINE — they will be KICKED first, then wiped.");
        out.add("§cWILL DELETE§7: inventory, ender chest, XP, advancements, stats, position");
        out.add("§cWILL RESET§7: playtime §8(this drops them to §fLv 1§8)§7, first-join + starter-bonus flags");
        out.add("§aWILL KEEP§7: password, display name, Discord link, approvals, trusted IPs, join date");
        out.add("§8not handled — do these by hand: FTB Teams · FTB Chunks claims · AeroClaims ships "
                + "· Waystones · graves");
        out.add("§7Run again with §fconfirm§7 to execute.");
        return new Result(true, out);
    }

    /**
     * Executes the wipe. Server thread only. The caller must already have kicked the player and
     * confirmed they are gone — see {@code ProfileCommands.freshStart}.
     */
    public static Result execute(MinecraftServer server, String name) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("FreshStart must run on the server thread.");
        }
        UUID uuid = AccountTransfer.offlineUuid(name);
        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile p = store == null ? null : store.get(uuid);
        boolean dbUp = CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
        boolean online = server.getPlayerList().getPlayer(uuid) != null;

        String why = FreshStartRules.refusalReason(p != null, dbUp, online);
        if (why != null) return Result.fail(why);

        List<String> out = new ArrayList<>();

        // 1. BACK UP FIRST. A wipe with no backup does not ship; a failed copy aborts everything.
        Path backup = server.getWorldPath(LevelResource.ROOT)
            .resolve("coffeesaeroauth").resolve("freshstart-backups")
            .resolve(FreshStartRules.backupFolderName(name, System.currentTimeMillis()));
        try {
            Files.createDirectories(backup);
            int copied = 0;
            for (Sub s : SUBS) {
                Path src = s.dir(server).resolve(uuid + s.ext());
                if (!Files.exists(src)) continue;
                Files.copy(src, backup.resolve(s.label() + "-" + uuid + s.ext()),
                    StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            out.add("§7backed up §f" + copied + "§7 file(s) to §f" + backup);
        } catch (Exception e) {
            return Result.fail("Backup FAILED (" + e.getMessage() + ") — nothing was deleted.");
        }

        // 2. Delete.
        int deleted = 0;
        for (Sub s : SUBS) {
            Path f = s.dir(server).resolve(uuid + s.ext());
            try {
                if (Files.deleteIfExists(f)) deleted++;
            } catch (Exception e) {
                out.add("§c could not delete " + f.getFileName() + ": " + e.getMessage());
            }
        }
        out.add("§7deleted §f" + deleted + "§7 file(s)");

        // 3. Reset the progress columns, and evict the cache — reads are cache-first, so a stale
        //    entry would answer for the rest of this server's uptime.
        AsyncIo.submit(() -> {
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE players SET total_playtime=0, season1_playtime=0, " +
                     "session_start_epoch=0, first_join_complete=FALSE, " +
                     "startup_bonus_given=FALSE, return_dim=NULL, return_x=0, return_y=0, return_z=0 " +
                     "WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[FreshStart] column reset failed for {}: {}",
                    uuid, e.toString());
            }
        });
        store.evict(uuid);
        out.add("§7profile progress columns reset; cache evicted");

        out.add("§a" + name + " has a fresh start. Account, password and display name are unchanged.");
        out.add("§8Still to do by hand: FTB Teams · FTB Chunks claims · AeroClaims ships · "
                + "Waystones · graves");
        CoffeesAeroAuth.LOGGER.warn("[FreshStart] {} ({}) wiped — backup at {}", name, uuid, backup);
        return new Result(true, out);
    }
}
```

> ⚠️ The `UPDATE` names columns as they are spelled in the `players` table. Confirm the exact column names for season playtime, session start, first-join and starter-bonus against `DatabaseManager.createSchema()` before running this, and correct them if they differ — a wrong column name here fails silently in the async block.

- [ ] **Step 6: Add the command**

In `ProfileCommands.java`, inside the `authmod` tree:

```java
            // /authmod freshstart <player> [confirm]
            //
            // Name-based, never EntityArgument: the target is usually offline, and an online-only
            // argument type would reject the common case. Same shape as transferaccount.
            .then(Commands.literal("freshstart")
                .then(Commands.argument("player", StringArgumentType.word())
                    // No "confirm" => dry run. This is destructive, so seeing the plan is the
                    // default and running it is the opt-in.
                    .executes(ctx -> freshStart(ctx.getSource(),
                        StringArgumentType.getString(ctx, "player"), false))
                    .then(Commands.literal("confirm")
                        .executes(ctx -> freshStart(ctx.getSource(),
                            StringArgumentType.getString(ctx, "player"), true)))))
```

And the handler:

```java
    private static int freshStart(CommandSourceStack src, String name, boolean confirm) {
        var server = src.getServer();
        if (!confirm) {
            FreshStart.Result r = FreshStart.plan(server, name);
            r.lines().forEach(l -> src.sendSuccess(() -> Component.literal(l), false));
            return r.ok() ? 1 : 0;
        }

        java.util.UUID uuid = AccountTransfer.offlineUuid(name);
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            online.connection.disconnect(Component.literal(
                "§eYour progress is being reset by an admin. Reconnect in a moment."));
            src.sendSuccess(() -> Component.literal("§7Kicked " + name + " — waiting for the save..."), false);
            // Poll on later ticks until vanilla has actually removed them. PlayerLoggedOutEvent is
            // NOT enough: it is the first statement of PlayerList.remove, before save().
            scheduleWipeAfterGone(src, server, name, uuid, 0);
            return 1;
        }
        FreshStart.Result r = FreshStart.execute(server, name);
        r.lines().forEach(l -> src.sendSuccess(() -> Component.literal(l), false));
        return r.ok() ? 1 : 0;
    }

    /** Re-checks every 20 ticks for up to 10 seconds, then gives up rather than racing the save. */
    private static void scheduleWipeAfterGone(CommandSourceStack src,
                                              net.minecraft.server.MinecraftServer server,
                                              String name, java.util.UUID uuid, int attempt) {
        if (attempt > 10) {
            src.sendFailure(Component.literal(
                "§c" + name + " did not fully disconnect within 10s — nothing was wiped. Try again."));
            return;
        }
        server.execute(() -> {
            if (server.getPlayerList().getPlayer(uuid) != null) {
                try { Thread.sleep(1000L); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                scheduleWipeAfterGone(src, server, name, uuid, attempt + 1);
                return;
            }
            FreshStart.Result r = FreshStart.execute(server, name);
            r.lines().forEach(l -> src.sendSuccess(() -> Component.literal(l), false));
        });
    }
```

> ⚠️ The `Thread.sleep` inside `server.execute` blocks the tick loop for a second. Replace it with the project's existing tick-scheduling helper if one exists (check `util/Repeating.java`); if not, schedule the retry via `server.tell(new TickTask(server.getTickCount() + 20, ...))` instead. **Do not ship the `sleep`.**

- [ ] **Step 7: Compile**

Run: `./gradlew.bat compileJava` and `./gradlew.bat test`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 8: Verify in game — on a throwaway account only**

1. Create a throwaway account, play briefly, gather items, gain a level, put something in the ender chest.
2. `/authmod freshstart <throwaway>` → plan prints, **nothing changes**.
3. `/authmod freshstart <throwaway> confirm` while they are **online** → they are kicked, then wiped.
4. Confirm the backup folder exists with up to four files.
5. Log the throwaway back in: empty inventory, empty ender chest, Lv 1, no advancements — **and the same password still works**.
6. Confirm the manual checklist printed.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/coffeesaerosmp/auth/admin/FreshStart.java \
        src/main/java/com/coffeesaerosmp/auth/admin/FreshStartRules.java \
        src/test/java/com/coffeesaerosmp/auth/admin/FreshStartRulesTest.java \
        src/main/java/com/coffeesaerosmp/auth/commands/ProfileCommands.java
git commit -m "feat(admin): /authmod freshstart — wipe progress, keep the account

Backs up all four per-player files before deleting anything, and waits for
vanilla to actually release the player rather than racing the save. Does not
touch third-party mod data; prints a manual checklist instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 12: Version bump and final verification

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change:

```
mod_version=1.10.12
```

to:

```
mod_version=1.11.0
```

`neoforge.mods.toml` reads `version = "${file.jarVersion}"`, which resolves from the jar manifest, so `gradle.properties` is the **only** place the version lives.

- [ ] **Step 2: Full build**

Run: `./gradlew.bat clean build`
Expected: `BUILD SUCCESSFUL`, all unit tests pass, and `build/libs/CoffeesAeroAuth-1.11.0.jar` exists.

- [ ] **Step 3: Verify the manifest version**

Run:

```bash
unzip -p build/libs/CoffeesAeroAuth-1.11.0.jar META-INF/MANIFEST.MF | grep Implementation-Version
```

Expected: `Implementation-Version: 1.11.0`.

- [ ] **Step 4: Run the full live checklist**

Work through every item in §11 of the spec. In particular, do not skip:

- The **F6 first-boot check** — if the server refuses to boot, `JoinMessageMixin` was already silently dead.
- The **F4 assembly test** — a clean boot does not validate those mixins.
- The **F3 "an ordinary player can still move"** check — the held player cannot reveal that regression.

- [ ] **Step 5: Commit**

```bash
git add gradle.properties
git commit -m "chore: auth 1.11.0

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Deployment

Auth 1.11.0 is a **server-side jar** and must be deployed to **both** the lobby and the SMP — one jar, two roles, and running a skewed pair against a shared database has bitten this project before (1.7.51/1.7.54).

1. Back up the world **before** the first `/authmod freshstart` is ever run in anger.
2. Upload by SFTP to both servers. Do **not** deploy from `untitled/server-mods/` — that is a stale reference mirror, not the live set.
3. Re-read the rendered host startup command and diff it against the vault; the heap flags get reverted without notice.
4. Boot both. Confirm `DB: UP` and `[DB] Schema verified.`, then run the live checklist.

**Rollback jar:** 1.10.12. The five new tables are additive and harmless to the older jar, so a rollback needs no schema work.

---

## Self-Review Notes

**Spec coverage:** F1 → Task 11 · F2 → Tasks 9, 10 · F3 → Tasks 3, 4 · F4 → Tasks 5, 6 · F5 → Tasks 7, 8 · F6 → Task 1. Schema (spec §10) → Task 2. Deployment (spec §12) → Task 12 + the Deployment section.

**Three places this plan deliberately flags uncertainty rather than inventing a fact:**

1. `FootprintSampler`'s two AeroClaims method names are unverified and reached by reflection, so a wrong guess degrades to "unknown" rather than failing. Task 9 Step 4 says to confirm them with `javap`.
2. `FreshStart`'s `UPDATE` column names must be checked against `DatabaseManager.createSchema()` — a wrong name fails silently inside the async block.
3. `scheduleWipeAfterGone` contains a `Thread.sleep` that must be replaced with tick scheduling before shipping. It is called out in Task 11 Step 6.

These are real gaps in what could be verified without a running server, and each is marked at the exact step where it must be resolved.
