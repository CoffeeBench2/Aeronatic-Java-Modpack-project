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

    public enum Group { TRAIL, CONTRAIL, NAME }

    public record Item(String id, String label, int price, Group group) {}

    public static final int COLOUR = 100;
    public static final int BOLD = 100;
    public static final int GRADIENT = 300;
    public static final int MESSAGES = 200;

    /** Particle prices. Only {@code requires == null} catalog entries may appear here (unit-tested). */
    private static final Map<String, Integer> PARTICLES = Map.of(
        "trail:kettle", 150,
        "trail:ember", 150,
        "trail:petal", 200,
        "contrail:sakura", 450,
        "contrail:espresso", 500);

    private BeanPrices() {}

    public static OptionalInt price(String id) {
        if (id == null) return OptionalInt.empty();
        String key = id.trim().toLowerCase(Locale.ROOT);
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
        for (var t : new CosmeticCatalog.Type[]{CosmeticCatalog.Type.TRAIL, CosmeticCatalog.Type.CONTRAIL}) {
            for (var c : CosmeticCatalog.all(t)) {
                OptionalInt p = price(c.id());
                if (p.isPresent()) out.add(new Item(c.id(), c.label(), p.getAsInt(),
                    t == CosmeticCatalog.Type.TRAIL ? Group.TRAIL : Group.CONTRAIL));
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
