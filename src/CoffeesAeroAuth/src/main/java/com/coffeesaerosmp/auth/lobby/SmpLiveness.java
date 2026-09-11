package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.coffeesaerosmp.auth.util.Repeating.guard;

/**
 * Lobby-side liveness check for the SMP, by Server List Ping.
 *
 * <h2>Why SLP and not a database heartbeat</h2>
 * Both servers share one MySQL, so a heartbeat row is the obvious idea and the wrong one. A heartbeat
 * can be <b>stale-but-alive</b> (the writer thread died while the server runs) or <b>alive-but-not-
 * joinable</b> (mid-boot, world still loading). An SLP tests the only thing that actually matters to a
 * player: <i>is the port answering the protocol</i>. It is also the pattern already proven in this
 * stack — AeroGate's {@code BackendStatus} polls its backend exactly this way.
 *
 * <h2>🔴 N consecutive results before the state flips</h2>
 * This is the sharp edge of the whole feature. A single false NEGATIVE makes the lobby refuse
 * everyone; a single false POSITIVE fires players at a dead port. One dropped packet must never do
 * either, so the state only changes after {@code smpLivenessConfirmCount} identical polls in a row.
 * The counter resets whenever a poll disagrees with the pending direction.
 *
 * <h2>Off the tick loop entirely</h2>
 * Runs on its own daemon thread with a socket timeout. Blocking network I/O on the server thread is
 * how you turn a health check into the outage it was meant to detect.
 *
 * <p>Also records the SMP's reported player count, which is the number the gate ought to be
 * advertising now that it polls the lobby rather than the SMP.
 */
public final class SmpLiveness {

    private SmpLiveness() {}

    private static ScheduledExecutorService exec;
    private static final AtomicBoolean started = new AtomicBoolean(false);

    /** Current believed state. Starts UP so a lobby booting alone never refuses everyone. */
    private static volatile boolean up = true;
    private static volatile int onlinePlayers = -1;
    private static volatile int maxPlayers    = -1;
    private static volatile long lastChangeMs = 0L;

    /** Consecutive polls agreeing with the OPPOSITE of {@link #up}. */
    private static int pendingStreak = 0;

    public static boolean isUp()          { return up; }
    public static int     onlinePlayers() { return onlinePlayers; }
    public static int     maxPlayers()    { return maxPlayers; }
    public static long    lastChangeMs()  { return lastChangeMs; }

    /** Starts polling. Only meaningful on a LOBBY-role server; a no-op anywhere else. */
    public static void start() {
        if (!LobbyHandoff.isLobbyRole()) return;
        boolean enabled;
        try { enabled = AuthConfig.SMP_LIVENESS_ENABLED.get(); } catch (Exception e) { return; }
        if (!enabled || !started.compareAndSet(false, true)) return;

        int period;
        try { period = AuthConfig.SMP_LIVENESS_POLL_SECONDS.get(); } catch (Exception e) { period = 15; }

        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroLobby-SmpLiveness");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(guard("smp-liveness", SmpLiveness::poll), 5, period, TimeUnit.SECONDS);
        CoffeesAeroAuth.LOGGER.info("[Liveness] Watching the SMP every {}s (confirm x{}).",
            period, confirmCount());
    }

    public static void stop() {
        if (exec != null) exec.shutdownNow();
        exec = null;
        started.set(false);
    }

    private static int confirmCount() {
        try { return AuthConfig.SMP_LIVENESS_CONFIRM.get(); } catch (Exception e) { return 3; }
    }

    private static void poll() {
        String host; int port; int timeoutMs;
        try {
            host      = AuthConfig.HANDOFF_HOST.get().trim();   // the SMP we hand players to
            port      = AuthConfig.HANDOFF_PORT.get();
            timeoutMs = AuthConfig.SMP_LIVENESS_TIMEOUT_MS.get();
        } catch (Exception e) { return; }
        if (host.isEmpty()) return;

        boolean reachable = ping(host, port, timeoutMs);

        if (reachable == up) {
            pendingStreak = 0;                       // agrees with current state; nothing to do
            return;
        }
        if (++pendingStreak < confirmCount()) return;   // not confirmed yet — one blip is not an outage

        pendingStreak = 0;
        up = reachable;
        lastChangeMs = System.currentTimeMillis();
        if (up) {
            CoffeesAeroAuth.LOGGER.info("[Liveness] SMP is BACK ({}:{}) — the door is open again.", host, port);
            LobbyWaitingRoom.onSmpBack();
        } else {
            onlinePlayers = -1;
            CoffeesAeroAuth.LOGGER.warn("[Liveness] SMP is DOWN ({}:{}) — holding players in the lobby.", host, port);
            LobbyWaitingRoom.onSmpDown();
        }
    }

    /**
     * One Server List Ping. Returns true only on a well-formed status response.
     *
     * <p>Deliberately strict: a bare TCP connect would succeed against a port that is bound but not
     * yet serving, which is exactly the window where re-admitting players drops them into a server
     * that cannot take them.
     */
    private static boolean ping(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream  in  = new DataInputStream(s.getInputStream());

            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream hs = new java.io.ByteArrayOutputStream();
            hs.write(0x00);                       // handshake
            writeVarInt(hs, 767);                 // protocol (1.21.1); any value works for status
            writeVarInt(hs, hostBytes.length);
            hs.write(hostBytes);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 1);                   // next state: status
            writePacket(out, hs.toByteArray());

            writePacket(out, new byte[]{0x00});   // status request

            readVarInt(in);                       // packet length
            if (readVarInt(in) != 0x00) return false;
            int jsonLen = readVarInt(in);
            if (jsonLen <= 0 || jsonLen > 1 << 20) return false;
            byte[] buf = new byte[jsonLen];
            in.readFully(buf);

            JsonObject o = JsonParser.parseString(new String(buf, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("players")) {
                JsonObject p = o.getAsJsonObject("players");
                if (p.has("online")) onlinePlayers = p.get("online").getAsInt();
                if (p.has("max"))    maxPlayers    = p.get("max").getAsInt();
            }
            return true;
        } catch (EOFException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writePacket(DataOutputStream out, byte[] body) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        writeVarInt(b, body.length);
        b.write(body);
        out.write(b.toByteArray());
        out.flush();
    }

    private static void writeVarInt(java.io.OutputStream o, int v) throws java.io.IOException {
        while (true) {
            if ((v & ~0x7F) == 0) { o.write(v); return; }
            o.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws java.io.IOException {
        int n = 0, shift = 0;
        while (true) {
            int b = in.readByte() & 0xFF;
            n |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return n;
            shift += 7;
            if (shift > 35) throw new java.io.IOException("VarInt too long");
        }
    }
}
