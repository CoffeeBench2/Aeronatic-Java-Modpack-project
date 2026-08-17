package com.coffeesaerosmp.auth.daily;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.coffeesaerosmp.auth.util.Sounds;

/**
 * The {@code /daily} streak reward. Once per {@code dailyRewardIntervalHours}, a player claims a
 * reward that escalates over a 7-day streak (day 7 = weekly jackpot), then loops. Waiting longer
 * than 2x the interval breaks the streak back to day 1.
 *
 * <p>Rewards are <b>items + XP only — never currency</b>, so this system has zero economy impact by
 * design (money inflow lives in the Bank, not here). Per-player state (last-claim time + streak)
 * persists to {@code daily_rewards.json}; saves run off the server thread via {@link AsyncIo}, so the
 * command never blocks a tick.</p>
 */
public final class DailyRewardManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>(){}.getType();

    /** One reward tier: XP levels + items to give (each entry is [itemId, count]). */
    private record Tier(String name, int xpLevels, String[][] items) {}

    // 7-day escalating table. Items + XP only, SMP-safe (no OP gear, no coins). Day 7 = jackpot.
    private static final Tier[] TIERS = {
        new Tier("Day 1",             1,  new String[][]{{"minecraft:bread", "16"}}),
        new Tier("Day 2",             2,  new String[][]{{"minecraft:iron_ingot", "8"}, {"minecraft:cooked_beef", "8"}}),
        new Tier("Day 3",             3,  new String[][]{{"minecraft:gold_ingot", "6"}}),
        new Tier("Day 4",             3,  new String[][]{{"minecraft:diamond", "2"}}),
        new Tier("Day 5",             4,  new String[][]{{"create:andesite_alloy", "16"}}),
        new Tier("Day 6",             5,  new String[][]{{"minecraft:emerald", "4"}, {"minecraft:experience_bottle", "8"}}),
        new Tier("Day 7 ★ JACKPOT", 10, new String[][]{{"minecraft:diamond_block", "1"}, {"minecraft:netherite_scrap", "1"}}),
    };

    /** Per-player persisted state. */
    private static final class Entry {
        long lastClaimMs;
        int  streak;
    }

    private final Path file;
    private final Map<String, Entry> state = new ConcurrentHashMap<>();

    /**
     * Players whose "your reward is ready" nudge was withheld because they authenticated somewhere
     * that cannot claim (the lobby). The nudge is not dropped — it is re-sent the moment they set
     * foot in the Overworld, so every notification still arrives, just where the command works.
     */
    private final Set<UUID> pendingReminder = ConcurrentHashMap.newKeySet();

    public DailyRewardManager(Path dataDir) {
        this.file = dataDir == null ? null : dataDir.resolve("daily_rewards.json");
        load();
    }

    /** Handle a {@code /daily} claim. Returns true if a reward was granted this call. */
    public boolean claim(ServerPlayer player) {
        if (!AuthConfig.DAILY_REWARD_ENABLED.get()) {
            msg(player, "§cThe daily reward is currently disabled.");
            return false;
        }
        if (CoffeesAeroAuth.AUTH_MANAGER == null
                || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
            msg(player, "§cYou must be logged in to claim a daily reward.");
            return false;
        }
        // The reward is items + XP straight into the inventory. In the lobby that inventory is the
        // stashed/limited one and anything dropped falls into void, so the claim would be consumed
        // for nothing. Overworld only.
        if (!inOverworld(player)) {
            msg(player, TextUtil.PREFIX + (inLobby(player)
                ? "§cYou can't claim §f/daily§c in the lobby. Type §a/spawn§c first."
                : "§cYou can only claim §f/daily§c in the Overworld."));
            Sounds.error(player);
            return false;
        }

        long now        = System.currentTimeMillis();
        long intervalMs = AuthConfig.DAILY_REWARD_INTERVAL_HOURS.get() * 3_600_000L;
        long resetMs    = intervalMs * 2;
        String key = player.getUUID().toString();
        Entry e = state.computeIfAbsent(key, k -> new Entry());

        if (e.lastClaimMs > 0) {
            long since = now - e.lastClaimMs;
            if (since < intervalMs) {
                msg(player, "§7You've already claimed. Come back in §e"
                    + TextUtil.formatRemaining(intervalMs - since) + "§7.");
                Sounds.error(player);
                return false;
            }
            e.streak = (since <= resetMs) ? e.streak + 1 : 1;   // continue the streak vs. break it
        } else {
            e.streak = 1;
        }

        int dayIndex = (e.streak - 1) % TIERS.length;           // 0..6
        Tier tier = TIERS[dayIndex];
        grant(player, tier);
        e.lastClaimMs = now;
        save();

        int cycleDay = dayIndex + 1;
        Sounds.reward(player);
        msg(player, TextUtil.PREFIX + "§a✦ Daily reward claimed! §f" + tier.name()
            + " §7(streak §e" + e.streak + "§7 · day §e" + cycleDay + "§7/7)");

        if (cycleDay == TIERS.length) {                          // weekly jackpot — small server shout
            MinecraftServer server = player.getServer();
            if (server != null) {
                String who = player.getDisplayName().getString();
                server.getPlayerList().broadcastSystemMessage(Component.literal(
                    TextUtil.PREFIX + "§6✦ §e" + who + " §6hit a 7-day streak and claimed the weekly jackpot!"),
                    false);
            }
        }
        return true;
    }

    /** Milliseconds until this player may claim again; {@code 0} means claimable right now. */
    public long msUntilClaimable(java.util.UUID uuid) {
        Entry e = state.get(uuid.toString());
        if (e == null || e.lastClaimMs <= 0) return 0L;          // never claimed = ready
        long intervalMs = AuthConfig.DAILY_REWARD_INTERVAL_HOURS.get() * 3_600_000L;
        long since = System.currentTimeMillis() - e.lastClaimMs;
        return since >= intervalMs ? 0L : intervalMs - since;
    }

    /**
     * Join-time nudge, sent once after auth completes. **Silent unless there is something to claim**
     * — a reminder that fires when the reward is not available is just noise on every join, and the
     * welcome sequence is already busy.
     *
     * <p>The streak it advertises is computed the same way {@link #claim} computes it, including the
     * {@code resetMs} break rule, so the number here cannot disagree with what the player actually
     * receives.
     */
    public void remindOnJoin(ServerPlayer player) {
        try {
            if (!AuthConfig.DAILY_REWARD_ENABLED.get()) return;
            if (msUntilClaimable(player.getUUID()) > 0) return;

            // Authenticated in the lobby (or anywhere /daily is refused): hold the nudge rather than
            // advertising a command that will be rejected. It fires on arrival in the Overworld.
            if (!inOverworld(player)) {
                pendingReminder.add(player.getUUID());
                return;
            }
            sendReminder(player);
        } catch (Exception ex) {
            // A cosmetic nudge must never interfere with a join.
            CoffeesAeroAuth.LOGGER.debug("[Daily] join reminder skipped: {}", ex.toString());
        }
    }

    /**
     * Delivers a withheld nudge the moment the player reaches the Overworld — the lobby → world
     * step is a dimension change, so this is where a deferred reminder lands.
     */
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            if (!pendingReminder.contains(player.getUUID())) return;
            if (player.level().dimension() != Level.OVERWORLD) return;
            pendingReminder.remove(player.getUUID());

            if (!AuthConfig.DAILY_REWARD_ENABLED.get()) return;
            if (msUntilClaimable(player.getUUID()) > 0) return;   // claimed in between somehow
            sendReminder(player);
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.debug("[Daily] deferred reminder skipped: {}", ex.toString());
        }
    }

    /** Drops any withheld nudge for a leaving player, so the set never grows across sessions. */
    public void onPlayerLeave(ServerPlayer player) {
        pendingReminder.remove(player.getUUID());
    }

    private static boolean inOverworld(ServerPlayer player) {
        return player.level().dimension() == Level.OVERWORLD;
    }

    private static boolean inLobby(ServerPlayer player) {
        return player.level().dimension()
            == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
    }

    private void sendReminder(ServerPlayer player) {
        try {
            Entry e = state.get(player.getUUID().toString());
            long intervalMs = AuthConfig.DAILY_REWARD_INTERVAL_HOURS.get() * 3_600_000L;
            int streak;
            if (e == null || e.lastClaimMs <= 0) {
                streak = 1;
            } else {
                long since = System.currentTimeMillis() - e.lastClaimMs;
                streak = (since <= intervalMs * 2) ? e.streak + 1 : 1;   // mirrors claim()
            }
            int cycleDay = ((streak - 1) % TIERS.length) + 1;
            Tier tier = TIERS[cycleDay - 1];

            msg(player, TextUtil.PREFIX + "§e✦ Your §f/daily§e reward is ready! §7("
                + tier.name() + " · streak §e" + streak + "§7 · day §e" + cycleDay + "§7/7)");
            Sounds.notify(player);
            if (streak == 1 && e != null && e.lastClaimMs > 0) {
                msg(player, TextUtil.PREFIX + "§7Your streak reset — claim daily to keep it going.");
            }
        } catch (Exception ex) {
            // A cosmetic nudge must never interfere with a join.
            CoffeesAeroAuth.LOGGER.debug("[Daily] join reminder skipped: {}", ex.toString());
        }
    }

    private void grant(ServerPlayer player, Tier tier) {
        if (tier.xpLevels() > 0) player.giveExperienceLevels(tier.xpLevels());
        for (String[] entry : tier.items()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry[0]));
            if (item == null || item == Items.AIR) continue;    // mod/item absent — skip that line silently
            int count;
            try { count = Integer.parseInt(entry[1]); } catch (NumberFormatException ex) { continue; }
            int max = new ItemStack(item).getMaxStackSize();
            while (count > 0) {
                int n = Math.min(count, max);
                ItemStack stack = new ItemStack(item, n);
                if (!player.getInventory().add(stack)) player.drop(stack, false);
                count -= n;
            }
        }
    }

    private void msg(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(s));
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            Map<String, Entry> m = GSON.fromJson(Files.readString(file), MAP_TYPE);
            if (m != null) state.putAll(m);
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.warn("[Daily] load failed: {}", ex.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        Map<String, Entry> snapshot = new HashMap<>(state);
        AsyncIo.submit(() -> {
            try {
                Files.writeString(file, GSON.toJson(snapshot, MAP_TYPE));
            } catch (Exception ex) {
                CoffeesAeroAuth.LOGGER.debug("[Daily] save failed: {}", ex.getMessage());
            }
        });
    }
}
