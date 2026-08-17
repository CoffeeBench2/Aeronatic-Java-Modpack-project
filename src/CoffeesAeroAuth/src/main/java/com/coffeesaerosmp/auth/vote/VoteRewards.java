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

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * <h2>Scaling</h2>
 * The reward grows with a player's lifetime vote count and is then clamped:
 * {@code reward = min(max, base + totalVotes / votesPerStep)}. Regular voters end up better off
 * than one-off voters, but the per-vote ceiling means it can never inflate without bound — spurs
 * cap at {@code voteRewardSpursMax}, diamonds at {@code voteRewardDiamondsMax}.
 */
public final class VoteRewards {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>(){}.getType();

    /** Per-player persisted state. {@code pending} is votes banked while they were offline. */
    private static final class Entry {
        int  totalVotes;
        int  pending;
        long lastVoteMs;
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
        synchronized (e) {
            e.totalVotes++;
            e.lastVoteMs = System.currentTimeMillis();
            voteNumber = e.totalVotes;
            e.pending++;                      // cleared below if they are online to receive it now
        }
        save();

        CoffeesAeroAuth.LOGGER.info("[Vote] {} voted on {} (vote #{})",
            profile.username, serviceName, voteNumber);

        // Hop to the server thread before looking at players or touching inventories.
        String shown = (profile.displayName != null && !profile.displayName.isBlank())
            ? profile.displayName : profile.username;
        server.execute(() -> {
            announce(server, shown, voteNumber);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) deliver(player, server);
        });
        return true;
    }

    /** Pays out anything banked while the player was offline. Called on join, on the server thread. */
    public void onPlayerJoin(ServerPlayer player) {
        try {
            if (!AuthConfig.VOTE_REWARD_ENABLED.get()) return;
            Entry e = state.get(player.getUUID().toString());
            if (e == null || e.pending <= 0) return;
            deliver(player, player.getServer());
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.debug("[Vote] join payout skipped: {}", ex.toString());
        }
    }

    /** Lifetime vote count, for {@code /authmod player} and the reward message. */
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

    /** MUST run on the server thread. Pays every banked vote and clears the counter. */
    private void deliver(ServerPlayer player, MinecraftServer server) {
        Entry e = state.get(player.getUUID().toString());
        if (e == null) return;

        int owed, total;
        synchronized (e) {
            owed  = e.pending;
            total = e.totalVotes;
            if (owed <= 0) return;
            e.pending = 0;
        }
        save();

        // Each banked vote is paid at the tier the player had reached, so someone who banked five
        // votes is not paid five times at their FINAL tier — the reward earned the ladder as it went.
        // Votes past the lifetime cap pay nothing; they still counted, they are just not rewarded.
        int cap = AuthConfig.VOTE_REWARD_MAX_REWARDED.get();
        int spurs = 0, diamonds = 0, paidVotes = 0;
        for (int i = owed; i >= 1; i--) {
            int at = Math.max(1, total - i + 1);
            if (cap > 0 && at > cap) continue;
            spurs    += spursFor(at);
            diamonds += diamondsFor(at);
            paidVotes++;
        }

        if (paidVotes == 0) {
            // Past the cap. Say so plainly rather than paying nothing in silence — an unexplained
            // empty reward reads as a bug, and this player is a repeat voter worth keeping onside.
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§a✦ Thanks for voting! §7You've already collected all §f" + cap
                + "§7 vote rewards — but every vote still pushes the server up the list. §6❤"));
            Sounds.success(player);
            CoffeesAeroAuth.LOGGER.info("[Vote] {} voted past the {}-vote reward cap (lifetime {})",
                player.getGameProfile().getName(), cap, total);
            return;
        }

        Coins.pay(player, spurs);
        Coins.giveItem(player, ResourceLocation.parse("minecraft:diamond"), diamonds);

        String votes = owed == 1 ? "vote" : owed + " votes";
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§a✦ Thanks for voting! §7(" + votes + ") §f→ §6" + spurs + " spurs §fand §b"
            + diamonds + " diamonds§f."));
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Rewarded votes: §e"
            + Math.min(total, cap > 0 ? cap : total) + (cap > 0 ? "§7/§e" + cap : "") + "§7."
            + (cap > 0 && total >= cap
               ? " §6That's all of them — thank you!"
               : " §7Each vote pays a little more than the last.")));
        Sounds.reward(player);

        CoffeesAeroAuth.LOGGER.info("[Vote] Paid {} for {} of {} vote(s): {} spurs, {} diamonds (lifetime {})",
            player.getGameProfile().getName(), paidVotes, owed, spurs, diamonds, total);
    }

    /** Spurs for the Nth lifetime vote, clamped to the configured ceiling. */
    private static int spursFor(int voteNumber) {
        int step = Math.max(1, AuthConfig.VOTE_REWARD_VOTES_PER_STEP.get());
        int value = AuthConfig.VOTE_REWARD_SPURS_BASE.get() + (voteNumber - 1) / step;
        return Math.min(AuthConfig.VOTE_REWARD_SPURS_MAX.get(), value);
    }

    /** Diamonds for the Nth lifetime vote, clamped to the configured ceiling. */
    private static int diamondsFor(int voteNumber) {
        int step = Math.max(1, AuthConfig.VOTE_REWARD_VOTES_PER_STEP.get());
        int value = AuthConfig.VOTE_REWARD_DIAMONDS_BASE.get() + (voteNumber - 1) / step;
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
