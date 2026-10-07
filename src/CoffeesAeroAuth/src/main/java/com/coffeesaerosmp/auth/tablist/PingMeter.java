package com.coffeesaerosmp.auth.tablist;

import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Our own round-trip measurement for the tab list (number + bars).
 *
 * <p>🔑 Why not {@code connection.latency()}: vanilla only measures on keep-alive, and in 1.21.1 the
 * keep-alive INTERVAL and TIMEOUT are one constant. Packet Fixer ({@code timeout=120}) raises that
 * constant to 120 s to stop timeouts during heavy chunk/contraption loads, so the server measured ping
 * once every two minutes, smoothed 3:1 from 0 — it read 0 ms for the first two minutes and took ~10
 * minutes to become accurate (diagnosed 2026-10-07: "[Tab] measured ping: ...=0ms" twice in a row).
 *
 * <p>Instead, every 5 s each player gets a {@link ClientboundPingPacket} whose id carries our marker in
 * the top byte; the client answers immediately with a pong, timed in {@code ServerCommonCookieMixin}.
 * Pongs without our marker are left alone for whoever sent them.
 */
public final class PingMeter {
    private PingMeter() {}

    private static final int MARK = 0x5A000000;
    private static final int MARK_MASK = 0xFF000000;
    private static final AtomicInteger SEQ = new AtomicInteger();
    /** uuid -> {ping id, send time nanos} of the one ping in flight. */
    private static final Map<UUID, long[]> PENDING = new ConcurrentHashMap<>();
    /** uuid -> smoothed round trip in ms. */
    private static final Map<UUID, Integer> MS = new ConcurrentHashMap<>();
    private static int ticks;

    /** Server thread, every tick; sends one ping per player every 5 s. */
    public static void onServerTick(MinecraftServer server) {
        if (++ticks % 100 != 0) return;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID u = p.getUUID();
            online.add(u);
            int id = MARK | (SEQ.incrementAndGet() & ~MARK_MASK);
            PENDING.put(u, new long[]{id, System.nanoTime()});
            p.connection.send(new ClientboundPingPacket(id));
        }
        PENDING.keySet().retainAll(online);
        MS.keySet().retainAll(online);
    }

    /**
     * Netty thread. @return true when the pong answered one of OUR pings (the caller may then drop it);
     * false when it belongs to someone else and must pass through untouched.
     */
    public static boolean onPong(UUID uuid, int id) {
        if ((id & MARK_MASK) != MARK) return false;
        long[] pend = PENDING.get(uuid);
        if (pend == null || pend[0] != id) return true;          // stale answer to an older ping
        PENDING.remove(uuid, pend);
        int rtt = (int) Math.max(1, (System.nanoTime() - pend[1]) / 1_000_000L);
        MS.merge(uuid, rtt, (old, now) -> (old * 2 + now) / 3);  // light smoothing, follows changes fast
        return true;
    }

    /** Our measurement once we have one; vanilla's until then. */
    public static int ms(ServerPlayer p) {
        Integer v = MS.get(p.getUUID());
        return v != null ? v : p.connection.latency();
    }
}
