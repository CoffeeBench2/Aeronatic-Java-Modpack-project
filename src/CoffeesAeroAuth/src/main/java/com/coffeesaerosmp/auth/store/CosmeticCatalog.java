package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.Entitlements.Capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every particle cosmetic on the server: walking trails and airship contrails. Pure — no {@code net.minecraft} —
 * so the limits and the rank mapping are unit-tested.
 *
 * <p>Plan: Obaa {@code planning/cosmetics-plan-2026-10-12.md} — themed catalog from the theme proposal, filtered
 * by the compliance proposal (no particle that fakes a game signal: totem, crit, omens, explosions, flashes;
 * contrails thin enough never to hide a ship from cannon fire), limits from the performance proposal.
 *
 * <p>🔴 Ids are a STORED DATA FORMAT ({@code cosmetics_owned.cosmetic_id}, {@code cosmetic_selection.trail}):
 * add new ones, never rename one.
 *
 * <p>{@code requires == null} = not part of any rank: a Beans / event item. Ops can still preview it.
 */
public final class CosmeticCatalog {

    public enum Type { TRAIL, CONTRAIL }

    /**
     * How a cosmetic looks. {@code particleId} is a vanilla particle id resolved at server start; an unknown id
     * disables that entry. {@code rgb >= 0} makes it a coloured {@code minecraft:dust}, and with {@code rgb2 >= 0}
     * a {@code minecraft:dust_color_transition} from rgb to rgb2.
     */
    public record Fx(String particleId, int rgb, int rgb2, float scale, int count, float spread, float speed,
                     int everyTicks, double yOffset) {}

    public record Cosmetic(String id, Type type, Capability requires, String label, String blurb, Fx fx) {}

    /** Hard limits — checked by the unit test, so a hand edit cannot ship a laggy or blinding entry. */
    public static final int MAX_COUNT = 4;
    public static final int MIN_TRAIL_TICKS = 2;
    public static final int MIN_CONTRAIL_TICKS = 4;

    /** Particles that mimic a real game signal or are big/long-lived enough to hide things. Never allowed. */
    public static final List<String> BANNED = List.of(
        "minecraft:totem_of_undying", "minecraft:crit", "minecraft:enchanted_hit", "minecraft:explosion",
        "minecraft:explosion_emitter", "minecraft:flash", "minecraft:sonic_boom", "minecraft:elder_guardian",
        "minecraft:trial_omen", "minecraft:raid_omen", "minecraft:ominous_spawning", "minecraft:damage_indicator",
        "minecraft:campfire_signal_smoke", "minecraft:campfire_cosy_smoke", "minecraft:angry_villager");

    private static final Map<String, Cosmetic> ALL = new LinkedHashMap<>();

    private static void add(String id, Type type, Capability req, String label, String blurb, Fx fx) {
        ALL.put(id, new Cosmetic(id, type, req, label, blurb, fx));
    }

    private static Fx p(String particle, int count, float spread, float speed, int every, double y) {
        return new Fx(particle, -1, -1, 1f, count, spread, speed, every, y);
    }

    private static Fx dust(int rgb, float scale, int count, float spread, int every, double y) {
        return new Fx("minecraft:dust", rgb, -1, scale, count, spread, 0f, every, y);
    }

    private static Fx fade(int from, int to, float scale, int count, float spread, int every, double y) {
        return new Fx("minecraft:dust_color_transition", from, to, scale, count, spread, 0f, every, y);
    }

    static {
        // ── Walking trails (on foot or elytra; never while standing still) ─────────────────────────────
        add("trail:cloudwalk", Type.TRAIL, Capability.TRAIL_BASIC, "Cloud Walker",
            "Little clouds at your feet. You belong in the sky.", p("minecraft:cloud", 2, 0.15f, 0.0f, 4, 0.1));
        add("trail:brass", Type.TRAIL, Capability.TRAIL, "Brass Sparks",
            "Copper-and-brass glints, an engineer's walk.", p("minecraft:wax_off", 2, 0.2f, 0.0f, 4, 0.2));
        add("trail:clockwork", Type.TRAIL, Capability.TRAIL, "Clockwork",
            "Copper scrape sparkles, the Create palette.", p("minecraft:scrape", 2, 0.2f, 0.0f, 4, 0.2));
        add("trail:espresso", Type.TRAIL, Capability.TRAIL, "Spilled Beans",
            "A trail of coffee-brown specks. Coffee Beans, literally.", dust(0x4B2E1E, 0.9f, 2, 0.15f, 4, 0.1));
        add("trail:kettle", Type.TRAIL, null, "Kettle",
            "A wisp of fresh-brew steam.", p("minecraft:white_smoke", 1, 0.1f, 0.01f, 6, 0.3));
        add("trail:petal", Type.TRAIL, null, "Petal Path",
            "Cherry petals drifting behind you.", p("minecraft:cherry_leaves", 2, 0.3f, 0.0f, 4, 0.6));
        add("trail:ember", Type.TRAIL, null, "Ember Step",
            "Tiny flames, like a boiler's firebox.", p("minecraft:small_flame", 1, 0.12f, 0.0f, 4, 0.1));
        add("trail:snow", Type.TRAIL, null, "Snowfall",
            "Snowflakes — a December event trail.", p("minecraft:snowflake", 2, 0.25f, 0.0f, 4, 0.5));

        // ── Airship contrails (behind the ship you are on, only while it flies faster than ~5 m/s) ────
        add("contrail:vapour", Type.CONTRAIL, Capability.CONTRAIL, "Vapour Trail",
            "The classic sky-ship line: soft white cloud behind your hull.", p("minecraft:cloud", 3, 0.25f, 0.0f, 4, 0));
        add("contrail:steam", Type.CONTRAIL, Capability.CONTRAIL, "Boiler Steam",
            "White steam puffs from a hard-working engine.", p("minecraft:white_smoke", 3, 0.3f, 0.01f, 4, 0));
        add("contrail:espresso", Type.CONTRAIL, null, "Espresso Wake",
            "A coffee-to-crema wake. Only on Coffee's AeroSMP.", fade(0x6F4E37, 0xE8D8C4, 1.5f, 3, 0.3f, 4, 0));
        add("contrail:sakura", Type.CONTRAIL, null, "Sakura Drift",
            "Cherry petals scattering behind your ship.", p("minecraft:cherry_leaves", 3, 0.4f, 0.0f, 4, 0));
        add("contrail:aurora", Type.CONTRAIL, Capability.CONTRAIL_EXCLUSIVE, "Aurora Ribbon",
            "Aqua into violet. Admiral only, never sold.", fade(0x2EE6D6, 0x8A4FFF, 1.4f, 3, 0.25f, 4, 0));
    }

    private CosmeticCatalog() {}

    public static Optional<Cosmetic> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(ALL.get(id.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    public static List<Cosmetic> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
    }

    public static List<Cosmetic> all(Type type) {
        List<Cosmetic> out = new ArrayList<>();
        for (Cosmetic c : ALL.values()) if (c.type() == type) out.add(c);
        return out;
    }
}
