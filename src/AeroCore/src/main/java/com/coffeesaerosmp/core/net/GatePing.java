package com.coffeesaerosmp.core.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Pings the gate with a plain Server List Ping so the title screen can show whether the server is up
 * and how many people are on it.
 *
 * <p>Worth knowing why this number is meaningful at all: the gate TRANSFERS players to the backend
 * rather than proxying them, so its own player list is empty essentially always. AeroGate's
 * BackendStatus polls the real backend and rewrites the ping, so what we read here is the true
 * in-game count. Before that server-side fix this would have shown 0 forever.
 *
 * <p>Deliberately hand-rolled rather than using vanilla's {@code ServerStatusPinger}: that wants a
 * {@code ServerData} and drives its own callbacks into the multiplayer list. We want three volatile
 * fields and no coupling to a screen that may already have been closed.
 */
public final class GatePing {

    /** Refresh no faster than this — the title screen re-inits on every resize. */
    private static final long MIN_INTERVAL_MS = 20_000L;
    private static final int TIMEOUT_MS = 4000;
    private static final int PROTOCOL_VERSION = 767;   // 1.21.1

    public enum State { UNKNOWN, ONLINE, OFFLINE }

    public static volatile State state = State.UNKNOWN;
    public static volatile int online = 0;
    public static volatile int max = 0;

    private static volatile long lastAttemptMs = 0;
    private static volatile boolean inFlight = false;

    private GatePing() {}

    /**
     * Kick off a refresh if one isn't already running and the last was long enough ago. Safe to call
     * every time the title screen initialises — it self-throttles.
     */
    public static void refresh() {
        long now = System.currentTimeMillis();
        synchronized (GatePing.class) {
            if (inFlight || now - lastAttemptMs < MIN_INTERVAL_MS) return;
            inFlight = true;
            lastAttemptMs = now;
        }
        Thread t = new Thread(GatePing::run, "AeroCore-GatePing");
        t.setDaemon(true);   // must never hold the game open on quit
        t.start();
    }

    private static void run() {
        try {
            String address = ServerLocator.resolve();
            String host = address;
            int port = 25565;
            int colon = address.lastIndexOf(':');
            if (colon > 0) {
                host = address.substring(0, colon);
                try { port = Integer.parseInt(address.substring(colon + 1)); } catch (NumberFormatException ignored) {}
            }
            String json = ping(host, port);
            int on = extractInt(json, "online");
            int mx = extractInt(json, "max");
            if (on < 0) {
                state = State.OFFLINE;
            } else {
                online = on;
                max = Math.max(mx, on);
                state = State.ONLINE;
            }
        } catch (Exception e) {
            state = State.OFFLINE;
        } finally {
            inFlight = false;
        }
    }

    // ── Server List Ping ──────────────────────────────────────────────────────

    private static String ping(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream rawOut = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);
            writeVarInt(h, PROTOCOL_VERSION);
            writeString(h, host);
            h.writeShort(port);
            writeVarInt(h, 1);                       // next state: status
            writeFramed(rawOut, handshake.toByteArray());

            ByteArrayOutputStream request = new ByteArrayOutputStream();
            writeVarInt(new DataOutputStream(request), 0x00);
            writeFramed(rawOut, request.toByteArray());
            rawOut.flush();

            readVarInt(in);                          // frame length
            if (readVarInt(in) != 0x00) throw new IOException("unexpected status packet");
            int len = readVarInt(in);
            if (len <= 0 || len > 1 << 20) throw new IOException("bad status length");
            byte[] payload = in.readNBytes(len);
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads {@code players.online} / {@code players.max} without a JSON library. Scoped to the
     * {@code "players"} object on purpose: a MOTD is arbitrary operator text and could contain the
     * word "online", which a whole-document search would happily read a number out of.
     */
    static int extractInt(String json, String key) {
        int players = json.indexOf("\"players\"");
        if (players < 0) return -1;
        int open = json.indexOf('{', players);
        if (open < 0) return -1;
        int close = json.indexOf('}', open);
        if (close < 0) close = json.length();
        String scope = json.substring(open, close);

        int k = scope.indexOf('"' + key + '"');
        if (k < 0) return -1;
        int colon = scope.indexOf(':', k);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < scope.length() && Character.isWhitespace(scope.charAt(i))) i++;
        int start = i;
        while (i < scope.length() && (Character.isDigit(scope.charAt(i)) || scope.charAt(i) == '-')) i++;
        if (i == start) return -1;
        try { return Integer.parseInt(scope.substring(start, i)); } catch (NumberFormatException e) { return -1; }
    }

    // ── VarInt / framing ──────────────────────────────────────────────────────

    private static void writeFramed(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(framed);
        writeVarInt(d, body.length);
        d.write(body);
        out.write(framed.toByteArray());
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("stream ended inside a VarInt");
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too long");
        }
    }
}
