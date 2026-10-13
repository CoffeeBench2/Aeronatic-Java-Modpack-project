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

    /**
     * The four slots. Trails and contrails also have a server-drawn fallback ({@link Fx}); wings and auras exist
     * only on AeroCore clients (1.14.0), which draw every effect themselves from the synced selection.
     */
    public enum Type {
        TRAIL(Entitlements.TRAIL_PREFIX), CONTRAIL(Entitlements.CONTRAIL_PREFIX),
        WINGS(Entitlements.WINGS_PREFIX), AURA(Entitlements.AURA_PREFIX);

        public final String prefix;
        Type(String prefix) { this.prefix = prefix; }

        /** The slot an id belongs to, or null. */
        public static Type of(String id) {
            if (id == null) return null;
            for (Type t : values()) if (id.startsWith(t.prefix)) return t;
            return null;
        }
    }

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

    /** Wings are built but not offered yet. While false, no wings exist anywhere: shop, Wardrobe, commands, sync. */
    public static final boolean WINGS_ON = false;

    /**
     * Airship contrails are parked for a later update (owner 2026-10-13: "keep contrail airship for a later update").
     * Unlike wings they stay IN the catalog, because players may already own contrail ids and dropping the entries
     * would orphan those. While false, {@link #all(Type)} offers none, so no shop page, Wardrobe tab, command or sync
     * shows them. {@link #get} still resolves them; gate anything player-facing on {@link #offered}.
     */
    public static final boolean CONTRAILS_ON = false;

    /** False for a whole type that's parked right now (contrails while {@link #CONTRAILS_ON} is false). */
    public static boolean offered(Type type) {
        return type != Type.CONTRAIL || CONTRAILS_ON;
    }

    /** True if {@code id} is in the catalog AND its type is offered right now. */
    public static boolean offered(String id) {
        return get(id).map(c -> offered(c.type())).orElse(false);
    }

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

        // ── 1.14.0: drawn by AeroCore on every client; the Fx is only the server's fallback ─────────────
        add("trail:rainbow", Type.TRAIL, null, "Rainbow Road",
            "Every colour of the sky, one footstep at a time.", fade(0xFF4D4D, 0x4D7BFF, 1.0f, 2, 0.2f, 4, 0.1));
        add("trail:starfall", Type.TRAIL, null, "Starfall",
            "Twinkling stars that drift down where you walked.", p("minecraft:end_rod", 1, 0.2f, 0.0f, 4, 0.4));
        add("trail:livewire", Type.TRAIL, null, "Live Wire",
            "Crackling sparks, fresh off a Create dynamo.", p("minecraft:electric_spark", 2, 0.2f, 0.0f, 4, 0.2));
        add("trail:soulfire", Type.TRAIL, null, "Soul Lantern",
            "Blue soul-fire flickers at your heels.", p("minecraft:soul_fire_flame", 1, 0.12f, 0.0f, 4, 0.1));
        add("trail:notes", Type.TRAIL, null, "Gramophone",
            "Music notes bounce along behind you.", p("minecraft:note", 1, 0.2f, 0.0f, 6, 1.0));
        add("trail:fireflies", Type.TRAIL, Capability.TRAIL, "Fireflies",
            "Soft glowing motes that wander after you.", p("minecraft:glow", 1, 0.3f, 0.0f, 6, 0.8));
        add("trail:rosequartz", Type.TRAIL, null, "Rose Quartz",
            "Polished pink crystal glints. Straight out of the mixer.", dust(0xF27BA8, 0.8f, 2, 0.2f, 4, 0.3));
        add("trail:honey", Type.TRAIL, null, "Honey Drip",
            "Create's own honey, dripping off you as you go.", p("minecraft:dripping_honey", 1, 0.2f, 0.0f, 6, 1.0));
        add("trail:chocolate", Type.TRAIL, null, "Chocolate River",
            "Create's chocolate, straight from the mixer, dripping in your wake.", dust(0x5A3018, 1.2f, 2, 0.2f, 4, 0.1));
        add("trail:bubbles", Type.TRAIL, null, "Bubble Wake",
            "Bubbles that pop as they rise. Splendid in the rain.", p("minecraft:bubble_pop", 2, 0.2f, 0.0f, 4, 0.3));

        add("contrail:rainbow", Type.CONTRAIL, null, "Rainbow Wake",
            "Seven bands of colour streaming off your propellers.", fade(0xFF4D4D, 0x8A4FFF, 1.4f, 3, 0.25f, 4, 0));
        add("contrail:starlight", Type.CONTRAIL, null, "Starlight",
            "A river of stars behind your ship.", p("minecraft:end_rod", 2, 0.25f, 0.0f, 4, 0));
        add("contrail:storm", Type.CONTRAIL, Capability.CONTRAIL, "Stormfront",
            "Dark cloud and crackling sparks. Fly ominously.", p("minecraft:smoke", 2, 0.25f, 0.0f, 4, 0));
        add("contrail:levitite", Type.CONTRAIL, null, "Levitite Shimmer",
            "Pearlescent levitite dust, the stuff that keeps you up there.", fade(0xE8F4FF, 0xC9A8FF, 1.3f, 3, 0.25f, 4, 0));
        add("contrail:sparkler", Type.CONTRAIL, null, "Sparkler",
            "Golden sparks, like a firework that never ends.", p("minecraft:wax_off", 3, 0.25f, 0.0f, 4, 0));

        // ── Wings: parked (owner 2026-10-13: "remove wings for now, that's for another day"). The entries, AeroCore's
        // renderer and the Create-built models are all kept; flip WINGS_ON to bring them back.
        if (WINGS_ON) {
            // ── Wings: a 3D pair on your back that flaps faster when you fly ──────────────────────────────────
            // (Wings are wings. Mojang's usage guidelines forbid selling capes or anything that acts like one,
            // so nothing here is ever a cloth panel hanging off the shoulders.)
            add("wings:ornithopter", Type.WINGS, Capability.WINGS, "Ornithopter",
                "Brass ribs, canvas sails and a spinning cog. Create-built flight.", p("minecraft:wax_off", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:windmill", Type.WINGS, null, "Windmill Sails",
                "Four Create sails that turn on your back like a windmill.", p("minecraft:cloud", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:aeroplane", Type.WINGS, null, "Aeroplane Wings",
                "Create Propulsion wing panels with ailerons that bank into your turns.", p("minecraft:cloud", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:rotors", Type.WINGS, null, "Twin Rotors",
                "Two counter-rotating Create Propulsion rotors on shoulder masts.", p("minecraft:cloud", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:glider", Type.WINGS, null, "Sky Glider",
                "Create Wings Plus angled sails that spread into a delta when you fly.", p("minecraft:cloud", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:seraph", Type.WINGS, null, "Seraph",
                "White feathered wings with a soft golden glow.", p("minecraft:end_rod", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:phoenix", Type.WINGS, null, "Phoenix",
                "Wings of fire that shed embers as they beat.", p("minecraft:flame", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:wyvern", Type.WINGS, null, "Wyvern",
                "Leathery dragon wings. Pairs well with a menacing stare.", p("minecraft:smoke", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:monarch", Type.WINGS, null, "Monarch",
                "Bright butterfly wings that flutter as you move.", p("minecraft:cherry_leaves", 1, 0.2f, 0.0f, 8, 1.2));
            add("wings:prism", Type.WINGS, Capability.WINGS_EXCLUSIVE, "Prism",
                "Crystal wings that shift through the rainbow. Admiral only, never sold.", fade(0x2EE6D6, 0xFF6AD5, 1.0f, 1, 0.2f, 8, 1.2));
            add("wings:frost", Type.WINGS, null, "Frostwing",
                "Icy crystal wings, a December event prize.", p("minecraft:snowflake", 1, 0.2f, 0.0f, 8, 1.2));
        }

        // ── Auras: something that is always with you ─────────────────────────────────────────────────────
        add("aura:updraft", Type.AURA, Capability.AURA_BASIC, "Updraft",
            "A gentle spiral of wind that rises around you.", p("minecraft:cloud", 1, 0.3f, 0.0f, 8, 0.2));
        add("aura:halo", Type.AURA, Capability.AURA, "Halo",
            "A glowing golden ring above your head.", p("minecraft:wax_off", 1, 0.15f, 0.0f, 8, 2.2));
        add("aura:orbit", Type.AURA, Capability.AURA, "Clockwork Orbit",
            "Little brass cogs that orbit you, turning as they go.", p("minecraft:wax_off", 1, 0.3f, 0.0f, 8, 1.0));
        add("aura:propeller", Type.AURA, null, "Propeller Cap",
            "A little Aeronautics propeller spinning over your head. Lift not included.", p("minecraft:cloud", 1, 0.15f, 0.0f, 8, 2.3));
        add("aura:spectrum", Type.AURA, null, "Spectrum Ring",
            "A ring of every colour turning slowly at your waist.", fade(0xFF4D4D, 0x4D7BFF, 1.0f, 1, 0.3f, 8, 1.0));
        add("aura:raincloud", Type.AURA, null, "Personal Raincloud",
            "Your very own little cloud. It drizzles. Only on you.", p("minecraft:dripping_water", 1, 0.3f, 0.0f, 8, 2.4));
        add("aura:enchanted", Type.AURA, null, "Spellbound",
            "Enchanting glyphs swirl up around you.", p("minecraft:enchant", 2, 0.4f, 0.0f, 8, 1.0));
        add("aura:snowglobe", Type.AURA, null, "Snow Globe",
            "Gentle snow that falls only around you. December event.", p("minecraft:snowflake", 1, 0.4f, 0.0f, 8, 2.0));
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
        if (!offered(type)) return out;
        for (Cosmetic c : ALL.values()) if (c.type() == type) out.add(c);
        return out;
    }
}
