package com.coffeesaerosmp.core.wardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The Wardrobe payload, parsed. Schema v1 (see CoffeesAeroAuth {@code WardrobeSync.build}). Unknown fields are
 * ignored and missing ones default, so the server can add fields without breaking older clients.
 */
public final class WardrobeData {

    public enum Cat { TRAILS, CONTRAILS, WINGS, AURAS, COLOURS, GRADIENTS, EXTRAS }

    /** Particle settings for a preview. rgb/rgb2 = -1 when unused. */
    public record Fx(String particle, int rgb, int rgb2, float scale, int count, float spread, int every) {}

    public record Item(String id, String label, String blurb, Cat cat, int price, String rank, boolean owned,
                       boolean available, boolean equipped, String icon, Fx fx, int[] nameRgb) {
        public boolean forSale() { return price >= 0; }
        public boolean particle() { return cat == Cat.TRAILS || cat == Cat.CONTRAILS; }
        /** Can be tried on in the world for a few seconds (trails, contrails, wings, auras). */
        public boolean tryable() { return particle() || cat == Cat.WINGS || cat == Cat.AURAS; }
    }

    public int version;
    public boolean open;
    public String name = "Player";
    public String rank = "NONE", rankLabel = "", rankBadge = "";
    public int balance = -1;
    public String storeUrl = "";
    public String eqTrail, eqContrail, eqNameStyle, eqWings, eqAura;
    public boolean eqBold;
    public int[] nameRgb = new int[0];
    public final List<Item> items = new ArrayList<>();

    public static WardrobeData parse(String json) {
        JsonObject r = JsonParser.parseString(json).getAsJsonObject();
        WardrobeData d = new WardrobeData();
        d.version = num(r, "v", 1);
        d.open = bool(r, "open", true);
        d.name = str(r, "name", "Player");
        d.rank = str(r, "rank", "NONE");
        d.rankLabel = str(r, "rankLabel", "");
        d.rankBadge = str(r, "rankBadge", "");
        d.balance = num(r, "balance", -1);
        d.storeUrl = str(r, "storeUrl", "");
        if (r.has("equipped") && r.get("equipped").isJsonObject()) {
            JsonObject e = r.getAsJsonObject("equipped");
            d.eqTrail = str(e, "trail", null);
            d.eqContrail = str(e, "contrail", null);
            d.eqWings = str(e, "wings", null);
            d.eqAura = str(e, "aura", null);
            d.eqNameStyle = str(e, "nameStyle", null);
            d.eqBold = bool(e, "bold", false);
        }
        d.nameRgb = ints(r.get("nameRgb"));
        if (r.has("items")) for (JsonElement el : r.getAsJsonArray("items")) {
            JsonObject o = el.getAsJsonObject();
            Cat cat;
            try { cat = Cat.valueOf(str(o, "cat", "EXTRAS")); } catch (IllegalArgumentException ex) { continue; }
            Fx fx = null;
            if (o.has("fx") && o.get("fx").isJsonObject()) {
                JsonObject f = o.getAsJsonObject("fx");
                fx = new Fx(str(f, "particle", "minecraft:cloud"), num(f, "rgb", -1), num(f, "rgb2", -1),
                    (float) dbl(f, "scale", 1), num(f, "count", 2), (float) dbl(f, "spread", 0.2), Math.max(1, num(f, "every", 4)));
            }
            d.items.add(new Item(str(o, "id", "?"), str(o, "label", "?"), str(o, "blurb", null), cat,
                num(o, "price", -1), str(o, "rank", null), bool(o, "owned", false), bool(o, "available", false),
                bool(o, "equipped", false), str(o, "icon", "minecraft:paper"), fx, ints(o.get("nameRgb"))));
        }
        return d;
    }

    public List<Item> in(Cat cat) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.cat() == cat) out.add(i);
        return out;
    }

    public List<Item> owned() {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.available()) out.add(i);
        return out;
    }

    public Item byId(String id) {
        if (id == null) return null;
        for (Item i : items) if (i.id().equals(id)) return i;
        return null;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static int num(JsonObject o, String k, int def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    private static double dbl(JsonObject o, String k, double def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def; } catch (Exception e) { return def; }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def; } catch (Exception e) { return def; }
    }

    private static int[] ints(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new int[0];
        JsonArray a = e.getAsJsonArray();
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }
}
