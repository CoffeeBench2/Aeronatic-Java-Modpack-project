package com.coffeesaerosmp.auth.vote;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.Coins;
import com.coffeesaerosmp.auth.util.Sounds;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewards for voting on server-list sites.
 *
 * <h2>Why this exists rather than a plain {@code /give} in the Votifier config</h2>
 * Three reasons, each of which broke the naive setup on this server:
 *
 * <ol>
 *   <li><b>Votifier runs its reward command on its own network thread.</b> Creating an item entity
 *       there threw {@code ThreadLocalRandom accessed from a different thread} and the reward was
 *       silently lost (2026-08-17). Everything here hops onto the server thread via
 *       {@link MinecraftServer#execute} before touching the world.</li>
 *   <li><b>Display names are not usernames.</b> Players vote with the name they SEE, which NameMask
 *       may have changed, so {@code /give <displayName>} fails for exactly the players most likely
 *       to vote. {@link com.coffeesaerosmp.auth.db.ProfileStore#findByAnyName} resolves either.</li>
 *   <li><b>Voters are usually offline.</b> A vote cast at 3am must still pay out, so an unclaimed
 *       vote is banked and delivered on the next join.</li>
 * </ol>
 *
 * <h2>Scaling — a real STREAK (2026-10-04)</h2>
 * The reward grows with the player's current voting streak and is clamped:
 * {@code reward = min(max, base + (streak - 1) / votesPerStep)}.
 * <ul>
 *   <li>A vote at least {@link VoteStreak#NEXT_DAY_HOURS} after the vote that last moved the streak counts as
 *       the next day (streak + 1). Sooner = same day (another site): the streak does not move.</li>
 *   <li>More than {@code 2 × voteCooldownHours} with no vote breaks it: back to day 1.</li>
 *   <li>No lifetime cap. A player who keeps their streak keeps earning the top tier.</li>
 * </ul>
 * Why: the old ladder ran on LIFETIME votes with a 30-vote cap. Someone who stopped voting for weeks came
 * back on the top tier, while the most loyal voters hit vote 31 and were paid nothing ever again — the
 * admins' report, 2026-10-04. Voting is the ONLY recurring spur source in the game (owner, 2026-10-04).
 */
public final class VoteRewards {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>(){}.getType();

    /** Per-player persisted state. {@code pending} is votes banked while they were offline. */
    private static final class Entry {
        int  totalVotes;
        int  pending;
        long lastVoteMs;
        // Streak (2026-10-04). The reward is fixed when the vote LANDS, so banked votes pay the tier earned.
        int  streak;
        long lastStreakMs;
        int  pendingSpurs;
        int  pendingDiamonds;
    }



    private final Path file;
    private final Map<String, Entry> state = new ConcurrentHashMap<>();

    public VoteRewards(Path dataDir) {
        this.file = dataDir == null ? null : dataDir.resolve("vote_rewards.json");
        load();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Records a vote for {@code rawName}, which may be a username OR a display name.
     *
     * <p><b>Safe to call from any thread</b> — this is the whole point. Votifier calls it from its
     * socket thread; the only work done there is a cache lookup, and everything that touches the
     * world is handed to the server thread.
     *
     * @return false only if the name could not be resolved to a known player at all.
     */
    public boolean recordVote(MinecraftServer server, String rawName, String serviceName) {
        if (server == null || rawName == null || rawName.isBlank()) return false;
        if (!AuthConfig.VOTE_REWARD_ENABLED.get()) return false;
        if (CoffeesAeroAuth.PROFILE_STORE == null) return false;

        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.findByAnyName(rawName.trim());
        if (profile == null || profile.getUUID() == null) {
            CoffeesAeroAuth.LOGGER.warn(
                "[Vote] Vote from {} for unknown player '{}' — no profile matches that username or "
                    + "display name. Reward not granted.", serviceName, rawName);
            return false;
        }

        UUID uuid = profile.getUUID();
        Entry e = state.computeIfAbsent(uuid.toString(), k -> new Entry());
        int voteNumber;
        int streak;
        synchronized (e) {
            long now = System.currentTimeMillis();
            int next = VoteStreak.next(e.streak, e.lastStreakMs, now, AuthConfig.VOTE_COOLDOWN_HOURS.get());
            if (next != e.streak || e.lastStreakMs <= 0) e.lastStreakMs = now;   // only a NEW day moves the clock
            e.streak = next;
            e.totalVotes++;
            e.lastVoteMs = now;
            voteNumber = e.totalVotes;
            streak = e.streak;
            e.pending++;                      // cleared below if they are online to receive it now
            e.pendingSpurs    += spursFor(streak);
            e.pendingDiamonds += diamondsFor(streak);
        }
        save();

        CoffeesAeroAuth.LOGGER.info("[Vote] {} voted on {} (vote #{}, streak {})",
            profile.username, serviceName, voteNumber, streak);

        // Hop to the server thread before looking at players or touching inventories.
        String shown = (profile.displayName != null && !profile.displayName.isBlank())
            ? profile.displayName : profile.username;
        server.execute(() -> {
            announce(server, shown, voteNumber);
            deliver(uuid, server);            // by mail: online or not (owner 2026-10-07)
        });
        return true;
    }

    /** Pays out anything banked while the player was offline. Called on join, on the server thread. */
    public void onPlayerJoin(ServerPlayer player) {
        try {
            if (!AuthConfig.VOTE_REWARD_ENABLED.get()) return;
            Entry e = state.get(player.getUUID().toString());
            if (e == null || e.pending <= 0) return;
            deliver(player.getUUID(), player.getServer());   // banked before mail, or mail was down
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.debug("[Vote] join payout skipped: {}", ex.toString());
        }
    }

    /** Lifetime vote count, for {@code /authmod player} and the reward message. */
    /**
     * The streak as it stands NOW: a streak whose last day is older than 2 × cooldown already counts as
     * broken, so /vote never shows a number the next vote would immediately reset.
     */
    public int currentStreak(UUID uuid) {
        Entry e = state.get(uuid.toString());
        if (e == null || e.streak <= 0) return 0;
        long since = System.currentTimeMillis() - e.lastStreakMs;
        return since > 2L * Math.max(1, AuthConfig.VOTE_COOLDOWN_HOURS.get()) * 3_600_000L ? 0 : e.streak;
    }

    public int voteCount(UUID uuid) {
        Entry e = state.get(uuid.toString());
        return e == null ? 0 : e.totalVotes;
    }

    /**
     * Server-wide "X voted" line. MUST run on the server thread.
     *
     * <p>Fires when the vote LANDS, not when the reward is handed over, so it still celebrates a
     * vote cast while the player is offline — which is most of them, and exactly the case where a
     * visible thank-you does the most work.
     *
     * <p>Uses the DISPLAY name, matching NameMask everywhere else. The sound is deliberately the
     * quiet one: this fires for everybody on the server every time anyone votes, and a celebratory
     * sting at that frequency stops being a celebration and becomes something people mute.
     */
    private void announce(MinecraftServer server, String displayName, int voteNumber) {
        if (!AuthConfig.VOTE_ANNOUNCE_ENABLED.get()) return;
        Component line = Component.literal(TextUtil.PREFIX + "§6✦ §e" + displayName
            + " §6voted for the server! §7Thank you — §f/vote");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
            // The voter gets the full reward sting from deliver(); everyone else gets the light one.
            if (!p.getGameProfile().getName().equalsIgnoreCase(displayName)
                    && !displayName.equalsIgnoreCase(p.getDisplayName().getString())) {
                Sounds.success(p);
            }
        }
    }

    // ── Reminders ─────────────────────────────────────────────────────────────

    /**
     * Milliseconds until this player may vote again; {@code 0} means now.
     *
     * <p>This is an ESTIMATE and cannot be anything else. Votifier only ever tells us a vote
     * happened, never when the site will next allow one, so the countdown is
     * {@code voteCooldownHours} measured from the last vote WE saw. A vote cast on a site we are
     * not listening to, or before this system existed, is invisible to it.
     */
    public long msUntilVotable(UUID uuid) {
        Entry e = state.get(uuid.toString());
        if (e == null || e.lastVoteMs <= 0) return 0L;          // never voted = ready
        long cooldown = AuthConfig.VOTE_COOLDOWN_HOURS.get() * 3_600_000L;
        long since = System.currentTimeMillis() - e.lastVoteMs;
        return since >= cooldown ? 0L : cooldown - since;
    }

    /** The clickable "you can vote" line. */
    private static Component readyLine() {
        return Component.literal(TextUtil.PREFIX + "§e✦ You can §f/vote§e again! §7"
            + AuthConfig.VOTE_URL.get());
    }

    /** Join-time nudge — silent unless they can actually vote right now. */
    public void remindOnJoin(ServerPlayer player) {
        try {
            if (!AuthConfig.VOTE_REWARD_ENABLED.get()) return;
            if (!AuthConfig.VOTE_REMINDER_ENABLED.get()) return;
            if (msUntilVotable(player.getUUID()) > 0) return;
            player.sendSystemMessage(readyLine());
            Sounds.notify(player);
            reminded.add(player.getUUID());
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.debug("[Vote] join reminder skipped: {}", ex.toString());
        }
    }

    /**
     * Fires the reminder the moment a player's cooldown elapses while they are online.
     *
     * <p>Throttled to once every 30s of ticks — this is a courtesy notification, not something that
     * needs tick resolution, and the login path on this server is already expensive enough.
     * {@code reminded} makes it once-per-cooldown rather than once every 30 seconds forever.
     */
    public void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++tickCounter % 600 != 0) return;                   // ~30s at 20 TPS
        if (!AuthConfig.VOTE_REWARD_ENABLED.get()) return;
        if (!AuthConfig.VOTE_REMINDER_ENABLED.get()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            if (msUntilVotable(id) > 0) {
                reminded.remove(id);                            // re-arm for the next cooldown
                continue;
            }
            if (reminded.add(id)) {                             // only true the first time
                p.sendSystemMessage(readyLine());
                Sounds.notify(p);
            }
        }
    }

    /** Players already told about the current window, so the 30s sweep does not repeat itself. */
    private final java.util.Set<UUID> reminded = ConcurrentHashMap.newKeySet();
    private int tickCounter;

    // ── Payout ────────────────────────────────────────────────────────────────

    /**
     * MUST run on the server thread. Mails every banked vote (spurs + diamonds) and clears the counters —
     * online or offline, so nobody waits for a join any more (owner 2026-10-07: system rewards come by mail).
     * If the mail cannot be stored: paid directly when they are online, put back in the bank when not.
     */
    private void deliver(UUID uuid, MinecraftServer server) {
        Entry e = state.get(uuid.toString());
        if (e == null) return;
        final int owed, spurs, diamonds, streak, lifetime;
        synchronized (e) {
            if (e.pending <= 0) return;
            int s = e.pendingSpurs, d = e.pendingDiamonds;
            if (s == 0 && d == 0) {
                // Banked before the streak system existed (old file): pay those at the day-1 tier.
                s = e.pending * spursFor(1);
                d = e.pending * diamondsFor(1);
            }
            owed = e.pending; spurs = s; diamonds = d;
            streak = Math.max(1, e.streak);
            lifetime = e.totalVotes;
            e.pending = 0;
            e.pendingSpurs = 0;
            e.pendingDiamonds = 0;
        }
        save();
        List<ItemStack> items = new ArrayList<>();
        for (int left = diamonds; left > 0; left -= 64) items.add(new ItemStack(Items.DIAMOND, Math.min(64, left)));
        String votes = owed == 1 ? "vote" : owed + " votes";
        com.coffeesaerosmp.auth.mail.MailService.sendSystemReward(server, uuid, "Thanks for voting!",
            "Thank you for voting (" + votes + "). Streak: " + streak + (streak == 1 ? " day" : " days")
                + ". Vote again within " + (2 * AuthConfig.VOTE_COOLDOWN_HOURS.get()) + "h to keep it.",
            items, spurs, "vote:" + uuid + ":" + lifetime,
            stored -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (stored) {
                    if (player != null) voteMessages(player, votes, spurs, diamonds, streak, " §7are in your §f/mail§7.");
                    CoffeesAeroAuth.LOGGER.info("[Vote] Mailed {} for {} vote(s): {} spurs, {} diamonds (streak {}, lifetime {})",
                        uuid, owed, spurs, diamonds, streak, lifetime);
                } else if (player != null) {
                    payDirect(player, owed, spurs, diamonds, streak, lifetime);
                } else {
                    synchronized (e) {                       // keep it for their next join
                        e.pending += owed;
                        e.pendingSpurs += spurs;
                        e.pendingDiamonds += diamonds;
                    }
                    save();
                    CoffeesAeroAuth.LOGGER.warn("[Vote] Mail unavailable — {} vote(s) for {} banked for their next join.", owed, uuid);
                }
            });
    }

    private void voteMessages(ServerPlayer player, String votes, int spurs, int diamonds, int streak, String where) {
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§a✦ Thanks for voting! §7(" + votes + ") §f→ §6" + spurs + " spurs §fand §b"
            + diamonds + " diamonds" + where));
        boolean topTier = spursFor(streak) >= AuthConfig.VOTE_REWARD_SPURS_MAX.get()
                       && diamondsFor(streak) >= AuthConfig.VOTE_REWARD_DIAMONDS_MAX.get();
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Vote streak: §e" + streak
            + (streak == 1 ? " day" : " days") + (topTier ? " §6— top reward!" : " §7— each day pays a little more.")
            + " §7Vote again within §f" + (2 * AuthConfig.VOTE_COOLDOWN_HOURS.get()) + "h§7 to keep it."));
        Sounds.reward(player);
    }

    /** The pre-mail payout, kept as the fallback when mail cannot be stored. */
    private void payDirect(ServerPlayer player, int owed, int spurs, int diamonds, int streak, int lifetime) {
        Coins.pay(player, spurs);
        Coins.giveItem(player, ResourceLocation.parse("minecraft:diamond"), diamonds);
        voteMessages(player, owed == 1 ? "vote" : owed + " votes", spurs, diamonds, streak, "§f.");
        CoffeesAeroAuth.LOGGER.info("[Vote] Paid {} for {} vote(s): {} spurs, {} diamonds (streak {}, lifetime {})",
            player.getGameProfile().getName(), owed, spurs, diamonds, streak, lifetime);
    }


    /** Spurs for streak day N, clamped to the configured ceiling. */
    private static int spursFor(int streakDay) {
        int step = Math.max(1, AuthConfig.VOTE_REWARD_VOTES_PER_STEP.get());
        int value = AuthConfig.VOTE_REWARD_SPURS_BASE.get() + (Math.max(1, streakDay) - 1) / step;
        return Math.min(AuthConfig.VOTE_REWARD_SPURS_MAX.get(), value);
    }

    /** Diamonds for streak day N, clamped to the configured ceiling. */
    private static int diamondsFor(int streakDay) {
        int step = Math.max(1, AuthConfig.VOTE_REWARD_VOTES_PER_STEP.get());
        int value = AuthConfig.VOTE_REWARD_DIAMONDS_BASE.get() + (Math.max(1, streakDay) - 1) / step;
        return Math.min(AuthConfig.VOTE_REWARD_DIAMONDS_MAX.get(), value);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            Map<String, Entry> m = GSON.fromJson(Files.readString(file), MAP_TYPE);
            if (m != null) state.putAll(m);
            CoffeesAeroAuth.LOGGER.info("[Vote] Loaded vote history for {} player(s).", state.size());
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.warn("[Vote] load failed: {}", ex.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        Map<String, Entry> snapshot = new HashMap<>(state);
        AsyncIo.submit(() -> {
            try {
                Files.writeString(file, GSON.toJson(snapshot, MAP_TYPE));
            } catch (Exception ex) {
                CoffeesAeroAuth.LOGGER.debug("[Vote] save failed: {}", ex.getMessage());
            }
        });
    }
}
