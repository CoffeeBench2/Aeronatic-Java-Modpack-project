package com.coffeesaerosmp.auth.sidebar;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.afk.AfkTracker;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.SeasonMigration;
import com.coffeesaerosmp.auth.util.TestingMode;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player scoreboard sidebar — the right-hand status panel (name, level, playtime, deaths,
 * advancements, clan, online, season).
 *
 * <p><b>Why packets instead of the real scoreboard.</b> A vanilla {@link Objective} lives on the
 * level-wide {@link net.minecraft.world.scores.Scoreboard} and shows the SAME lines to everyone, so
 * it cannot carry per-player values. Every packet here is built by hand and sent to one connection;
 * the server scoreboard is never touched, nothing is persisted to the world, and no other player can
 * see another's panel. The {@link Objective} instances below are constructed purely as packet
 * payloads — constructing one does not register it.
 *
 * <p><b>Why line text lives in the score's display component.</b> Pre-1.20.3 the only way to draw
 * text was to make the score's <i>owner</i> string be the text, which forced every line to be
 * unique (two separator rows would collide). Since 1.20.3 {@link ClientboundSetScorePacket} carries
 * an optional display {@link Component}, so owners are stable keys ({@code aero_l0}…) and the text
 * is free to repeat. {@link BlankFormat} hides the red score number.
 *
 * <p><b>Cost.</b> Rebuilt once a second, and only CHANGED lines are sent. A player standing still
 * sends zero packets after the first tick. Nothing here touches MySQL — playtime comes from the
 * in-memory profile, the advancement count is cached and incremented by event, and the FTB team
 * lookup is a local map. See {@code lag-is-blocking-not-compute}: the tick loop is compute-bound,
 * so this deliberately does no per-tick work.
 */
public final class SidebarManager {

    private SidebarManager() {}

    private static final String OBJECTIVE_NAME = "aero_sidebar";
    /** Stable per-row score owners. Never shown to the player — the display component is. */
    private static final String OWNER_PREFIX = "aero_l";

    // ── Palette ───────────────────────────────────────────────────────────────
    //
    // Coffee house style, in true RGB rather than the legacy §-codes: the legacy palette has no
    // brown at all, so a "coffee theme" written with § can only ever be the same gold every other
    // server uses. The sidebar background is translucent black, so these are the LIGHT end of the
    // coffee range (foam/latte/mocha) — espresso is dark enough that it is used only for the
    // separator rules and the unfilled half of the progress bar.
    //
    // Deliberately NOT bold. Bold in Minecraft's font is a smeared double-draw, not a real weight,
    // and on eight consecutive rows it reads as shouting. Hierarchy here comes from colour instead.

    /** Milk foam — the brightest tone, for values the player actually reads. */
    private static final int FOAM     = 0xE8D5B7;
    /** Latte — the accent: server name, level, filled progress. */
    private static final int LATTE    = 0xD4A574;
    /** Crema — the warmer accent used for glyphs. */
    private static final int CREMA    = 0xC68B45;
    /** Mocha — muted, for row labels that should recede behind their values. */
    private static final int MOCHA    = 0xA67B5B;
    /** Espresso — structural only: separators, empty progress cells, the "no clan" dash. */
    private static final int ESPRESSO = 0x6F4E37;

    private static MutableComponent tint(String text, int rgb) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    /** Lines currently displayed to each player, so only diffs are sent. */
    private static final Map<UUID, List<Component>> shown = new ConcurrentHashMap<>();
    /** Players who currently have the objective registered client-side. */
    private static final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Completed-advancement count per player: computed once, then incremented by event. */
    private static final Map<UUID, Integer> advCount = new ConcurrentHashMap<>();

    private static int ticks = 0;

    // ── Tick ──────────────────────────────────────────────────────────────────

    /** Call every server tick; throttles internally to once a second. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 20 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                update(p, server);
            } catch (Throwable t) {
                // A sidebar must never be able to take the tick loop down.
                CoffeesAeroAuth.LOGGER.warn("[Sidebar] update failed for {}",
                    p.getGameProfile().getName(), t);
            }
        }
    }

    private static void update(ServerPlayer player, MinecraftServer server) {
        if (!AuthConfig.SIDEBAR_ENABLED.get() || isHidden(player) || inLobby(player)) {
            teardown(player);
            return;
        }
        List<Component> lines = build(player, server);
        UUID id = player.getUUID();

        if (active.add(id)) {
            player.connection.send(new ClientboundSetObjectivePacket(objective(server),
                ClientboundSetObjectivePacket.METHOD_ADD));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(
                DisplaySlot.SIDEBAR, objective(server)));
        }

        List<Component> old = shown.get(id);
        for (int i = 0; i < lines.size(); i++) {
            // Component defines a real structural equals (contents + style + siblings), so this
            // still sends only the rows whose rendered text or colour actually moved.
            if (old != null && i < old.size() && old.get(i).equals(lines.get(i))) continue;
            // Higher score = higher on the panel, so row 0 must sort highest.
            player.connection.send(new ClientboundSetScorePacket(
                OWNER_PREFIX + i, OBJECTIVE_NAME, lines.size() - i,
                Optional.of(lines.get(i)),
                Optional.of(BlankFormat.INSTANCE)));
        }
        // The panel shrank (e.g. the player left their team) — clear the orphaned rows.
        if (old != null) {
            for (int i = lines.size(); i < old.size(); i++) {
                player.connection.send(new ClientboundResetScorePacket(OWNER_PREFIX + i, OBJECTIVE_NAME));
            }
        }
        shown.put(id, lines);
    }

    /** Removes the panel from a player's screen and forgets their cached rows. */
    private static void teardown(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!active.remove(id)) return;
        shown.remove(id);
        // METHOD_REMOVE only needs the name to match; the rest of the payload is ignored.
        player.connection.send(new ClientboundSetObjectivePacket(
            objective(player.server), ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    private static Objective objective(MinecraftServer server) {
        return new Objective(server.getScoreboard(), OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
            title(), ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
    }

    /**
     * The panel header. The apostrophe in "Coffee's" is intentional — it is the brand everywhere
     * else (store pages, Discord, the tab list), and the old {@code AERO SMP} was the only place it
     * was shouted in caps.
     */
    private static Component title() {
        return tint("☕ ", CREMA)
            .append(tint("Coffee's ", FOAM))
            .append(tint("AeroSMP", LATTE));
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private static List<Component> build(ServerPlayer player, MinecraftServer server) {
        List<Component> l = new ArrayList<>(11);
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) : null;

        String name = profile != null && profile.displayName != null
            ? profile.displayName : player.getGameProfile().getName();
        long seconds = playtimeSeconds(profile);
        int  level   = levelFor(seconds);

        l.add(rule());
        l.add(tint(" ✈ ", CREMA).append(tint(name, FOAM)));
        l.add(tint(" Lv " + level + " ", LATTE).append(progressBar(seconds, level)));
        l.add(rule());
        // While AFK the clock is genuinely paused, so say so on the row that stopped moving —
        // a playtime figure that silently freezes reads as a bug, not as a rule.
        boolean afk = AfkTracker.isAfk(player);
        l.add(afk ? row("⏸", "Playtime", formatPlaytime(seconds) + " · afk")
                  : row("⏱", "Playtime", formatPlaytime(seconds)));
        l.add(row("☠", "Deaths",   String.valueOf(deaths(player))));
        l.add(row("★", "Advances", String.valueOf(advancements(player, server))));
        l.add(row("⚑", "Clan",     clanOf(player)));
        l.add(row("☁", "Online",   String.valueOf(server.getPlayerList().getPlayerCount())));
        l.add(row("✦", "Season",   String.valueOf(SeasonMigration.CURRENT_SEASON)));

        // Testing phase: a footnote, and ONLY when it is on — a normal session adds no row at all.
        // Deliberately understated (lowercase, muted, no separator above it): the tab-list header
        // and the join banner already announce this loudly, and a panel that shouts every second
        // stops being read. It is last so the rows above never shift position when it appears.
        // No reason text here — `/authmod testing status` and the join banner carry that.
        if (TestingMode.isActive()) l.add(tint(" ⚠ testing phase", MOCHA));
        return l;
    }

    /**
     * A label/value row. The label is padded to a fixed width, which only APPROXIMATELY aligns the
     * values — Minecraft's font is proportional, not monospaced — but it is close enough to read as
     * a column and it is what the panel already did.
     */
    private static Component row(String glyph, String label, String value) {
        return tint(" " + glyph + " ", CREMA)
            .append(tint(String.format(Locale.ROOT, "%-9s", label), MOCHA))
            .append(tint(value, FOAM));
    }

    /** A horizontal rule. Strikethrough spaces, so its length sets the panel's minimum width. */
    private static Component rule() {
        return Component.literal("                  ").withStyle(
            Style.EMPTY.withColor(TextColor.fromRgb(ESPRESSO)).withStrikethrough(true));
    }

    /**
     * Deaths, straight from vanilla's own statistics — no new profile column and no schema
     * migration on a database the creative TEST server SHARES with live.
     *
     * <p>A useful side effect: vanilla stats live in {@code <world>/stats/<uuid>.json}, so the
     * counter is scoped to the CURRENT world. Season 2 started on fresh terrain, which means this
     * reads as "deaths this season" rather than an all-time number carried over from Season 1.
     */
    private static int deaths(ServerPlayer player) {
        try {
            return player.getStats().getValue(Stats.CUSTOM, Stats.DEATHS);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Banked playtime plus the live session. {@code totalPlaytimeSeconds} is only written when the
     * session is banked (on leave/save), so without the live part the panel would sit frozen at the
     * join value for the whole session.
     */
    private static long playtimeSeconds(PlayerProfile profile) {
        if (profile == null) return 0L;
        long total = profile.totalPlaytimeSeconds;
        if (profile.sessionStartEpoch > 0) {
            total += Math.max(0L, (System.currentTimeMillis() - profile.sessionStartEpoch) / 1000L);
        }
        return total;
    }

    private static String formatPlaytime(long seconds) {
        long hours = seconds / 3600L;
        if (hours < 1) return (seconds / 60L) + "m";
        if (hours < 1000) return hours + "h";
        return String.format(Locale.ROOT, "%,dh", hours);
    }

    /**
     * The player's clan, or {@code —}.
     *
     * <p>⚠ FTB Teams gives EVERY player a personal team, so
     * {@code getTeamForPlayer} always returns something — using it directly would show every player
     * their own username as their "clan". Only a <b>party</b> team is a real clan.
     */
    private static String clanOf(ServerPlayer player) {
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("ftbteams")) return "—";
            var api = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api();
            if (!api.isManagerLoaded()) return "—";
            return api.getManager().getTeamForPlayer(player)
                .filter(dev.ftb.mods.ftbteams.api.Team::isPartyTeam)
                .map(t -> t.getName().getString())
                .orElse("—");
        } catch (Throwable t) {
            return "—";
        }
    }

    // ── Level ─────────────────────────────────────────────────────────────────
    //
    //   Lv = 1 + floor(2 · √hours)      →  hours to reach Lv n = ((n-1)/2)²
    //
    //   Lv 2  →   15m      Lv 20 →   90h
    //   Lv 3  →    1h      Lv 25 →  144h
    //   Lv 5  →    4h      Lv 30 →  210h
    //   Lv 10 →   20h      Lv 40 →  380h
    //   Lv 15 →   49h      Lv 50 →  600h
    //
    // 🔴 WHY THIS IS PLAYTIME ONLY — the previous formula was hours×10 + advancements×5, and in
    // THIS pack that made the level almost entirely an advancement count. A scan of `server-mods/`
    // found 8,926 advancement definitions, and 7,651 of them (86%) are recipe-unlock advancements,
    // which complete automatically the moment a player so much as picks up an ingredient. So the
    // advancement term was worth up to ~50,000 points while 130 HOURS of play was worth 1,300 —
    // playtime contributed about 2.5% of a player's own level. That is why a 30-hour player and a
    // 130-hour player could sit at the same level: the panel was ranking recipe unlocks and calling
    // it experience.
    //
    // The old comment claimed a playtime-only level "is just playtime shown twice". That trade was
    // taken deliberately. A level that can disagree with the Playtime row directly beneath it is
    // worse than a redundant one, and the square root already does the real work — the first hour
    // moves fast, and a 600-hour veteran is Lv 50 rather than Lv 500. Advancements and deaths are
    // still on the panel; they are just their own honest rows now instead of hidden level inputs.
    //
    // AFK time does NOT count — see AfkTracker, which rolls sessionStartEpoch forward while a
    // player is idle, so idle seconds never reach this formula (or any other playtime readout).
    // ⚠ A physical mouse-jiggler still defeats it. That is accepted; nothing server-side can tell
    // a jiggler from a player, and re-adding advancements would not have helped either.

    private static int levelFor(long seconds) {
        if (seconds <= 0L) return 1;
        return 1 + (int) Math.floor(2.0 * Math.sqrt(seconds / 3600.0));
    }

    /** Seconds of playtime needed to reach {@code level}. Inverse of {@link #levelFor}. */
    private static double secondsForLevel(int level) {
        double h = (level - 1) / 2.0;
        return h * h * 3600.0;
    }

    private static Component progressBar(long seconds, int level) {
        double base = secondsForLevel(level);
        double next = secondsForLevel(level + 1);
        int filled = next <= base ? 8
            : (int) Math.round(8.0 * Math.max(0.0, seconds - base) / (next - base));
        filled = Math.max(0, Math.min(8, filled));
        return tint("▰".repeat(filled), LATTE).append(tint("▱".repeat(8 - filled), ESPRESSO));
    }

    // ── Advancements ──────────────────────────────────────────────────────────

    /**
     * Completed advancements, counted ONCE per session then kept current by
     * {@link #onAdvancementEarned}. The full scan walks every advancement in a ~250-mod pack, so it
     * must never run per tick — and it is deliberately done here, on the first sidebar update, and
     * not on the join path, which already blocks the main thread on the Helsinki↔Singapore RTT.
     *
     * <p><b>🔴 Only REAL advancements are counted.</b> {@code getAllAdvancements()} returns 8,926
     * entries in this pack and 7,651 of them are recipe-unlock advancements that complete on their
     * own — an unfiltered count reads in the thousands, tracks inventory rather than achievement,
     * and is the exact term that used to distort the level. {@link #isRealAdvancement} is the same
     * display-present + {@code shouldAnnounceChat} test {@code WatchdogEvents.onAdvancement} already
     * uses to keep those auto-grants out of Discord, so the panel and the feed now agree.
     */
    private static int advancements(ServerPlayer player, MinecraftServer server) {
        Integer cached = advCount.get(player.getUUID());
        if (cached != null) return cached;
        int n = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (!isRealAdvancement(holder)) continue;
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) n++;
        }
        advCount.put(player.getUUID(), n);
        return n;
    }

    /** True for advancements a player actually earns; false for recipe unlocks and hidden steps. */
    private static boolean isRealAdvancement(AdvancementHolder holder) {
        var display = holder.value().display();
        return display.isPresent() && display.get().shouldAnnounceChat();
    }

    /** Hook for {@code AdvancementEvent.AdvancementEarnEvent} — keeps the cache warm without rescanning. */
    public static void onAdvancementEarned(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Must apply the SAME filter as the scan, or the cached count drifts upward all session:
        // a fresh join re-grants batches of recipe advancements and every one fires this event.
        if (!isRealAdvancement(event.getAdvancement())) return;
        advCount.computeIfPresent(player.getUUID(), (k, v) -> v + 1);
    }

    // ── Toggle + lifecycle ────────────────────────────────────────────────────

    /**
     * The per-player hide flag, stored in the player's persisted NBT rather than MySQL.
     *
     * <p>Deliberately NOT a profile column: a HUD preference does not justify a schema migration on
     * a database the creative TEST server SHARES with live, and it keeps the toggle off the blocking
     * DB path entirely. {@link net.minecraft.world.entity.player.Player#PERSISTED_NBT_TAG} survives
     * relog and death.
     */
    private static boolean isHidden(ServerPlayer player) {
        var root = player.getPersistentData();
        return root.contains(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
            && root.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                   .getBoolean("aeroSidebarHidden");
    }

    /** Flips the player's sidebar preference. Returns true if the panel is now VISIBLE. */
    public static boolean toggle(ServerPlayer player) {
        var root = player.getPersistentData();
        String key = net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG;
        var persisted = root.getCompound(key);
        boolean nowHidden = !persisted.getBoolean("aeroSidebarHidden");
        persisted.putBoolean("aeroSidebarHidden", nowHidden);
        root.put(key, persisted);
        if (nowHidden) teardown(player);
        return !nowHidden;
    }

    /** Drop all per-player state on logout so nothing leaks across sessions. */
    public static void onPlayerLogout(ServerPlayer player) {
        UUID id = player.getUUID();
        active.remove(id);
        shown.remove(id);
        advCount.remove(id);
    }

    private static boolean inLobby(ServerPlayer player) {
        return player.level().dimension()
            == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
    }
}
