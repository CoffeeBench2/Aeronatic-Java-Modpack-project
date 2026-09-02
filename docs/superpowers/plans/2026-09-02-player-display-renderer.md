# Player Display Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three competing systems that write a player's displayed name with a single `PlayerDisplay` renderer, fixing the missing clan tag in TAB, adding staff badges, and making join messages use real colours.

**Architecture:** A pure `PlayerDisplay.compose()` over plain strings decides what each surface shows; thin adapters feed it from Minecraft objects and push the result to the TAB packet, the scoreboard team prefix, chat and Discord. The pure core is unit-tested with JUnit; the adapters are boot-tested.

**Tech Stack:** Java 21, NeoForge 21.1.244 (moddev 2.0.141), JUnit 5, Gradle wrapper at `src/CoffeesAeroAuth/gradlew.bat`.

**Scope:** Spec items **E1** (renderer), **E2** (staff badges), **E5** (OP hiding), **E7** (lobby copy) from `docs/superpowers/specs/2026-09-02-player-display-and-lobby-design.md`.

**Deliberately deferred:** E3 (RGB clan tags) waits until the renderer is proven in game — `StaffBadges.isStaff()` is the hook it needs and it is cheap to add later. E4 (`/lobby`) and E6 (`/back`) are a separate plan.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/CoffeesAeroAuth/build.gradle` | add JUnit 5 to the test source set |
| `.../auth/display/PlayerDisplay.java` | **NEW** — pure composition. No Minecraft imports. |
| `.../auth/display/StaffBadges.java` | **NEW** — pure rank lookup from config strings |
| `.../auth/display/DisplayAdapter.java` | **NEW** — builds `Parts` from a `ServerPlayer` |
| `.../auth/display/HiddenOps.java` | **NEW** — per-op hide toggle, JSON-persisted |
| `.../auth/config/AuthConfig.java` | add staff + hide config keys |
| `.../auth/auth/NameVisibility.java` | nameplate prefix now comes from `PlayerDisplay` |
| `.../auth/tablist/TabListManager.java` | two senders collapse into one; lobby copy fix |
| `src/test/java/.../display/PlayerDisplayTest.java` | **NEW** — the regression suite |
| `src/test/java/.../display/StaffBadgesTest.java` | **NEW** |

`PlayerDisplay` and `StaffBadges` must not import anything from `net.minecraft` or `com.coffeesaerosmp.auth.CoffeesAeroAuth`. That is what keeps them unit-testable without a server, and it is enforced by Task 1's test compiling at all.

---

### Task 1: JUnit setup and the first failing test

**Files:**
- Modify: `src/CoffeesAeroAuth/build.gradle:32-58` (dependencies block), and add a `test` task config
- Test: `src/CoffeesAeroAuth/src/test/java/com/coffeesaerosmp/auth/display/PlayerDisplayTest.java`

- [ ] **Step 1: Add JUnit to the build**

Append to the `dependencies { }` block in `src/CoffeesAeroAuth/build.gradle`:

```gradle
    // Unit tests for the PURE display core only. PlayerDisplay/StaffBadges import no Minecraft
    // classes, so these run without a game. Anything needing a ServerPlayer is boot-tested instead.
    testImplementation platform("org.junit:junit-bom:5.10.2")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
```

And add after the `tasks.withType(JavaCompile)` block:

```gradle
tasks.named('test') {
    useJUnitPlatform()
    testLogging { events "passed", "failed", "skipped" }
}
```

- [ ] **Step 2: Write the failing test**

Create `src/CoffeesAeroAuth/src/test/java/com/coffeesaerosmp/auth/display/PlayerDisplayTest.java`:

```java
package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDisplayTest {

    private static PlayerDisplay.Parts parts() {
        return new PlayerDisplay.Parts("§6✈ ", "§c[ADMIN] ", "§7[§9AERO§7] ", "Coffee", "MrCoffeeBench");
    }

    /** THE REGRESSION THIS WHOLE PROJECT EXISTS FOR: the clan tag must survive into TAB. */
    @Test
    void tabIncludesClanTag() {
        String out = PlayerDisplay.compose(parts(), TAB, false);
        assertTrue(out.contains("[§9AERO§7]"), "clan tag missing from TAB: " + out);
    }

    @Test
    void nameplateIncludesClanTag() {
        String out = PlayerDisplay.compose(parts(), NAMEPLATE, false);
        assertTrue(out.contains("[§9AERO§7]"), "clan tag missing from nameplate: " + out);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run from `src/CoffeesAeroAuth`:

```
.\gradlew.bat test --tests "*PlayerDisplayTest*"
```

Expected: **compile failure** — `package com.coffeesaerosmp.auth.display does not exist` / `cannot find symbol: class PlayerDisplay`. That is the correct failure; the class does not exist yet.

- [ ] **Step 4: Commit the harness**

```bash
git add src/CoffeesAeroAuth/build.gradle src/CoffeesAeroAuth/src/test
git commit -m "test: add JUnit 5 and the failing PlayerDisplay clan-tag test"
```

---

### Task 2: The pure composition core

**Files:**
- Create: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/PlayerDisplay.java`
- Test: `src/CoffeesAeroAuth/src/test/java/com/coffeesaerosmp/auth/display/PlayerDisplayTest.java`

- [ ] **Step 1: Write the full test suite**

Replace the contents of `PlayerDisplayTest.java` with:

```java
package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDisplayTest {

    private static PlayerDisplay.Parts full() {
        return new PlayerDisplay.Parts("§6✈ ", "§c[ADMIN] ", "§7[§9AERO§7] ", "Coffee", "MrCoffeeBench");
    }

    private static PlayerDisplay.Parts plain() {
        return new PlayerDisplay.Parts("§8◈ ", "", "", "Steve", null);
    }

    @Test
    void tabIncludesClanTag() {
        assertTrue(PlayerDisplay.compose(full(), TAB, false).contains("[§9AERO§7]"));
    }

    @Test
    void nameplateIncludesClanTag() {
        assertTrue(PlayerDisplay.compose(full(), NAMEPLATE, false).contains("[§9AERO§7]"));
    }

    @Test
    void tabShowsRealNameToOpViewerOnly() {
        assertTrue(PlayerDisplay.compose(full(), TAB, true).contains("MrCoffeeBench"));
        assertFalse(PlayerDisplay.compose(full(), TAB, false).contains("MrCoffeeBench"));
    }

    /** A scoreboard team prefix is GLOBAL — it cannot vary per viewer, so it must never leak
     *  the real name even when the viewer is an op. */
    @Test
    void nameplateNeverShowsRealNameEvenForOps() {
        assertFalse(PlayerDisplay.compose(full(), NAMEPLATE, true).contains("MrCoffeeBench"));
    }

    @Test
    void discordStripsFormattingCodes() {
        String out = PlayerDisplay.compose(full(), DISCORD, false);
        assertFalse(out.contains("§"), "Discord output still has § codes: " + out);
        assertTrue(out.contains("AERO"));
        assertTrue(out.contains("Coffee"));
    }

    @Test
    void emptyPartsDoNotProduceStrayWhitespace() {
        String out = PlayerDisplay.compose(plain(), TAB, false);
        assertEquals("§8◈ Steve", out);
    }

    @Test
    void unmaskedPlayerGetsNoRealNameSuffixEvenForOps() {
        PlayerDisplay.Parts p = new PlayerDisplay.Parts("§6✈ ", "", "", "Coffee", null);
        assertEquals("§6✈ Coffee", PlayerDisplay.compose(p, TAB, true));
    }

    @Test
    void staffBadgeSitsBeforeClanTag() {
        String out = PlayerDisplay.compose(full(), TAB, false);
        assertTrue(out.indexOf("[ADMIN]") < out.indexOf("AERO"), "wrong order: " + out);
    }

    /** A scoreboard team PREFIX must not contain the name — the client appends the scoreboard
     *  name itself, so including it would render the name twice. */
    @Test
    void prefixExcludesTheName() {
        String out = PlayerDisplay.composePrefix(full());
        assertFalse(out.contains("Coffee"), "prefix must not contain the name: " + out);
        assertTrue(out.contains("[§9AERO§7]"));
        assertTrue(out.contains("[ADMIN]"));
        assertTrue(out.endsWith(" "), "prefix must end with a separator space: '" + out + "'");
    }

    @Test
    void prefixIsEmptyWhenNothingDecoratesTheName() {
        PlayerDisplay.Parts bare = new PlayerDisplay.Parts("", "", "", "Steve", null);
        assertEquals("", PlayerDisplay.composePrefix(bare));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
.\gradlew.bat test --tests "*PlayerDisplayTest*"
```

Expected: compile failure — `PlayerDisplay` still does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/PlayerDisplay.java`:

```java
package com.coffeesaerosmp.auth.display;

/**
 * The single source of truth for how a player's name is rendered, everywhere.
 *
 * <p>Before this class, THREE systems wrote the displayed name independently and overwrote each
 * other: {@code NameVisibility.reveal} (scoreboard team prefix), {@code sendStyledNames} and
 * {@code sendAdminNameOverlay} (both {@code UPDATE_DISPLAY_NAME}, ~2/sec). Because a tab-list
 * display name makes the client IGNORE the team prefix, and neither packet carried the clan tag,
 * the tag was painted by the team and erased from TAB twice a second. One cause, three bugs.</p>
 *
 * <p>Deliberately pure: no {@code net.minecraft} imports, no static mod state. That is what makes
 * it unit-testable without a server, and the tests encode the regression above.</p>
 */
public final class PlayerDisplay {

    /** Where the result is going. Each surface can carry only what its mechanism supports. */
    public enum Surface {
        /** {@code UPDATE_DISPLAY_NAME} packet — per viewer, supports animation. */
        TAB,
        /** Scoreboard team prefix — GLOBAL to the team, so no per-viewer content, no animation. */
        NAMEPLATE,
        /** Chat component, sent per recipient. */
        CHAT,
        /** Join/leave line, sent per recipient. */
        JOIN,
        /** Plain text for the Discord bridge — § codes stripped. */
        DISCORD
    }

    /**
     * Everything the renderer needs, already resolved to strings.
     *
     * @param badge     account badge WITH its trailing space, e.g. {@code "§6✈ "}; may be empty
     * @param staffTag  staff badge WITH its trailing space, e.g. {@code "§c[ADMIN] "}; may be empty
     * @param clanTag   clan tag WITH its trailing space, e.g. {@code "§7[§9AERO§7] "}; may be empty
     * @param name      the display name, already styled
     * @param realName  the account name when masked, else {@code null}
     */
    public record Parts(String badge, String staffTag, String clanTag, String name, String realName) {}

    private PlayerDisplay() {}

    public static String compose(Parts p, Surface surface, boolean viewerIsOp) {
        StringBuilder sb = new StringBuilder();
        append(sb, p.badge());
        append(sb, p.staffTag());
        append(sb, p.clanTag());
        sb.append(p.name() == null ? "" : p.name());

        // The real-name reveal is PER VIEWER, so it can only go on surfaces sent per viewer.
        // NAMEPLATE is a team prefix shared by everyone who can see the player — putting it there
        // would leak the account name to every player, which is the whole point of NameMask.
        boolean perViewer = surface == Surface.TAB || surface == Surface.CHAT || surface == Surface.JOIN;
        if (perViewer && viewerIsOp && p.realName() != null && !p.realName().isBlank()
            && !p.realName().equals(p.name())) {
            sb.append(" §8(").append(p.realName()).append(')');
        }

        String out = sb.toString();
        return surface == Surface.DISCORD ? stripCodes(out) : out;
    }

    /**
     * Everything that goes BEFORE the name — badge, staff tag, clan tag — and nothing else.
     *
     * <p>A scoreboard team prefix must exclude the name, because the client appends the scoreboard
     * name after it. Deriving this by string-trimming {@link #compose} would be fragile: the
     * display name and the scoreboard name differ whenever NameMask is active, so the substring
     * would not match and the name would render twice.</p>
     */
    public static String composePrefix(Parts p) {
        StringBuilder sb = new StringBuilder();
        append(sb, p.badge());
        append(sb, p.staffTag());
        append(sb, p.clanTag());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isEmpty()) sb.append(part);
    }

    /** Removes legacy § formatting codes. Used for Discord, which renders them literally. */
    public static String stripCodes(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '§' && i + 1 < in.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```
.\gradlew.bat test --tests "*PlayerDisplayTest*"
```

Expected: **10 tests PASS**.

If `emptyPartsDoNotProduceStrayWhitespace` fails, the badge already carries its trailing space and `name` must not be padded — do not add a separator in `append`.

- [ ] **Step 5: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/PlayerDisplay.java src/CoffeesAeroAuth/src/test
git commit -m "feat: PlayerDisplay pure composition core with regression tests"
```

---

### Task 3: Staff badges from config

**Files:**
- Create: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/StaffBadges.java`
- Test: `src/CoffeesAeroAuth/src/test/java/com/coffeesaerosmp/auth/display/StaffBadgesTest.java`
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java` (the `display` section, near `DISPLAY_RGB_NAMES`)

- [ ] **Step 1: Write the failing test**

Create `StaffBadgesTest.java`:

```java
package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StaffBadgesTest {

    @Test
    void resolvesRankCaseInsensitively() {
        StaffBadges b = new StaffBadges("MrCoffeeBench", "Alice, Bob", "");
        assertEquals("§4[OWNER] ", b.badgeFor("mrcoffeebench"));
        assertEquals("§c[ADMIN] ", b.badgeFor("ALICE"));
        assertEquals("§c[ADMIN] ", b.badgeFor("bob"));
    }

    @Test
    void unlistedPlayerGetsNoBadge() {
        StaffBadges b = new StaffBadges("MrCoffeeBench", "", "");
        assertEquals("", b.badgeFor("Steve"));
    }

    @Test
    void ownerWinsWhenListedTwice() {
        StaffBadges b = new StaffBadges("Coffee", "Coffee", "Coffee");
        assertEquals("§4[OWNER] ", b.badgeFor("Coffee"));
    }

    @Test
    void handlesEmptyAndBlankConfig() {
        StaffBadges b = new StaffBadges("", "  ", null);
        assertEquals("", b.badgeFor("Anyone"));
    }

    @Test
    void ignoresStrayWhitespaceAndEmptyEntries() {
        StaffBadges b = new StaffBadges("", " Alice ,, Bob ,", "");
        assertEquals("§c[ADMIN] ", b.badgeFor("Alice"));
        assertEquals("§c[ADMIN] ", b.badgeFor("Bob"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
.\gradlew.bat test --tests "*StaffBadgesTest*"
```

Expected: compile failure — `cannot find symbol: class StaffBadges`.

- [ ] **Step 3: Write the implementation**

Create `StaffBadges.java`:

```java
package com.coffeesaerosmp.auth.display;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Staff badge lookup. Rank comes from CONFIG, not from op level, so a moderator can be badged
 * without being handed command powers — and an op can go unbadged.
 *
 * <p>Pure and immutable: built from three comma-separated config strings and rebuilt when they
 * change, exactly like {@code DISPLAY_RGB_NAMES} is re-read on the tick. No Minecraft imports.</p>
 */
public final class StaffBadges {

    public static final String OWNER_BADGE = "§4[OWNER] ";
    public static final String ADMIN_BADGE = "§c[ADMIN] ";
    public static final String MOD_BADGE   = "§9[MOD] ";

    private final Set<String> owners;
    private final Set<String> admins;
    private final Set<String> mods;

    public StaffBadges(String ownerCsv, String adminCsv, String modCsv) {
        this.owners = parse(ownerCsv);
        this.admins = parse(adminCsv);
        this.mods   = parse(modCsv);
    }

    /** The badge for this account name, or {@code ""}. Highest rank wins. */
    public String badgeFor(String username) {
        if (username == null) return "";
        String key = username.toLowerCase(Locale.ROOT);
        if (owners.contains(key)) return OWNER_BADGE;
        if (admins.contains(key)) return ADMIN_BADGE;
        if (mods.contains(key))   return MOD_BADGE;
        return "";
    }

    /** True if this player may use staff-only cosmetics (the RGB clan tag). */
    public boolean isStaff(String username) {
        return !badgeFor(username).isEmpty();
    }

    private static Set<String> parse(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```
.\gradlew.bat test --tests "*StaffBadgesTest*"
```

Expected: **5 tests PASS**.

- [ ] **Step 5: Add the config keys**

In `AuthConfig.java`, declare three fields alongside `DISPLAY_RGB_NAMES`:

```java
    public static final ModConfigSpec.ConfigValue<String>  STAFF_OWNER;
    public static final ModConfigSpec.ConfigValue<String>  STAFF_ADMIN;
    public static final ModConfigSpec.ConfigValue<String>  STAFF_MOD;
```

and define them inside the existing `b.comment("Display").push("display");` section, immediately after the `DISPLAY_RGB_NAMES` definition:

```java
        STAFF_OWNER = b
            .comment("Comma-separated usernames shown with the §4[OWNER]§r badge.",
                     "Rank is config-driven, NOT op level, so a moderator can be badged without",
                     "being given command powers — and an op can go unbadged. Re-read live.")
            .define("staffOwner", "MrCoffeeBench");
        STAFF_ADMIN = b
            .comment("Comma-separated usernames shown with the §c[ADMIN]§r badge.")
            .define("staffAdmin", "");
        STAFF_MOD = b
            .comment("Comma-separated usernames shown with the §9[MOD]§r badge.")
            .define("staffMod", "");
```

- [ ] **Step 6: Build to verify the config compiles**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/StaffBadges.java src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/config/AuthConfig.java src/CoffeesAeroAuth/src/test
git commit -m "feat: config-driven staff badges, independent of op level"
```

---

### Task 4: The adapter — Parts from a ServerPlayer

**Files:**
- Create: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/DisplayAdapter.java`

This is the only class that touches Minecraft, and it stays thin on purpose.

- [ ] **Step 1: Write the adapter**

Create `DisplayAdapter.java`:

```java
package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.clan.ClanTags;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bridges Minecraft objects to {@link PlayerDisplay}'s plain-string {@link PlayerDisplay.Parts}.
 * Deliberately the ONLY class in this package that imports {@code net.minecraft} — everything
 * decision-shaped lives in the pure core so it can be unit-tested.
 */
public final class DisplayAdapter {

    private static volatile StaffBadges badges = new StaffBadges("", "", "");

    private DisplayAdapter() {}

    /** Re-read the staff lists from config. Call on the same cadence as the RGB-name refresh. */
    public static void refreshStaff() {
        badges = new StaffBadges(AuthConfig.STAFF_OWNER.get(),
                                 AuthConfig.STAFF_ADMIN.get(),
                                 AuthConfig.STAFF_MOD.get());
    }

    public static StaffBadges staff() { return badges; }

    /** Builds the render parts for a player. Never throws — a display bug must not break login. */
    public static PlayerDisplay.Parts partsFor(ServerPlayer player) {
        String username  = player.getGameProfile().getName();
        String display   = username;
        String badge     = "";
        // 🔑 The name MUST carry its own colour. Legacy § codes persist within a literal, so without
        // one the name inherits the last code emitted by the decoration — §7 gray after a clan tag,
        // or §8 near-black for a guest with no tag. Today's code already does this:
        // TabListManager used (premium ? "§6✈ §f" : "§8◈ §7") and ChatEvents prepends nameColor.
        String nameColor = "§f";
        try {
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
                ? CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) : null;
            if (p != null) {
                if (p.username != null) username = p.username;
                display = p.displayName != null ? p.displayName : username;
                boolean premium = p.getAccountType() == PlayerProfile.AccountType.PREMIUM;
                badge     = premium ? "§6✈ " : "§8◈ ";
                nameColor = premium ? "§f"   : "§7";
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Display] profile lookup failed for {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        }

        String clan = "";
        try {
            String tag = ClanTags.tagFor(player);
            if (tag != null) clan = "§7[" + ClanTags.colorFor(player) + tag + "§7] ";
        } catch (Exception ignored) {
            // FTB Teams not ready — render untagged rather than break the caller.
        }

        String realName = username.equals(display) ? null : username;
        return new PlayerDisplay.Parts(badge, badges.badgeFor(username), clan,
                                       nameColor + display, realName);
    }
}
```

- [ ] **Step 2: Build**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/DisplayAdapter.java
git commit -m "feat: DisplayAdapter builds render Parts from a ServerPlayer"
```

---

### Task 5: Nameplate goes through PlayerDisplay

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/auth/NameVisibility.java:36-52` (the `reveal` method)

- [ ] **Step 1: Replace `reveal`**

Replace the whole `reveal` method body with:

```java
    /** Verified/logged in: reveal with badge + staff tag + clan tag. A per-player team is required
     *  because a shared badge team cannot vary its prefix. */
    public static void reveal(ServerPlayer player, boolean premium) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // composePrefix, NOT compose — a team prefix must exclude the name, because the client
        // appends the scoreboard name after it.
        String prefix = com.coffeesaerosmp.auth.display.PlayerDisplay.composePrefix(
            com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(player));

        if (prefix.isBlank()) {
            removePersonalTeam(player);
            put(player, premium ? VERIFIED : GUEST);
            return;
        }
        ServerScoreboard sb = server.getScoreboard();
        PlayerTeam team = sb.getPlayerTeam(personalTeamName(player));
        if (team == null) team = sb.addPlayerTeam(personalTeamName(player));
        team.setPlayerPrefix(Component.literal(prefix));
        sb.addPlayerToTeam(player.getScoreboardName(), team);   // moves off any previous team
    }
```

- [ ] **Step 2: Build**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/auth/NameVisibility.java
git commit -m "refactor: nameplate prefix now comes from PlayerDisplay"
```

---

### Task 6: Collapse the two TAB senders into one

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/tablist/TabListManager.java:84-153` — delete `sendStyledNames` and `sendAdminNameOverlay`, add `sendTabNames`
- Modify: `TabListManager.java:68-73` — the call sites

**This is the task that actually fixes the reported bug.** Two senders racing on the same field is why whichever wrote last won.

- [ ] **Step 1: Replace both methods with one**

Delete `sendStyledNames` and `sendAdminNameOverlay` entirely. Add:

```java
    /**
     * One per-viewer TAB name send. Replaces the two methods that used to race each other on
     * UPDATE_DISPLAY_NAME every ~500ms — neither carried the clan tag, so the scoreboard team
     * prefix that DID carry it was overwritten twice a second. Ops get the real-name reveal;
     * everyone else does not, which is why this must be built per viewer.
     */
    private static void sendTabNames(MinecraftServer server, java.util.List<ServerPlayer> players) {
        java.util.List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry> plain =
            new java.util.ArrayList<>();
        java.util.List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry> opView =
            new java.util.ArrayList<>();

        for (ServerPlayer p : players) {
            var parts = com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(p);

            // Animated styles (rainbow, §k) are per-character and cannot live in a plain string,
            // so NameStyles paints the NAME and PlayerDisplay supplies the surrounding decoration.
            //
            // 🔑 Use segments(), NEVER String.replace to subtract the name. The display name is
            // routinely a SUBSTRING of the account name — "Coffee" inside "MrCoffeeBench" — so
            // replace() corrupts the op reveal to "(MrBench)", and a player whose name matches
            // their own clan tag guts the tag entirely. Verified, not theoretical.
            var segPlain = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                parts, com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.TAB, false);
            var segOp = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                parts, com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.TAB, true);

            // 🔑 NameStyles keys its lookups (owner seed, legacy rainbow config list) off the RAW
            // account username and renders onto the RAW display text — NEVER parts.name(), which
            // carries a "§f"/"§7" colour code on the front. Passing the coloured string would break
            // the username-equality and config-list lookups outright, and re-embed that code inside
            // the rendered Component, cancelling a custom /namecolor colour partway through.
            var profile = com.coffeesaerosmp.auth.CoffeesAeroAuth.PROFILE_STORE != null
                ? com.coffeesaerosmp.auth.CoffeesAeroAuth.PROFILE_STORE.get(p.getUUID()) : null;
            String rawUsername = profile != null && profile.username != null
                ? profile.username : p.getGameProfile().getName();
            String rawDisplay = profile != null && profile.displayName != null
                ? profile.displayName : rawUsername;

            Component styled = com.coffeesaerosmp.auth.util.NameStyles.nameComponent(
                p.getUUID(), rawUsername, rawDisplay);

            Component plainName = Component.literal(segPlain.prefix())
                .append(styled != null ? styled : Component.literal(segPlain.name()))
                .append(Component.literal(segPlain.suffix()));
            Component opName = Component.literal(segOp.prefix())
                .append(styled != null ? styled : Component.literal(segOp.name()))
                .append(Component.literal(segOp.suffix()));

            plain.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), plainName, null));
            opView.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), opName, null));
        }
        if (plain.isEmpty()) return;

        var pkt = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
            java.util.EnumSet.of(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
            java.util.List.of());
        ((com.coffeesaerosmp.auth.mixin.PlayerInfoPacketAccessor) (Object) pkt).aeroauth$setEntries(plain);
        var opPkt = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
            java.util.EnumSet.of(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
            java.util.List.of());
        ((com.coffeesaerosmp.auth.mixin.PlayerInfoPacketAccessor) (Object) opPkt).aeroauth$setEntries(opView);

        for (ServerPlayer viewer : players)
            viewer.connection.send(viewer.hasPermissions(2) ? opPkt : pkt);
    }
```

- [ ] **Step 2: Update the call sites**

In `onServerTick`, replace these lines:

```java
        sendAdminNameOverlay(server, players);
        // Refresh the RGB-name set from config every ~5s so edits apply live, then paint.
        if (frame % 10 == 0)
            com.coffeesaerosmp.auth.util.RainbowText.setEnabledNames(
                com.coffeesaerosmp.auth.config.AuthConfig.DISPLAY_RGB_NAMES.get());
        sendStyledNames(server, players);
```

with:

```java
        // Refresh RGB names AND staff badges from config every ~5s so edits apply live.
        if (frame % 10 == 0) {
            com.coffeesaerosmp.auth.util.RainbowText.setEnabledNames(
                com.coffeesaerosmp.auth.config.AuthConfig.DISPLAY_RGB_NAMES.get());
            com.coffeesaerosmp.auth.display.DisplayAdapter.refreshStaff();
        }
        sendTabNames(server, players);
```

- [ ] **Step 3: Build**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`. If `sendAdminNameOverlay` is still referenced anywhere, delete that reference — it has no replacement, its behaviour is now inside `sendTabNames`.

- [ ] **Step 4: Boot test**

Copy `build/libs/CoffeesAeroAuth-<version>.jar` to the local 66-mod test server's `mods/`, replacing the old jar. Start it.

Expected: reaches `Done (…)`. Search the log for `[Display]` — there must be **no** `profile lookup failed` warnings.

- [ ] **Step 5: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/tablist/TabListManager.java
git commit -m "fix: one TAB name sender, so the clan tag stops being overwritten"
```

---

### Task 7: OP join hiding

**Files:**
- Create: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/HiddenOps.java`
- Modify: `TabListManager.sendTabNames` — filter hidden ops out of the non-op packet
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/events/PlayerAuthEvents.java:14` — suppress the join line

- [ ] **Step 1: Write the toggle store**

Create `HiddenOps.java`:

```java
package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-op "hide me" toggle. Display state only, so it lives in a JSON file next to
 * {@code clan_tags.json} rather than the DB — the no-DB-for-non-essentials rule.
 * Survives restart: an op who logs out hidden comes back hidden.
 */
public final class HiddenOps {

    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
    private static volatile Path file;

    private HiddenOps() {}

    public static void initialize(Path dataDir) {
        file = dataDir.resolve("hidden_ops.json");
        HIDDEN.clear();
        if (!Files.exists(file)) return;
        try {
            JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            a.forEach(e -> {
                try { HIDDEN.add(UUID.fromString(e.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            });
            CoffeesAeroAuth.LOGGER.info("[Display] Loaded {} hidden ops.", HIDDEN.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Display] hidden_ops.json load failed: {}", e.getMessage());
        }
    }

    public static boolean isHidden(UUID uuid) { return HIDDEN.contains(uuid); }

    /** Returns the new state. */
    public static boolean toggle(UUID uuid) {
        boolean nowHidden;
        if (HIDDEN.contains(uuid)) { HIDDEN.remove(uuid); nowHidden = false; }
        else { HIDDEN.add(uuid); nowHidden = true; }
        persist();
        return nowHidden;
    }

    private static void persist() {
        Path f = file;
        if (f == null) return;
        JsonArray a = new JsonArray();
        HIDDEN.forEach(u -> a.add(u.toString()));
        String json = a.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Display] hidden_ops save failed: {}", e.getMessage()); }
        });
    }
}
```

- [ ] **Step 2: Call `initialize` where `ClanTags.initialize` is called**

Find the `ClanTags.initialize(` call in `CoffeesAeroAuth.java` and add directly beneath it:

```java
        com.coffeesaerosmp.auth.display.HiddenOps.initialize(dataDir);
```

using the same `dataDir` variable that `ClanTags.initialize` receives.

- [ ] **Step 3: Filter hidden ops out of the non-op TAB packet**

In `sendTabNames`, wrap the `plain.add(...)` call so hidden ops are omitted from the packet non-ops receive, while still being added to `opView`:

```java
            if (!com.coffeesaerosmp.auth.display.HiddenOps.isHidden(p.getUUID())) {
                plain.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                    p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), plainName, null));
            }
            opView.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), opName, null));
```

and change the early return from `if (plain.isEmpty()) return;` to `if (opView.isEmpty()) return;` — otherwise a lobby containing only hidden ops would skip the send entirely and ops would see a stale list.

- [ ] **Step 4: Build and boot test**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`, then boot the test server to `Done (…)`.

- [ ] **Step 5: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/display/HiddenOps.java src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/CoffeesAeroAuth.java src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/tablist/TabListManager.java
git commit -m "feat: per-op hide toggle, persisted, filtered from the non-op TAB packet"
```

---

### Task 8: Join message colour, and suppressing it for hidden ops

**Files:**
- Create: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/mixin/JoinMessageMixin.java`
- Modify: `src/CoffeesAeroAuth/src/main/resources/coffeesaeroauth.mixins.json` — register `JoinMessageMixin`

🔴 **DO NOT MODIFY `PlayerDisplayNameMixin`.** An earlier draft of this plan routed
`Player.getDisplayName()` through `PlayerDisplay`. That is wrong — `getDisplayName()` is called from
three other places and prepending badges breaks all of them:

| Call site | Breakage |
|---|---|
| `vote/VoteRewards.java:156` | `!displayName.equalsIgnoreCase(p.getDisplayName().getString())` — this excludes the voter from the light sound. With badges prepended it can never match, so the voter gets the wrong sound. |
| `pvp/CombatGuard.java:119` | splices the name after `§c` mid-sentence; the badge's `§6` overrides the sentence colour. |
| `daily/DailyRewardManager.java:137` | same splice pattern. |

Changing it would also discard vanilla's `decorateDisplayNameComponent` — the click-to-`/tell`,
hover tooltip and shift-click insertion that every name in chat carries.

**So the join line is rebuilt in one place only: the mixin below.** That keeps the blast radius to
exactly the message we want to change.

- [ ] **Step 1: Write the join mixin**

Vanilla broadcasts the join line inside `PlayerList#placeNewPlayer`. There is no cancellable event
for it, so redirect the broadcast call.

Create `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/mixin/JoinMessageMixin.java`:

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
 * Owns the vanilla "X joined the game" line — both hiding it for ops who have hidden themselves,
 * and rebuilding it so the player's name carries their badge, staff tag, clan tag and colour
 * instead of rendering plain.
 *
 * <p>There is no cancellable event for it — {@code PlayerList#placeNewPlayer} calls
 * {@code broadcastSystemMessage} directly — so the call is redirected. Doing it HERE rather than in
 * {@code Player.getDisplayName()} is deliberate: three other call sites read that method and
 * prepending badges to it breaks vote-reward sound selection and two chat sentences.</p>
 *
 * <p>{@code require = 0}: if the target ever moves, joins are announced the vanilla way rather than
 * the server failing to boot. Failure is therefore SILENT — verify it applied, do not assume.</p>
 */
@Mixin(PlayerList.class)
public abstract class JoinMessageMixin {

    @Redirect(
        method = "placeNewPlayer",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
        require = 0)
    private void aeroauth$joinLine(PlayerList list, Component message, boolean overlay,
                                   net.minecraft.network.Connection connection,
                                   ServerPlayer player,
                                   net.minecraft.server.network.CommonListenerCookie cookie) {
        if (HiddenOps.isHidden(player.getUUID())) return;   // swallow the announcement entirely

        try {
            var seg = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(player),
                com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.JOIN,
                false);   // no viewer here — the line is broadcast, so no per-viewer reveal
            // Keep the vanilla translatable so the sentence stays localised; only the NAME argument
            // changes. Rebuilding the whole string would hardcode English.
            list.broadcastSystemMessage(
                Component.translatable("multiplayer.player.joined",
                    Component.literal(seg.prefix() + seg.name())), overlay);
            return;
        } catch (Exception e) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                "[Display] join line fell back to vanilla for {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        }
        list.broadcastSystemMessage(message, overlay);   // fallback: never lose the announcement
    }
}
```

- [ ] **Step 2: Register the mixin**

Add `"JoinMessageMixin"` to the `server` array in
`src/CoffeesAeroAuth/src/main/resources/coffeesaeroauth.mixins.json`, alongside the existing
entries.

- [ ] **Step 3: Build**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Boot test and check the mixin applied**

Deploy to the test server and boot. Because `require = 0` makes failure **silent**
([[mixin-object-param-fails-silently]] — this exact trap cost two rebuilds before), you must
confirm it applied rather than assume:

Search the log for `JoinMessageMixin`. A line reading
`Injection error` or `Redirect ... could not find target` means the descriptor is wrong — most
likely the trailing `placeNewPlayer` parameters changed. Fix the signature before continuing; a
silently-inert redirect looks identical to a working one until an op tries to hide.

- [ ] **Step 5: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/mixin/ src/CoffeesAeroAuth/src/main/resources/coffeesaeroauth.mixins.json
git commit -m "feat: join message uses PlayerDisplay; hidden ops join silently"
```

---

### Task 9: Fix the lobby copy

**Files:**
- Modify: `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/tablist/TabListManager.java:37-43` and `:176`

The lobby is **public and shared**. Two strings currently tell players it is a private hangar.

- [ ] **Step 1: Replace the lobby tips**

Replace the `TIPS_LOBBY` array with:

```java
    /** Lobby tips — the player is in the SHARED lobby, so every line points at the way out. */
    private static final String[] TIPS_LOBBY = {
        "Type §a/spawn§7 to enter the world",
        "Right-click the greeter to fly out",
        "Your inventory is safe — it comes back on §a/spawn",
        "§e/skin§7 sets how you look before you fly",
    };
```

- [ ] **Step 2: Replace the lobby tagline**

Change:

```java
            tagline = "\n§b⌂ §7§oyour private hangar §b⌂";
```

to:

```java
            tagline = "\n§b⌂ §7§othe hangar §b⌂";
```

- [ ] **Step 3: Build**

```
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/tablist/TabListManager.java
git commit -m "fix: lobby copy no longer claims a private, grief-proof hangar"
```

---

### Task 10: Full verification

- [ ] **Step 1: Run the whole test suite**

```
.\gradlew.bat test
```

Expected: **15 tests PASS** (10 PlayerDisplay + 5 StaffBadges), 0 failures.

- [ ] **Step 2: Clean build**

```
.\gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`, jar in `build/libs/`.

- [ ] **Step 3: Boot test**

Deploy to the local 66-mod test server. Expected: `Done (…)`, no exception from `com.coffeesaerosmp.auth`, no `[Display]` warnings.

- [ ] **Step 4: In-game checks — REQUIRES TWO CLIENTS, owner-driven**

These cannot be automated and are the real acceptance test:

| Check | Expected |
|---|---|
| Clan tag above the head | `✈ [ADMIN] [AERO] Name` |
| Clan tag in TAB | same, and it must NOT flicker or disappear |
| Op viewer | sees `Name §8(RealName)` for masked players |
| Non-op viewer | sees no real name |
| `/authmod hide` as op | join line suppressed, gone from non-op TAB, still visible to ops |
| Join message | player's colour and clan tag, not default yellow |

⚠️ **If the nameplate still shows no clan tag**, the FTB Teams hypothesis from the spec is confirmed: FTB is re-assigning party members to its own scoreboard team, evicting them from `ap_<uuid>`. That is a follow-up task, not a defect in this plan — every other item still works.

---

## Out of scope for this plan

- `/lobby` round trip, its gates, and the stash reason marker (spec E4)
- `/back` cooldown 600 → 60 (spec E6)
- RGB clan tags for staff (spec E3) — `StaffBadges.isStaff()` is the hook; wiring it into `ClanTags.setColor` is a follow-up once the renderer is proven in game
- Full entity vanish
