package com.coffeesaerosmp.auth.world;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads Sable's live sub-level containers and turns them into a {@link ShipCensus}.
 *
 * <h2>Reflection, and why this one is safe</h2>
 * There is no compile dependency on Sable — same reason as {@code ShipNameCommand}. But unlike that
 * class, this one does not have to guess: everything it calls is <b>public API</b> in
 * {@code dev.ryanhcode.sable.api.sublevel}, verified against the shipped jar (Sable 2.0.3):
 *
 * <pre>
 *   SubLevelContainer.getContainer(ServerLevel) -&gt; ServerSubLevelContainer   [public static]
 *   ServerSubLevelContainer.getAllSubLevels()   -&gt; List&lt;ServerSubLevel&gt;
 *   ServerSubLevelContainer.collectForceLoadedSubLevels() -&gt; Collection&lt;ServerSubLevel&gt;
 *   ServerSubLevel.getTrackingPlayers() -&gt; Collection&lt;UUID&gt;
 *   SubLevel.getName() / isRemoved()
 * </pre>
 *
 * {@code getContainer} is overloaded three ways ({@code Level}, {@code ServerLevel},
 * {@code ClientLevel}), so it is looked up by EXACT parameter type rather than by arity — an
 * arity-only search would be a coin flip between the server and client overloads, and the client one
 * does not exist on a dedicated server.
 *
 * <p>🔑 <b>Read-only.</b> Nothing here calls {@code removeSubLevel}, which destroys blocks. The whole
 * point is to decide whether deletion is worth a human's attention, not to perform it.
 *
 * <p>Every lookup failure degrades to "unknown" and logs once. A census command must never be able
 * to take down a tick — and after a Sable upgrade, a renamed method should produce an honest empty
 * report rather than a crash ([[never-remove-a-content-mod-live]] applies to the mod itself; this
 * applies to our read of it).
 */
public final class SableShips {

    private SableShips() {}

    private static final String CONTAINER_CLASS = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";

    /** Resolved once per server run; null means "Sable's API is not where we expect it". */
    private static Method getContainer;
    private static Method getAllSubLevels;
    private static Method collectForceLoaded;
    private static boolean resolved;
    private static boolean warned;

    /**
     * Walks every loaded dimension. Call from the server thread — it reads live container state and
     * makes no attempt to synchronise.
     */
    public static ShipCensus census(MinecraftServer server) {
        List<ShipCensus.LevelCensus> levels = new ArrayList<>();
        if (server == null || !resolve()) return new ShipCensus(levels, claimedCount(server));

        for (ServerLevel level : server.getAllLevels()) {
            try {
                Object container = getContainer.invoke(null, level);
                if (container == null) continue;

                Object all = getAllSubLevels.invoke(container);
                if (!(all instanceof Collection<?> subLevels) || subLevels.isEmpty()) continue;

                int forceLoaded = 0;
                Object forced = collectForceLoaded.invoke(container);
                if (forced instanceof Collection<?> f) forceLoaded = f.size();

                int live = 0, untracked = 0, named = 0;
                for (Object sub : subLevels) {
                    if (sub == null) continue;
                    resolveSubLevel(sub);
                    if (isRemoved(sub)) continue;                  // pending removal isn't ticked
                    live++;
                    if (trackingPlayers(sub) == 0) untracked++;
                    if (!ShipCensus.isDefaultName(nameOf(sub))) named++;
                }
                if (live == 0) continue;

                levels.add(new ShipCensus.LevelCensus(
                    level.dimension().location().toString(), live, forceLoaded, untracked, named));
            } catch (Throwable t) {
                warnOnce("reading " + level.dimension().location() + " failed", t);
            }
        }
        return new ShipCensus(levels, claimedCount(server));
    }

    // ── Sable API resolution ──────────────────────────────────────────────────

    private static boolean resolve() {
        if (resolved) return getContainer != null;
        resolved = true;
        try {
            Class<?> container = Class.forName(CONTAINER_CLASS);
            // EXACT type: getContainer is overloaded on Level / ServerLevel / ClientLevel.
            getContainer = container.getMethod("getContainer", ServerLevel.class);
            Class<?> serverContainer = getContainer.getReturnType();
            getAllSubLevels    = serverContainer.getMethod("getAllSubLevels");
            collectForceLoaded = serverContainer.getMethod("collectForceLoadedSubLevels");
        } catch (Throwable t) {
            warnOnce("Sable sub-level API not found — census unavailable", t);
            getContainer = null;
        }
        return getContainer != null;
    }

    /**
     * Per-sub-level accessors, cached on first use. Every element of every container is the same
     * concrete {@code ServerSubLevel}, so one lookup serves them all — worth doing because a server
     * with hundreds of hulls would otherwise pay three {@code getMethod} lookups per hull per run.
     */
    private static Method isRemovedM, getNameM, trackingPlayersM;
    private static Class<?> subLevelType;

    private static void resolveSubLevel(Object subLevel) {
        Class<?> type = subLevel.getClass();
        if (type == subLevelType) return;
        subLevelType = type;
        isRemovedM = lookup(type, "isRemoved");
        getNameM = lookup(type, "getName");
        trackingPlayersM = lookup(type, "getTrackingPlayers");
    }

    private static Method lookup(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (Throwable t) {
            warnOnce("ServerSubLevel." + name + "() missing", t);
            return null;
        }
    }

    private static boolean isRemoved(Object subLevel) {
        try {
            return isRemovedM != null && Boolean.TRUE.equals(isRemovedM.invoke(subLevel));
        } catch (Throwable t) { return false; }
    }

    private static String nameOf(Object subLevel) {
        try {
            Object v = getNameM != null ? getNameM.invoke(subLevel) : null;
            return v != null ? v.toString() : null;
        } catch (Throwable t) { return null; }
    }

    private static int trackingPlayers(Object subLevel) {
        try {
            Object v = trackingPlayersM != null ? trackingPlayersM.invoke(subLevel) : null;
            return v instanceof Collection<?> c ? c.size() : 0;
        } catch (Throwable t) { return 0; }
    }

    // ── AeroClaims claim count ────────────────────────────────────────────────

    /**
     * Counts entries in {@code <world>/aeroclaims/claimed_sublevels.json}, or -1 if it cannot be
     * read. Read straight off disk rather than through AeroClaims' classes: the file is the record
     * of intent, it is stable across AeroClaims versions, and a missing file is a clean -1 instead
     * of a NoSuchMethodError.
     */
    private static int claimedCount(MinecraftServer server) {
        if (server == null) return -1;
        try {
            Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("aeroclaims").resolve("claimed_sublevels.json");
            if (!Files.isRegularFile(file)) return -1;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                if (root.isJsonArray())  return root.getAsJsonArray().size();
                if (root.isJsonObject()) return root.getAsJsonObject().size();
                return -1;
            }
        } catch (Throwable t) {
            warnOnce("claimed_sublevels.json unreadable", t);
            return -1;
        }
    }

    private static void warnOnce(String what, Throwable t) {
        if (warned) return;
        warned = true;
        CoffeesAeroAuth.LOGGER.warn("[Ships] {}: {}", what, t.toString());
    }
}
