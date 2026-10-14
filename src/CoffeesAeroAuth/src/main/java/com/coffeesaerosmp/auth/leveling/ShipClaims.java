package com.coffeesaerosmp.auth.leveling;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Free AeroClaims ship claims, separate from land (owner, 2026-10-15).
 *
 * <h2>How AeroClaims works (read from its 0.9.4 bytecode)</h2>
 * It has no allowance of its own. With {@code claimProvider = FTB_CHUNKS} a player MOVES FTB land claims
 * into it ({@code AeroClaimManager.transferFromProvider(player, n)} → {@code addMigratedSlots}), and its
 * {@code ChunkTeamDataMixin} then lowers the FTB claim limit by the team's total migrated slots. One slot
 * covers {@code blocksPerClaim} ship blocks.
 *
 * <h2>The grant</h2>
 * {@link LevelService} adds the ship allowance to the player's FTB EXTRA claims, then this class moves
 * exactly that many into AeroClaims. Net: land unchanged, N ship slots for free. The amount already
 * granted is recorded per player in world-saved data ({@code coffeesaerosmp_ship_claims}) — it lives with
 * the world exactly like AeroClaims' own slot data, so a world restore keeps the two consistent, and the
 * join path never touches the database.
 *
 * <p>Never taken back: lowering the config keeps what was granted (the FTB extra keeps including it).
 * A player MAY move bonus slots back to land in the AeroClaims screen (owner chose not to block it):
 * worst case they trade free ship slots for the same number of chunks. They are not re-granted.
 *
 * <p>Reflection: AeroClaims is not on the compile classpath, and the lobby/test servers run without it.
 */
public final class ShipClaims {

    private static volatile boolean resolved, available;
    private static Method transferFromProvider, syncProviderUsage;

    private ShipClaims() {}

    /** Ship slots already granted to this player in this world. */
    public static int granted(MinecraftServer server, UUID uuid) {
        return Ledger.get(server).granted(uuid);
    }

    /** Granted slots, or 0 without a server — keeps them in the FTB extra even when the feature is off. */
    static int grantedOrZero(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? 0 : granted(server, player.getUUID());
    }

    /**
     * Moves {@code target - granted} slots into AeroClaims. The FTB extra must ALREADY include the ship
     * allowance (so the player has the free land slots to move). Server thread only.
     *
     * @return slots granted now (0 when nothing was due or it failed — retried on the next check)
     */
    static int topUp(ServerPlayer player, int target) {
        MinecraftServer server = player.getServer();
        if (server == null || target <= 0 || !resolve()) return 0;
        Ledger ledger = Ledger.get(server);
        int have = ledger.granted(player.getUUID());
        int due = target - have;
        if (due <= 0) return 0;
        try {
            Object result = transferFromProvider.invoke(null, player, due);
            if (!"SUCCESS".equals(String.valueOf(result))) {
                CoffeesAeroAuth.LOGGER.warn("[ShipClaims] {}: AeroClaims refused {} slot(s): {} (retried next check)",
                    player.getGameProfile().getName(), due, result);
                return 0;
            }
            ledger.set(player.getUUID(), target);
            try { syncProviderUsage.invoke(null, player); } catch (Throwable ignored) {}
            player.sendSystemMessage(Component.literal("§b✈ §fYou got §b" + due + " free ship claim"
                + (due == 1 ? "" : "s") + "§f (§b" + target + "§f total) — claim your airship in §bAeroClaims§f."
                + " Your land claims are unchanged."));
            CoffeesAeroAuth.LOGGER.info("[ShipClaims] {} +{} ship slot(s) -> {}", player.getGameProfile().getName(),
                due, target);
            return due;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[ShipClaims] grant failed for {}: {}", player.getGameProfile().getName(),
                t.toString());
            return 0;
        }
    }

    static boolean available() { return resolve(); }

    private static boolean resolve() {
        if (resolved) return available;
        synchronized (ShipClaims.class) {
            if (resolved) return available;
            try {
                Class<?> mgr = Class.forName("com.mapter.aeroclaims.claim.AeroClaimManager");
                transferFromProvider = mgr.getMethod("transferFromProvider", ServerPlayer.class, int.class);
                syncProviderUsage = mgr.getMethod("syncProviderUsage", ServerPlayer.class);
                available = true;
                CoffeesAeroAuth.LOGGER.info("[ShipClaims] AeroClaims found — free ship claims are on.");
            } catch (Throwable t) {
                available = false;
                CoffeesAeroAuth.LOGGER.info("[ShipClaims] AeroClaims not available ({}) — no ship claims granted.",
                    t.getClass().getSimpleName());
            }
            resolved = true;
            return available;
        }
    }

    /** Per-world record of granted ship slots: overworld data storage, saved with the world. */
    static final class Ledger extends SavedData {
        private static final String NAME = "coffeesaerosmp_ship_claims";
        private final Map<UUID, Integer> granted = new HashMap<>();
        /** Last FTB extra the level system applied — so an outside change (an admin) can be told apart. */
        private final Map<UUID, Integer> lastApplied = new HashMap<>();
        /** Claims added from OUTSIDE the level system (admin commands, rewards). Kept on top, forever. */
        private final Map<UUID, Integer> bonus = new HashMap<>();

        static Ledger get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Ledger::new, Ledger::load, null), NAME);
        }

        int granted(UUID uuid) { return granted.getOrDefault(uuid, 0); }

        void set(UUID uuid, int n) {
            granted.put(uuid, n);
            setDirty();
        }

        Integer lastApplied(UUID uuid) { return lastApplied.get(uuid); }

        void setLastApplied(UUID uuid, int n) {
            Integer old = lastApplied.put(uuid, n);
            if (old == null || old != n) setDirty();
        }

        int bonus(UUID uuid) { return bonus.getOrDefault(uuid, 0); }

        void setBonus(UUID uuid, int n) {
            Integer old = bonus.put(uuid, Math.max(0, n));
            if (old == null || old != Math.max(0, n)) setDirty();
        }

        static Ledger load(CompoundTag tag, HolderLookup.Provider lookup) {
            Ledger l = new Ledger();
            readMap(tag.getCompound("granted"), l.granted);
            readMap(tag.getCompound("lastApplied"), l.lastApplied);
            readMap(tag.getCompound("bonus"), l.bonus);
            return l;
        }

        private static void readMap(CompoundTag g, Map<UUID, Integer> into) {
            for (String k : g.getAllKeys()) {
                try { into.put(UUID.fromString(k), g.getInt(k)); } catch (IllegalArgumentException ignored) {}
            }
        }

        private static CompoundTag writeMap(Map<UUID, Integer> m) {
            CompoundTag g = new CompoundTag();
            m.forEach((u, n) -> g.putInt(u.toString(), n));
            return g;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
            tag.put("granted", writeMap(granted));
            tag.put("lastApplied", writeMap(lastApplied));
            tag.put("bonus", writeMap(bonus));
            return tag;
        }
    }
}
