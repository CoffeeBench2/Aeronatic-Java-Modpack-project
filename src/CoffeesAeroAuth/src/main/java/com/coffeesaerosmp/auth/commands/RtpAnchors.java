package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed destination set behind /rtp — see {@link RtpCommand} for the request flow.
 *
 * <p>WHY: the original /rtp rolled a brand-new random target per use, so every single request
 * paid full worldgen for a 15x15 chunk bubble. Measured live 2026-07-18 at ~3.4s per virgin
 * chunk, that is a multi-minute job, which is why /rtp needs a global one-at-a-time queue and a
 * 600s timeout at all. Anchors change the economics: a small pool of destinations is generated
 * ONCE, and from then on arriving there is a disk LOAD (~ms/chunk), not a generation. The
 * existing ticket/poll/land machinery is untouched — only where the target comes from changes,
 * so the readiness poll simply completes almost immediately instead of after minutes.
 *
 * <p>The cost is that /rtp stops being truly random: with N anchors, players repeatedly land in
 * the same N areas, which get stripped of trees/ore and built over. Mitigated three ways —
 * least-recently-used selection so arrivals rotate, {@code rtpAnchorSpread} scatter inside each
 * pregenerated bubble so it is not N exact blocks, and {@code rtpAnchorCount} being tunable.
 * Deleting {@code rtp_anchors.json} re-rolls the whole set when an area gets too picked-over.
 *
 * <p>Anchors are never force-loaded. Keeping 30 bubbles resident would be 30 x 361 = 10830 chunks
 * of permanent memory and tick cost; the point is that generated chunks live on DISK, and loading
 * them is the cheap operation.
 *
 * <p><b>1.7.8 — warmth is a RADIUS, not a boolean.</b> The old {@code warm} flag said "this
 * destination has been generated" without saying how far, and that hid two failures. (1) A bubble
 * generated to exactly {@code rtpPregenRadius} has a SOFT RIM: its outermost chunks are FULL on
 * disk, but promoting them back to FULL on the next visit pulls in their own neighbours one ring
 * further out, which nothing ever generated — so /rtp still paid worldgen for the rim. Measured
 * live 2026-08-05: rings 0-6 loaded in under a second, then the 56-chunk ring 7 took 152s at the
 * ~2.7s/chunk generation rate. (2) Raising {@code rtpPregenRadius} silently invalidated every
 * anchor, because a boolean cannot notice that the requirement grew. Storing the verified radius
 * fixes both: an anchor is usable only while {@code warmRadius >= rtpPregenRadius}, the warmer
 * generates out to {@code rtpPregenRadius + rtpAnchorWarmMargin} so the required rim's neighbours
 * are on disk too, and {@link #demote} sends an anchor back to the warmer when a live request
 * proves it was not as warm as claimed.
 */
public final class RtpAnchors {

    /** Mutable by design — {@code warmRadius} and {@code lastUsedMs} are updated in place on the server thread. */
    static final class Anchor {
        final int x, z;
        int warmRadius;        // chunk radius VERIFIED generated around this anchor; 0 = cold
        long lastUsedMs;       // 0 = never used; drives LRU rotation

        Anchor(int x, int z, int warmRadius, long lastUsedMs) {
            this.x = x; this.z = z; this.warmRadius = warmRadius; this.lastUsedMs = lastUsedMs;
        }
    }

    private static final List<Anchor> anchors = new ArrayList<>();
    private static volatile Path file;

    private RtpAnchors() {}

    // ── Persistence ───────────────────────────────────────────────────────────

    public static synchronized void initialize(Path dataDir) {
        file = dataDir.resolve("rtp_anchors.json");
        anchors.clear();
        if (!Files.exists(file)) {
            CoffeesAeroAuth.LOGGER.info("[Rtp] No rtp_anchors.json — anchors will be picked and warmed on demand.");
            return;
        }
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (var el : arr) {
                JsonObject o = el.getAsJsonObject();
                // Pre-1.7.8 files only have the boolean. Such an anchor was warmed to exactly
                // rtpPregenRadius, so record that honestly: it stays USABLE (still far cheaper than
                // a virgin random target) but counts as soft-rimmed, and nextCold() queues it for
                // the margin top-up that makes arrivals actually instant.
                int warmRadius = o.has("warmRadius") ? o.get("warmRadius").getAsInt()
                    : (o.has("warm") && o.get("warm").getAsBoolean() ? pregenRadius() : 0);
                anchors.add(new Anchor(
                    o.get("x").getAsInt(),
                    o.get("z").getAsInt(),
                    warmRadius,
                    o.has("lastUsed") ? o.get("lastUsed").getAsLong() : 0L));
            }
            CoffeesAeroAuth.LOGGER.info("[Rtp] Loaded {} anchors — {} usable, {} of those hardened to radius {}.",
                anchors.size(), countUsable(), countHardened(), warmTargetRadius());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Rtp] rtp_anchors.json load failed: {}", e.getMessage());
            anchors.clear();
        }
    }

    static synchronized void save() {
        Path f = file;
        if (f == null) return;
        JsonArray arr = new JsonArray();
        for (Anchor a : anchors) {
            JsonObject o = new JsonObject();
            o.addProperty("x", a.x);
            o.addProperty("z", a.z);
            o.addProperty("warmRadius", a.warmRadius);
            o.addProperty("lastUsed", a.lastUsedMs);
            arr.add(o);
        }
        String json = arr.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Rtp] anchor save failed: {}", e.getMessage()); }
        });
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    static int configuredCount() {
        try { return AuthConfig.RTP_ANCHOR_COUNT.get(); } catch (Exception e) { return 0; }
    }

    static boolean enabled() { return configuredCount() > 0; }

    /** Chunk radius a player /rtp needs generated before it will teleport. */
    static int pregenRadius() {
        try { return AuthConfig.RTP_PREGEN_RADIUS.get(); } catch (Exception e) { return 7; }
    }

    /** Radius the WARMER generates to — one or two rings past what players need, so the rim is hard. */
    static int warmTargetRadius() {
        int margin;
        try { margin = AuthConfig.RTP_ANCHOR_WARM_MARGIN.get(); } catch (Exception e) { margin = 2; }
        return pregenRadius() + margin;
    }

    /** Generated far enough that /rtp can send someone here at all. */
    static boolean usable(Anchor a) { return a.warmRadius >= pregenRadius(); }

    /** Usable AND rimmed by at least one further generated ring — the arrival is a pure disk load. */
    static boolean hardened(Anchor a) { return a.warmRadius > pregenRadius(); }

    static synchronized int size() { return anchors.size(); }

    static synchronized int countUsable() {
        int n = 0;
        for (Anchor a : anchors) if (usable(a)) n++;
        return n;
    }

    static synchronized int countHardened() {
        int n = 0;
        for (Anchor a : anchors) if (hardened(a)) n++;
        return n;
    }

    /** True when the pool is short of the configured size and another should be picked. */
    static synchronized boolean needsMore() {
        return anchors.size() < configuredCount();
    }

    /**
     * Evenly spaced bearing for the NEXT anchor, so a pool of 30 forms a ring around spawn rather
     * than clumping wherever the RNG happened to land. Jitter is applied by the caller.
     */
    static synchronized double nextAngle() {
        int target = Math.max(configuredCount(), 1);
        return (anchors.size() % target) * (Math.PI * 2.0 / target);
    }

    static synchronized void add(int x, int z) {
        anchors.add(new Anchor(x, z, 0, 0L));
        save();
    }

    /**
     * Least-recently-used destination for the next /rtp, or null if nothing is generated yet.
     *
     * <p>STRICT LRU across every usable anchor — variety is the whole point of having 30 of them,
     * and the oldest arrival is always the one that has had the longest to regrow.
     *
     * <p>Hardened only breaks TIES. The first cut of this (2026-08-05) preferred hardened anchors
     * outright, which read as sensible and was badly wrong: with one hardened anchor and 29 soft
     * ones, the hardened one won every single comparison and /rtp sent everybody to the same place
     * forever. Ranking by recency and using hardness only when two candidates are equally stale —
     * which is every anchor at {@code lastUsedMs == 0}, i.e. a fresh pool — keeps the nice-arrival
     * preference for the first visit without ever collapsing the rotation.
     */
    static synchronized Anchor nextWarm() {
        Anchor best = null;
        for (Anchor a : anchors) {
            if (!usable(a)) continue;
            if (best == null
                || a.lastUsedMs < best.lastUsedMs
                || (a.lastUsedMs == best.lastUsedMs && hardened(a) && !hardened(best))) {
                best = a;
            }
        }
        return best;
    }

    /**
     * Next anchor for the warmer: the LEAST generated one still short of {@link #warmTargetRadius}.
     * Lowest-first means brand-new destinations come online before top-ups of anchors that already
     * work, and a re-walk of rings already on disk costs load time, not generation time.
     */
    static synchronized Anchor nextCold() {
        int target = warmTargetRadius();
        Anchor best = null;
        for (Anchor a : anchors) {
            if (a.warmRadius >= target) continue;
            if (best == null || a.warmRadius < best.warmRadius) best = a;
        }
        return best;
    }

    static synchronized void markWarm(Anchor a, int radius) {
        a.warmRadius = Math.max(a.warmRadius, radius);
        save();
        CoffeesAeroAuth.LOGGER.info("[Rtp] Anchor {} {} generated to radius {} — {} usable, {} hardened, of {}.",
            a.x, a.z, a.warmRadius, countUsable(), countHardened(), anchors.size());
    }

    /**
     * A live /rtp proved this anchor is not as generated as the file claimed — send it back to the
     * warmer and stop handing it out. Re-walking is cheap for whatever IS on disk, so the only cost
     * of being wrong is one idle-window pass.
     */
    static synchronized void demote(Anchor a, int readyChunks, int totalChunks, long elapsedSec) {
        CoffeesAeroAuth.LOGGER.warn(
            "[Rtp] Anchor {} {} claimed radius {} but only {}/{} chunks were ready after {}s — "
            + "marked cold, it will be re-generated to radius {} in the next idle window.",
            a.x, a.z, a.warmRadius, readyChunks, totalChunks, elapsedSec, warmTargetRadius());
        a.warmRadius = 0;
        save();
    }

    static synchronized void markUsed(Anchor a) {
        a.lastUsedMs = System.currentTimeMillis();
        save();
    }

    /** Admin/status readout: "12/30 usable, 8 hardened (target 30 @ r9)". */
    public static synchronized String status() {
        if (!enabled()) return "disabled";
        return countUsable() + "/" + anchors.size() + " usable, " + countHardened() + " hardened"
            + " (target " + configuredCount() + " @ r" + warmTargetRadius() + ")";
    }
}
