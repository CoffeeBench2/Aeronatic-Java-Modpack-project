package com.coffeesaerosmp.auth.leveling;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.mail.MailService;
import com.coffeesaerosmp.auth.mail.MailStore;
import com.coffeesaerosmp.auth.sidebar.SidebarManager;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Season 3 levels in play: what level a player is, what they may claim, and paying each new level.
 *
 * <h2>When it runs</h2>
 * Once a minute per online player (playtime moves slowly), plus the tick after any advancement — the
 * advancement count is SidebarManager's cached count, so a check costs a map lookup, not a 10,000-entry
 * scan.
 *
 * <h2>Rewards are paid once, forward only</h2>
 * {@code level_progress} holds the highest level already rewarded this season (monotonic, GREATEST).
 * The FIRST time a player is seen on this system their current level is recorded WITHOUT back-pay — or
 * staff granted every advancement, or a returning veteran, would open fifty reward mails at once. Each
 * reward mail also carries a per-level dedupe key, so even a lost progress write cannot pay twice.
 * Ops (permission 4) are never paid: they hold every advancement by design.
 */
public final class LevelService {

    /** Per-session state. {@code rewarded}: UNKNOWN until the DB answers. */
    private static final class State {
        volatile int rewarded = UNKNOWN;
        int lastLevel = -1;
        int lastExtraApplied = Integer.MIN_VALUE;
    }

    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
    private static int ticks;

    private LevelService() {}

    public static boolean enabled() {
        try {
            return AuthConfig.LEVELING_ENABLED.get() && !LobbyHandoff.isLobbyRole();
        } catch (Exception e) {
            return false;
        }
    }

    public static LevelFormula.Params params() {
        return new LevelFormula.Params(AuthConfig.LEVEL_XP_PER_ADVANCEMENT.get(), AuthConfig.LEVEL_XP_PER_HOUR.get(),
            AuthConfig.LEVEL_CURVE.get(), AuthConfig.CLAIMS_BASE.get(), AuthConfig.CLAIMS_PER_LEVEL.get(),
            AuthConfig.CLAIMS_MAX.get());
    }

    /** XP right now — the sidebar reads this so it can never disagree with claims or rewards. */
    public static long xpOf(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE == null ? null
            : CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
        long season = profile == null ? 0
            : Math.max(0L, SidebarManager.playtimeSecondsOf(profile) - profile.seasonStartPlaytime);
        return LevelFormula.xp(SidebarManager.realAdvancementCount(player, server), season, params());
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    public static void onJoin(ServerPlayer player) {
        if (!enabled()) return;
        State st = new State();
        STATE.put(player.getUUID(), st);
        if (AuthConfig.OPS_GET_ALL_ADVANCEMENTS.get() && player.hasPermissions(4)) {
            GRANTS.put(player.getUUID(), new GrantJob(player));
        }
        MailStore.levelRewarded(player.getServer(), player.getUUID(), MailService.season(), lv -> {
            if (STATE.get(player.getUUID()) != st) return;            // relogged meanwhile
            st.rewarded = lv;                                         // -1 = first time; MIN = DB unknown
            check(player);
        });
    }

    public static void onLeave(ServerPlayer player) {
        STATE.remove(player.getUUID());
        GRANTS.remove(player.getUUID());
    }

    public static void onServerTick(MinecraftServer server) {
        if (!GRANTS.isEmpty()) runGrants();
        if (++ticks % 1200 != 0 || !enabled()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) safeCheck(p);
    }

    public static void onAdvancement(ServerPlayer player) {
        if (!enabled()) return;
        // Next tick: SidebarManager's own listener bumps the cached count in the same event. Coalesced —
        // an op's advancement grant fires hundreds of these per tick, and they need ONE check.
        if (!PENDING.add(player.getUUID())) return;
        player.getServer().execute(() -> {
            PENDING.remove(player.getUUID());
            safeCheck(player);
        });
    }

    private static void safeCheck(ServerPlayer p) {
        try {
            if (!p.hasDisconnected()) check(p);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[Level] check failed for {}", p.getGameProfile().getName(), t);
        }
    }

    // ── the check ────────────────────────────────────────────────────────────

    static void check(ServerPlayer player) {
        State st = STATE.get(player.getUUID());
        if (st == null) return;
        LevelFormula.Params p = params();
        int level = LevelFormula.level(xpOf(player), p);

        if (AuthConfig.LEVEL_CLAIMS_ENABLED.get()) {
            int extra = LevelFormula.extraClaims(level, p);
            if (extra != st.lastExtraApplied && FtbClaims.setExtra(player, extra) >= 0) st.lastExtraApplied = extra;
        }

        int previous = st.lastLevel;
        st.lastLevel = level;
        if (st.rewarded == UNKNOWN) return;                           // DB not answered (or down): no rewards
        if (st.rewarded < 0) {
            // First check this season. This used to record the CURRENT level as already rewarded ("no
            // back-pay"), meant for switching leveling on mid-season. On a fresh season world every
            // level above 1 was earned in this world, so it silently ate real rewards: aerosmp_random's
            // first check found level 2 and wrote it off with no mail (2026-10-07). Count from level 1.
            st.rewarded = 1;
        }
        if (level <= st.rewarded) return;

        boolean pay = !player.hasPermissions(4);
        for (int lv = st.rewarded + 1; lv <= level; lv++) {
            if (pay) MailService.sendLevelReward(player, lv, LevelFormula.claims(lv, p));
        }
        int from = st.rewarded;
        st.rewarded = level;
        MailStore.setLevelRewarded(player.getUUID(), MailService.season(), level);
        if (previous >= 0) announce(player, from, level, p);
        CoffeesAeroAuth.LOGGER.info("[Level] {} Lv {} -> {}{}", player.getGameProfile().getName(), from, level,
            pay ? "" : " (op: no reward mail)");
    }

    private static void announce(ServerPlayer player, int from, int to, LevelFormula.Params p) {
        int claims = LevelFormula.claims(to, p), gained = claims - LevelFormula.claims(from, p);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lLEVEL UP")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§fLv " + to
            + (gained > 0 ? "  §a+" + gained + " claim chunks" : ""))));
        player.sendSystemMessage(Component.literal("§6★ §fYou reached §eLv " + to + "§f! Claims: §a" + claims
            + "§7/" + p.claimsMax() + (MailService.enabled() && !player.hasPermissions(4)
                ? " §7— your reward is in §f/mail" : "")));
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    // ── ops: every advancement, spread over ticks ─────────────────────────────
    //
    // 2026-10-04 boot log: granting LegendaryTales everything in ONE tick (16,352 criteria) froze the
    // server for 3.1 s ("Running 3132ms or 62 ticks behind"). Each op would do that once. Now it is a job
    // that awards at most CRITERIA_PER_TICK per tick — the same total work, never more than a few ms at a time.

    private static final int CRITERIA_PER_TICK = 400;
    private static final Map<UUID, GrantJob> GRANTS = new ConcurrentHashMap<>();

    private static final class GrantJob {
        final ServerPlayer player;
        final java.util.Iterator<AdvancementHolder> it;
        int awarded;

        GrantJob(ServerPlayer player) {
            this.player = player;
            this.it = new java.util.ArrayList<>(player.getServer().getAdvancements().getAllAdvancements()).iterator();
        }
    }

    private static void runGrants() {
        var iter = GRANTS.values().iterator();
        while (iter.hasNext()) {
            GrantJob job = iter.next();
            ServerPlayer player = job.player;
            if (player.hasDisconnected()) { iter.remove(); continue; }
            int budget = CRITERIA_PER_TICK;
            while (budget > 0 && job.it.hasNext()) {
                AdvancementHolder h = job.it.next();
                AdvancementProgress prog = player.getAdvancements().getOrStartProgress(h);
                if (prog.isDone()) continue;
                java.util.List<String> remaining = new java.util.ArrayList<>();
                prog.getRemainingCriteria().forEach(remaining::add);   // copy: award() mutates the progress
                for (String c : remaining) {
                    if (player.getAdvancements().award(h, c)) job.awarded++;
                    budget--;
                }
            }
            if (!job.it.hasNext()) {
                iter.remove();
                if (job.awarded > 0) {
                    SidebarManager.invalidateAdvancements(player.getUUID());
                    CoffeesAeroAuth.LOGGER.info("[Level] op {}: granted {} advancement criteria (opsGetAllAdvancements).",
                        player.getGameProfile().getName(), job.awarded);
                }
            }
        }
    }
}
