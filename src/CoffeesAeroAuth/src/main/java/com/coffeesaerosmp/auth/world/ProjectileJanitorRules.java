package com.coffeesaerosmp.auth.world;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * When a gun projectile is stale. Pure — no Minecraft types — so it is unit-tested without a server.
 *
 * <p>2026-10-11, S3: {@code ntgl:projectile} (Create Gunsmithing's bullet) was the #1 entity in almost every
 * lag spike all day, up to ~1,100 live bullets. The bullet removes itself once {@code tickCount >= life},
 * but (1) {@code tickCount} only advances while the entity TICKS, so a bullet that flies into a loaded but
 * non-ticking border chunk is frozen there forever, and (2) {@code tickCount} is not saved, so every chunk
 * reload gives every stored bullet a brand-new lifetime. Each live bullet also ray-traces every tick.
 */
public final class ProjectileJanitorRules {

    private ProjectileJanitorRules() {}

    /** Config list → lower-case ids; blanks dropped. */
    public static Set<String> types(List<? extends String> configured) {
        Set<String> out = new HashSet<>();
        if (configured == null) return out;
        for (String s : configured) {
            if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** A bullet read back from a saved chunk is stale by definition: no real shot lasts across a chunk unload. */
    public static boolean dropOnLoad(boolean enabled, boolean listedType, boolean loadedFromDisk) {
        return enabled && listedType && loadedFromDisk;
    }

    /**
     * Remove when it has existed longer than {@code maxTicks}, counted from when the janitor FIRST SAW it
     * (server ticks, so a frozen bullet still ages) or by its own tickCount, whichever is older.
     */
    public static boolean stale(long nowTick, long firstSeenTick, int entityTickCount, long maxTicks) {
        long seenFor = Math.max(0, nowTick - firstSeenTick);
        return Math.max(seenFor, entityTickCount) > maxTicks;
    }
}
