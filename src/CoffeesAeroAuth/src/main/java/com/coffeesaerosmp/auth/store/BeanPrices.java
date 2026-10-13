package com.coffeesaerosmp.auth.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * What Coffee Beans can buy, and for how much. Pure, so the "never for sale" rules are unit-tested.
 *
 * <p>Prices follow the approved store spec (Obaa {@code planning/store-spec.md}, "Coffee Beans"): solid colour
 * 100, bold 100, two-colour gradient 300, join + leave messages 200, trails 150–300. Contrails were "later" in
 * the spec and are priced here at the top of the range because they are the most visible thing on the server.
 *
 * <p>🔴 Never for sale, by rule rather than by omission:
 * <ul>
 *   <li>anything a rank grants ({@code requires != null}) — the subscription has to keep a reason to exist;</li>
 *   <li>event items (Snowfall) — earned, never bought;</li>
 *   <li>staff colours — the anti-spoof rule; three-colour gradients — Admiral only.</li>
 * </ul>
 */
public final class BeanPrices {

    public enum Group { TRAIL, CONTRAIL, WINGS, AURA, NAME }

    public record Item(String id, String label, int price, Group group) {}

    public static final int COLOUR = 100;
    public static final int BOLD = 100;
    public static final int GRADIENT = 300;
    public static final int MESSAGES = 200;

    /** Particle prices. Only {@code requires == null} catalog entries may appear here (unit-tested). */
    private static final Map<String, Integer> PARTICLES = Map.ofEntries(
        Map.entry("trail:kettle", 150),
        Map.entry("trail:ember", 150),
        Map.entry("trail:petal", 200),
        Map.entry("trail:soulfire", 200),
        Map.entry("trail:notes", 200),
        Map.entry("trail:bubbles", 200),
        Map.entry("trail:livewire", 250),
        Map.entry("trail:starfall", 250),
        Map.entry("trail:rainbow", 300),
        Map.entry("trail:rosequartz", 200),
        Map.entry("trail:honey", 250),
        Map.entry("trail:chocolate", 250),
        Map.entry("contrail:levitite", 550),
        Map.entry("wings:windmill", 750),
        Map.entry("wings:glider", 750),
        Map.entry("wings:rotors", 850),
        Map.entry("wings:aeroplane", 900),
        Map.entry("aura:propeller", 350),
        Map.entry("contrail:sakura", 450),
        Map.entry("contrail:espresso", 500),
        Map.entry("contrail:starlight", 500),
        Map.entry("contrail:sparkler", 550),
        Map.entry("contrail:rainbow", 600),
        // 1.14.0. Wings are the biggest thing on a player, so they sit at the top of the range.
        Map.entry("wings:monarch", 700),
        Map.entry("wings:seraph", 800),
        Map.entry("wings:wyvern", 800),
        Map.entry("wings:phoenix", 900),
        Map.entry("aura:raincloud", 300),
        Map.entry("aura:enchanted", 350),
        Map.entry("aura:spectrum", 400));

    private BeanPrices() {}

    public static OptionalInt price(String id) {
        if (id == null) return OptionalInt.empty();
        String key = id.trim().toLowerCase(Locale.ROOT);
        // a catalog id that isn't offered right now (parked wings, parked contrails) is never for sale
        if (Entitlements.isCatalogId(key) && !CosmeticCatalog.offered(key)) return OptionalInt.empty();
        Integer p = PARTICLES.get(key);
        if (p != null) return OptionalInt.of(p);
        if (key.equals(Entitlements.BOLD_ID)) return OptionalInt.of(BOLD);
        // Join and leave lines share one entitlement (Entitlements.mayUse checks JOIN_MESSAGE_ID for both).
        if (key.equals(Entitlements.JOIN_MESSAGE_ID)) return OptionalInt.of(MESSAGES);
        if (key.startsWith(Entitlements.COLOUR_PREFIX)) {
            String c = key.substring(Entitlements.COLOUR_PREFIX.length());
            return Palette.isColour(c) && !Palette.isStaffReserved(c) ? OptionalInt.of(COLOUR) : OptionalInt.empty();
        }
        if (key.startsWith(Entitlements.GRADIENT_PREFIX)) {
            String g = key.substring(Entitlements.GRADIENT_PREFIX.length());
            if (Palette.gradientStops(g) != 2) return OptionalInt.empty();
            for (String c : Palette.gradient(g)) if (Palette.isStaffReserved(c)) return OptionalInt.empty();
            return OptionalInt.of(GRADIENT);
        }
        return OptionalInt.empty();
    }

    public static boolean forSale(String id) { return price(id).isPresent(); }

    /** Everything for sale, in shop order: trails, contrails, then name items. */
    public static List<Item> catalogue() {
        List<Item> out = new ArrayList<>();
        for (var t : CosmeticCatalog.Type.values()) {
            for (var c : CosmeticCatalog.all(t)) {
                OptionalInt p = price(c.id());
                if (p.isPresent()) out.add(new Item(c.id(), c.label(), p.getAsInt(), Group.valueOf(t.name())));
            }
        }
        out.add(new Item(Entitlements.JOIN_MESSAGE_ID, "Join & leave messages", MESSAGES, Group.NAME));
        out.add(new Item(Entitlements.BOLD_ID, "Bold name", BOLD, Group.NAME));
        for (String c : Palette.allColours()) {
            String id = Entitlements.COLOUR_PREFIX + c;
            if (forSale(id)) out.add(new Item(id, "Name colour: " + pretty(c), COLOUR, Group.NAME));
        }
        for (String g : Palette.allGradients()) {
            String id = Entitlements.GRADIENT_PREFIX + g;
            if (forSale(id)) out.add(new Item(id, "Gradient: " + pretty(g), GRADIENT, Group.NAME));
        }
        return Collections.unmodifiableList(out);
    }

    static String pretty(String raw) {
        String s = raw.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
