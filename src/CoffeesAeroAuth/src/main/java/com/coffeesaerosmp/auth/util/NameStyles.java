package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player name styling (2026-07-20, the {@code /namecolor} command) — the generalization the
 * {@link RainbowText} javadoc promised. A player's DISPLAY name in chat and the tab list can carry:
 * a named MC color, an arbitrary hex color, format flags (bold / italic / underline /
 * strikethrough), the §k "magic" scramble (enchantment-table glyphs — the whole name renders as
 * cycling random characters), or the animated RGB rainbow. Named per-player presets let a player
 * save a look and switch back later ({@code /namecolor preset save|load}).
 *
 * <p>Display layer ONLY (chat components + tab UPDATE_DISPLAY_NAME) — never the GameProfile (16-char
 * packet limit, see 1.6.10) and not the nametag above the head (scoreboard team color, can't carry
 * per-char styles). Stored per player UUID in {@code name_styles.json}, saved via {@link AsyncIo}
 * (never sync disk on the server thread — the 07-18 freeze-vector rule).</p>
 *
 * <p>Fallbacks when a player has NO stored entry: the owner (MrCoffeeBench) is seeded once with his
 * requested look — §c red + §k scramble — plus a saved {@code rgb} preset holding the old rainbow
 * (so {@code /namecolor preset load rgb} restores it); any other name listed in the legacy
 * {@code rgbNames} config renders rainbow exactly as before.</p>
 */
public final class NameStyles {

    /** One player's live style. {@code color} = MC color name or {@code #RRGGBB}; null = default. */
    public static final class NameStyle {
        public boolean rainbow;
        public String  color;
        public boolean bold, italic, underline, strikethrough, obfuscated;

        NameStyle copy() {
            NameStyle s = new NameStyle();
            s.rainbow = rainbow; s.color = color; s.bold = bold; s.italic = italic;
            s.underline = underline; s.strikethrough = strikethrough; s.obfuscated = obfuscated;
            return s;
        }
        boolean isPlain() {
            return !rainbow && color == null && !bold && !italic
                && !underline && !strikethrough && !obfuscated;
        }
    }

    private static final class Entry {
        NameStyle live = new NameStyle();
        final Map<String, NameStyle> presets = new ConcurrentHashMap<>();
    }

    private static final Map<UUID, Entry> STYLES = new ConcurrentHashMap<>();
    private static volatile Path file;

    /** Owner username seeded with red+scramble and the saved "rgb" rainbow preset on first sight. */
    private static final String OWNER_NAME = "mrcoffeebench";

    private NameStyles() {}

    public static void initialize(Path dataDir) {
        file = dataDir.resolve("name_styles.json");
        STYLES.clear();
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            o.entrySet().forEach(e -> {
                Entry entry = new Entry();
                JsonObject v = e.getValue().getAsJsonObject();
                entry.live = styleFromJson(v);
                if (v.has("presets")) {
                    v.getAsJsonObject("presets").entrySet().forEach(p ->
                        entry.presets.put(p.getKey(), styleFromJson(p.getValue().getAsJsonObject())));
                }
                STYLES.put(UUID.fromString(e.getKey()), entry);
            });
            CoffeesAeroAuth.LOGGER.info("[NameColor] Loaded {} name styles.", STYLES.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[NameColor] name_styles.json load failed: {}", e.getMessage());
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * The player's styled display name, or {@code null} when unstyled (caller renders its default).
     * Legacy {@code rgbNames} players without a stored entry keep their rainbow; the owner is
     * seeded on first lookup.
     */
    public static Component nameComponent(UUID uuid, String username, String displayName) {
        NameStyle s = liveStyle(uuid, username);
        if (s == null || s.isPlain()) return null;
        return render(s, displayName);
    }

    /** Renders {@code text} in the given style (also used for command previews). */
    public static Component render(NameStyle s, String text) {
        if (s.rainbow) return RainbowText.gradient(text);
        MutableComponent c = Component.literal(text);
        Style st = Style.EMPTY;
        TextColor tc = resolveColor(s.color);
        if (tc != null)      st = st.withColor(tc);
        if (s.bold)          st = st.withBold(true);
        if (s.italic)        st = st.withItalic(true);
        if (s.underline)     st = st.withUnderlined(true);
        if (s.strikethrough) st = st.withStrikethrough(true);
        if (s.obfuscated)    st = st.withObfuscated(true);
        c.setStyle(st);
        return c;
    }

    private static NameStyle liveStyle(UUID uuid, String username) {
        Entry e = STYLES.get(uuid);
        if (e != null) return e.live;
        if (username == null) return null;
        if (username.toLowerCase(Locale.ROOT).equals(OWNER_NAME)) return seedOwner(uuid);
        if (RainbowText.isEnabled(username)) {            // legacy config list, pre-/namecolor
            NameStyle s = new NameStyle();
            s.rainbow = true;
            return s;                                     // transient — not persisted
        }
        return null;
    }

    /** Owner's requested default: red scrambled name, rainbow kept one command away as "rgb". */
    private static NameStyle seedOwner(UUID uuid) {
        Entry e = STYLES.computeIfAbsent(uuid, k -> {
            Entry fresh = new Entry();
            fresh.live.color = "red";
            fresh.live.obfuscated = true;
            NameStyle rgb = new NameStyle();
            rgb.rainbow = true;
            fresh.presets.put("rgb", rgb);
            CoffeesAeroAuth.LOGGER.info("[NameColor] Seeded owner style (red scramble + rgb preset).");
            return fresh;
        });
        persist();
        return e.live;
    }

    // ── Command backend (each returns a user-facing error, or null on success) ─

    /** The 16 vanilla colors, tab-complete order. */
    public static java.util.List<String> colorNames() {
        return java.util.Arrays.stream(ChatFormatting.values())
            .filter(ChatFormatting::isColor).map(f -> f.getName().toLowerCase(Locale.ROOT)).toList();
    }

    public static String setColor(UUID uuid, String colorName) {
        String name = colorName == null ? "" : colorName.toLowerCase(Locale.ROOT);
        ChatFormatting f = ChatFormatting.getByName(name);
        if (f == null || !f.isColor())
            return "Unknown color. Pick one of: " + String.join(", ", colorNames());
        edit(uuid, s -> { s.color = name; s.rainbow = false; });
        return null;
    }

    public static String setHex(UUID uuid, String hex) {
        String h = hex == null ? "" : hex.trim().replace("#", "").toLowerCase(Locale.ROOT);
        if (!h.matches("[0-9a-f]{6}"))
            return "Hex must be 6 digits, e.g. ff8800 (that's orange).";
        edit(uuid, s -> { s.color = "#" + h; s.rainbow = false; });
        return null;
    }

    public static void setRainbow(UUID uuid) {
        edit(uuid, s -> { s.rainbow = true; s.color = null; });
    }

    /** flag ∈ bold|italic|underline|strikethrough|magic. Returns error or null. */
    public static String setFlag(UUID uuid, String flag, boolean on) {
        switch (flag == null ? "" : flag.toLowerCase(Locale.ROOT)) {
            case "bold"          -> edit(uuid, s -> s.bold = on);
            case "italic"        -> edit(uuid, s -> s.italic = on);
            case "underline"     -> edit(uuid, s -> s.underline = on);
            case "strikethrough" -> edit(uuid, s -> s.strikethrough = on);
            case "magic"         -> edit(uuid, s -> s.obfuscated = on);
            default -> { return "Unknown style. Pick: bold, italic, underline, strikethrough, magic."; }
        }
        return null;
    }

    public static void reset(UUID uuid) {
        Entry e = STYLES.get(uuid);
        if (e == null) return;
        if (e.presets.isEmpty()) STYLES.remove(uuid);     // nothing left worth keeping
        else e.live = new NameStyle();
        persist();
    }

    public static String savePreset(UUID uuid, String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{1,16}")) return "Preset names: 1-16 letters/numbers/underscores.";
        Entry e = STYLES.computeIfAbsent(uuid, k -> new Entry());
        if (e.presets.size() >= 8 && !e.presets.containsKey(n)) return "Preset limit is 8 — delete one first.";
        e.presets.put(n, e.live.copy());
        persist();
        return null;
    }

    public static String loadPreset(UUID uuid, String name) {
        Entry e = STYLES.get(uuid);
        NameStyle p = e == null ? null : e.presets.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (p == null) return "No such preset. Yours: " + presetList(uuid);
        e.live = p.copy();
        persist();
        return null;
    }

    public static String deletePreset(UUID uuid, String name) {
        Entry e = STYLES.get(uuid);
        if (e == null || e.presets.remove(name == null ? "" : name.toLowerCase(Locale.ROOT)) == null)
            return "No such preset. Yours: " + presetList(uuid);
        persist();
        return null;
    }

    public static String presetList(UUID uuid) {
        Entry e = STYLES.get(uuid);
        return e == null || e.presets.isEmpty() ? "(none saved)"
            : String.join(", ", e.presets.keySet().stream().sorted().toList());
    }

    /** Current live style of the player (a copy — safe to render previews from), never null. */
    public static NameStyle current(UUID uuid, String username) {
        NameStyle s = liveStyle(uuid, username);
        return s == null ? new NameStyle() : s.copy();
    }

    private static void edit(UUID uuid, java.util.function.Consumer<NameStyle> change) {
        Entry e = STYLES.computeIfAbsent(uuid, k -> new Entry());
        change.accept(e.live);
        persist();
    }

    // ── Persistence (AsyncIo — one writer thread, drained at stop) ────────────

    private static void persist() {
        Path f = file;
        if (f == null) return;
        JsonObject o = new JsonObject();
        STYLES.forEach((id, e) -> {
            JsonObject v = styleToJson(e.live);
            if (!e.presets.isEmpty()) {
                JsonObject p = new JsonObject();
                e.presets.forEach((n, s) -> p.add(n, styleToJson(s)));
                v.add("presets", p);
            }
            o.add(id.toString(), v);
        });
        String json = o.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[NameColor] save failed: {}", e.getMessage()); }
        });
    }

    private static JsonObject styleToJson(NameStyle s) {
        JsonObject v = new JsonObject();
        if (s.rainbow)        v.addProperty("rainbow", true);
        if (s.color != null)  v.addProperty("color", s.color);
        if (s.bold)           v.addProperty("bold", true);
        if (s.italic)         v.addProperty("italic", true);
        if (s.underline)      v.addProperty("underline", true);
        if (s.strikethrough)  v.addProperty("strikethrough", true);
        if (s.obfuscated)     v.addProperty("obfuscated", true);
        return v;
    }

    private static NameStyle styleFromJson(JsonObject v) {
        NameStyle s = new NameStyle();
        s.rainbow       = v.has("rainbow")       && v.get("rainbow").getAsBoolean();
        s.color         = v.has("color") ? v.get("color").getAsString() : null;
        s.bold          = v.has("bold")          && v.get("bold").getAsBoolean();
        s.italic        = v.has("italic")        && v.get("italic").getAsBoolean();
        s.underline     = v.has("underline")     && v.get("underline").getAsBoolean();
        s.strikethrough = v.has("strikethrough") && v.get("strikethrough").getAsBoolean();
        s.obfuscated    = v.has("obfuscated")    && v.get("obfuscated").getAsBoolean();
        return s;
    }

    private static TextColor resolveColor(String color) {
        if (color == null) return null;
        if (color.startsWith("#")) {
            try { return TextColor.fromRgb(Integer.parseInt(color.substring(1), 16)); }
            catch (NumberFormatException e) { return null; }
        }
        ChatFormatting f = ChatFormatting.getByName(color);
        return f != null && f.isColor() ? TextColor.fromLegacyFormat(f) : null;
    }
}
