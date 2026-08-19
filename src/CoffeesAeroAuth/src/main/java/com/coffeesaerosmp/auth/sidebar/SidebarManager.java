package com.coffeesaerosmp.auth.sidebar;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.SeasonMigration;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
 * Per-player scoreboard sidebar — the right-hand status panel (name, level, playtime, clan, online,
 * season).
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

    /** Lines currently displayed to each player, so only diffs are sent. */
    private static final Map<UUID, List<String>> shown = new ConcurrentHashMap<>();
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
        List<String> lines = build(player, server);
        UUID id = player.getUUID();

        if (active.add(id)) {
            player.connection.send(new ClientboundSetObjectivePacket(objective(server),
                ClientboundSetObjectivePacket.METHOD_ADD));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(
                DisplaySlot.SIDEBAR, objective(server)));
        }

        List<String> old = shown.get(id);
        for (int i = 0; i < lines.size(); i++) {
            if (old != null && i < old.size() && old.get(i).equals(lines.get(i))) continue;
            // Higher score = higher on the panel, so row 0 must sort highest.
            player.connection.send(new ClientboundSetScorePacket(
                OWNER_PREFIX + i, OBJECTIVE_NAME, lines.size() - i,
                Optional.of(Component.literal(lines.get(i))),
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
            Component.literal("§6§l⚙ §e§lAERO SMP §6§l⚙"),
            ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private static List<String> build(ServerPlayer player, MinecraftServer server) {
        List<String> l = new ArrayList<>(10);
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) : null;

        String name = profile != null && profile.displayName != null
            ? profile.displayName : player.getGameProfile().getName();
        long seconds  = playtimeSeconds(profile);
        int  adv      = advancements(player, server);
        int  level    = levelFor(seconds, adv);

        l.add("§8§m                    ");
        l.add(" §6✈ §f" + name);
        l.add(" §e§lLv " + level + " §r" + progressBar(seconds, adv, level));
        l.add("§8§m                    ");
        l.add(" §7⏱ Playtime  §f" + formatPlaytime(seconds));
        l.add(" §7⚑ Clan      §f" + clanOf(player));
        l.add(" §7☁ Online    §f" + server.getPlayerList().getPlayerCount());
        l.add(" §7✦ Season    §f" + SeasonMigration.CURRENT_SEASON);
        return l;
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
            if (!net.neoforged.fml.ModList.get().isLoaded("ftbteams")) return "§8—";
            var api = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api();
            if (!api.isManagerLoaded()) return "§8—";
            return api.getManager().getTeamForPlayer(player)
                .filter(dev.ftb.mods.ftbteams.api.Team::isPartyTeam)
                .map(t -> t.getName().getString())
                .orElse("§8—");
        } catch (Throwable t) {
            return "§8—";
        }
    }

    // ── Level ─────────────────────────────────────────────────────────────────
    //
    // Points = hours × 10 + advancements × 5, and the level curve is a square root so it slows down
    // instead of running away. Advancements are in deliberately: a level derived from playtime alone
    // is just playtime shown twice, and rewards idling over doing anything.
    //
    // Level n starts at (3(n-1))² points:  Lv2=9  Lv5=144  Lv10=729  Lv20=3249.
    // A player with 47h and 50 advancements = 720 pts = Lv9, 94% of the way to Lv10.

    private static int points(long seconds, int advancements) {
        return (int) Math.min(Integer.MAX_VALUE / 2L, (seconds / 3600L) * 10L + advancements * 5L);
    }

    private static int levelFor(long seconds, int advancements) {
        return 1 + (int) Math.floor(Math.sqrt(points(seconds, advancements)) / 3.0);
    }

    /** Points needed to reach {@code level}. Inverse of {@link #levelFor}. */
    private static int pointsForLevel(int level) {
        int r = 3 * (level - 1);
        return r * r;
    }

    private static String progressBar(long seconds, int advancements, int level) {
        int have = points(seconds, advancements);
        int base = pointsForLevel(level);
        int next = pointsForLevel(level + 1);
        int filled = next <= base ? 8
            : (int) Math.round(8.0 * Math.max(0, have - base) / (next - base));
        filled = Math.max(0, Math.min(8, filled));
        return "§8[§b" + "▰".repeat(filled) + "§8" + "▱".repeat(8 - filled) + "§8]";
    }

    // ── Advancements ──────────────────────────────────────────────────────────

    /**
     * Completed advancements, counted ONCE per session then kept current by
     * {@link #onAdvancementEarned}. The full scan walks every advancement in a ~250-mod pack, so it
     * must never run per tick — and it is deliberately done here, on the first sidebar update, and
     * not on the join path, which already blocks the main thread on the Helsinki↔Singapore RTT.
     */
    private static int advancements(ServerPlayer player, MinecraftServer server) {
        Integer cached = advCount.get(player.getUUID());
        if (cached != null) return cached;
        int n = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) n++;
        }
        advCount.put(player.getUUID(), n);
        return n;
    }

    /** Hook for {@code AdvancementEvent.AdvancementEarnEvent} — keeps the cache warm without rescanning. */
    public static void onAdvancementEarned(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
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
