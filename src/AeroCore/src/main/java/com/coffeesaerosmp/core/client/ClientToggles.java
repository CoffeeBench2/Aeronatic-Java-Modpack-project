package com.coffeesaerosmp.core.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime on/off switches for client-side cosmetic mods, for the Aero settings screen.
 *
 * <p><b>Why reflection and not config files.</b> A mod cannot be unloaded at runtime, so "off" means
 * flipping the same in-memory flag the mod itself reads. Writing only the config file would do
 * nothing until a restart — most of these mods read their config into a field at load. So each
 * toggle sets the live field first (instant effect) and then persists, so it survives a restart too.
 *
 * <p>Every mod here is <b>client-side and cosmetic</b>: toggling one changes nothing the server sees.
 * Each entry is skipped silently when its mod is absent, so this is safe on any subset of the pack.
 */
public final class ClientToggles {

    private ClientToggles() {}

    /** One switch on the settings screen. */
    public record Toggle(String label, String description, java.util.function.BooleanSupplier get,
                         java.util.function.Consumer<Boolean> set) {}

    private static List<Toggle> cached;

    public static List<Toggle> all() {
        if (cached == null) {
            List<Toggle> list = new ArrayList<>();
            // Each detector is isolated: one mod being absent, updated, or throwing during
            // detection must never take the other toggles — or the settings screen — down with it.
            record Detector(String name, java.util.function.Consumer<List<Toggle>> add) {}
            for (Detector d : new Detector[]{
                    new Detector("Sound Physics",  ClientToggles::addSoundPhysics),
                    new Detector("Punchy",         ClientToggles::addPunchy),
                    new Detector("Ragdolls",       ClientToggles::addRagdolls),
                    new Detector("Grand Teleport", ClientToggles::addGrandTeleport),
                    new Detector("Subtle Effects", ClientToggles::addSubtleEffects),
                    new Detector("Sounds",         ClientToggles::addSounds)}) {
                int before = list.size();
                try {
                    d.add().accept(list);
                } catch (Throwable t) {
                    LogUtils.getLogger().warn("[AeroCore] toggle detector '{}' threw — skipping it, "
                        + "the other toggles are unaffected", d.name(), t);
                    // Drop anything a half-finished detector added, so no dead switch is shown.
                    while (list.size() > before) list.remove(list.size() - 1);
                }
            }
            cached = list;
            LogUtils.getLogger().info("[AeroCore] client toggles built: {} -> {}",
                list.size(), list.stream().map(Toggle::label).toList());
        }
        return cached;
    }

    /**
     * Sound Physics Remastered — {@code SoundPhysicsConfig.enabled} is a {@code ConfigEntry<Boolean>}
     * with get/set/save, so this both applies live and persists in one call.
     */
    private static void addSoundPhysics(List<Toggle> out) {
        if (!ModList.get().isLoaded("sound_physics_remastered")) return;
        try {
            // enabled is an INSTANCE field on SoundPhysicsConfig; the instance lives on
            // SoundPhysicsMod.CONFIG. (Verified with javap — the field is not static.)
            Class<?> mod = Class.forName("com.sonicether.soundphysics.SoundPhysicsMod");
            Field cfgField = mod.getDeclaredField("CONFIG");
            cfgField.setAccessible(true);
            // Same rule as Punchy: resolve the live CONFIG object on each call rather than caching
            // the ConfigEntry, so a config reload cannot leave us writing to an orphaned instance.
            if (cfgField.get(null) == null) throw new IllegalStateException("SoundPhysicsMod.CONFIG is null");
            java.util.function.Supplier<Object> entryOf = () -> {
                try {
                    Object c = cfgField.get(null);
                    if (c == null) return null;
                    Field ef = c.getClass().getField("enabled");
                    ef.setAccessible(true);
                    return ef.get(c);
                } catch (Throwable t) { return null; }
            };
            out.add(new Toggle(
                "Realistic Sounds",
                "Sound Physics Remastered — reverb and occlusion.",
                () -> {
                    try {
                        Object e = entryOf.get();
                        return e != null && Boolean.TRUE.equals(e.getClass().getMethod("get").invoke(e));
                    } catch (Throwable t) { return false; }
                },
                v -> {
                    try {
                        Object e = entryOf.get();
                        if (e == null) return;
                        findSetter(e.getClass()).invoke(e, v);
                        e.getClass().getMethod("save").invoke(e);
                        LogUtils.getLogger().info("[AeroCore] sound physics enabled -> {}", v);
                    } catch (Throwable t) { LogUtils.getLogger().warn("[AeroCore] sound physics toggle failed", t); }
                }));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] Sound Physics toggle unavailable: {}", t.toString());
        }
    }

    /**
     * Punchy — {@code PunchyConfig.enableMod} is a plain field, so the live value and the on-disk
     * value are separate: set the field for instant effect, rewrite the JSON so it sticks.
     */
    private static void addPunchy(List<Toggle> out) {
        if (!ModList.get().isLoaded("punchy")) return;
        try {
            // Flags live on a private static PunchyConfig.DATA (a PunchyConfig$Data) — "enableMod"
            // in the class strings is the JSON key, not a field on PunchyConfig. Verified with javap,
            // and PunchyClient checks PunchyConfig.isModEnabled() every tick, which reads
            // DATA.enableMod with no caching. So writing that field DOES take effect immediately.
            //
            // ⚠ RESOLVE DATA ON EVERY CALL, NEVER CACHE IT. PunchyConfig.load() assigns a NEW Data
            // instance, so a reference captured when the toggle list was built is orphaned the next
            // time the config loads — we would be setting a field on an object nothing reads. That
            // is exactly why this toggle appeared to do nothing.
            final Class<?> cfg = Class.forName("punchy.config.PunchyConfig");
            final Field dataField = cfg.getDeclaredField("DATA");
            dataField.setAccessible(true);
            if (dataField.get(null) == null) throw new IllegalStateException("PunchyConfig.DATA is null");

            out.add(new Toggle(
                "Punchy Animations",
                "First-person swing and item animations.",
                () -> {
                    try {
                        Object data = dataField.get(null);
                        if (data == null) return false;
                        Field f = data.getClass().getField("enableMod");
                        return f.getBoolean(data);
                    } catch (Throwable t) { return false; }
                },
                v -> {
                    try {
                        Object data = dataField.get(null);
                        if (data == null) return;
                        Field f = data.getClass().getField("enableMod");
                        f.setBoolean(data, v);
                        // No save() on PunchyConfig, so persist by rewriting the JSON key.
                        writeJsonFlag(FMLPaths.CONFIGDIR.get().resolve("punchy/punchy_config.json"),
                            "enableMod", v);
                        LogUtils.getLogger().info("[AeroCore] punchy enableMod -> {}", v);
                    } catch (Throwable t) {
                        LogUtils.getLogger().warn("[AeroCore] punchy toggle failed", t);
                    }
                }));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] Punchy toggle unavailable: {}", t.toString());
        }
    }

    /**
     * Sable: Ragdolls — the only one here with a proper public API
     * ({@code RagdollSettings.enabled()} / {@code setEnabled(boolean)}), so no field poking.
     */
    private static void addRagdolls(List<Toggle> out) {
        if (!ModList.get().isLoaded("sable_player_ragdoll")
            && !ModList.get().isLoaded("sableplayerragdoll")) return;
        try {
            Class<?> s = Class.forName("dev.leo.sableplayerragdoll.config.RagdollSettings");
            Method get = s.getMethod("enabled");
            Method set = s.getMethod("setEnabled", boolean.class);
            out.add(new Toggle(
                "Death Ragdolls",
                "Sable: Ragdolls — physics ragdoll on death.",
                () -> {
                    try { return Boolean.TRUE.equals(get.invoke(null)); }
                    catch (Throwable t) { return false; }
                },
                v -> {
                    try { set.invoke(null, v); }
                    catch (Throwable t) { LogUtils.getLogger().warn("[AeroCore] ragdoll toggle failed", t); }
                }));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] Ragdolls toggle unavailable: {}", t.toString());
        }
    }

    /**
     * Grand Teleport — {@code effectEnabled} is a private static boolean with no public setter, so
     * set the field (its own config screen has a toggleEffectEnabled(), i.e. it is read live) and
     * mirror the value into the properties file so it persists.
     */
    private static void addGrandTeleport(List<Toggle> out) {
        if (!ModList.get().isLoaded("gtalike_teleport")) return;
        try {
            Class<?> cfg = Class.forName("dev.codex.gtaliketeleport.GtaLikeTeleportConfig");
            Field f = cfg.getDeclaredField("effectEnabled");
            f.setAccessible(true);
            out.add(new Toggle(
                "Teleport Animation",
                "Grand Teleport — the zoom-out/zoom-in warp effect.",
                () -> {
                    try { return f.getBoolean(null); }
                    catch (Throwable t) { return false; }
                },
                v -> {
                    try {
                        f.setBoolean(null, v);
                        writePropertiesFlag(
                            FMLPaths.CONFIGDIR.get().resolve("grand_teleport.properties"),
                            "effectEnabled", v);
                    } catch (Throwable t) {
                        LogUtils.getLogger().warn("[AeroCore] grand teleport toggle failed", t);
                    }
                }));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] Grand Teleport toggle unavailable: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bulk toggles.
    //
    // Subtle Effects and Sounds have NO master switch — they are dozens of individual flags. So
    // "off" means setting every flag false, which would destroy any per-effect customisation on
    // the way back on. To keep it lossless we SNAPSHOT the current values to our own JSON first
    // and restore from that, rather than blindly setting everything true.
    // ---------------------------------------------------------------------------------------

    /** Restores captured on the last "off", so "on" puts back exactly what was there. */
    private static final java.util.Map<String, List<Runnable>> RESTORES = new java.util.HashMap<>();

    private static Path snapshotFile(String id) {
        return FMLPaths.CONFIGDIR.get().resolve("coffeesaerosmp_core/" + id + "-snapshot.json");
    }

    /** True when we currently have that mod muted. */
    private static boolean isMuted(String id) {
        return RESTORES.containsKey(id) || Files.exists(snapshotFile(id));
    }

    /**
     * Applies {@code false} to every boolean-ish field found by {@code collect}, remembering how to
     * undo it. {@code collect} returns one entry per flag: a getter, a setter, and a stable key.
     */
    private static void mute(String id, List<Flag> flags, Runnable persist) {
        List<Runnable> undo = new java.util.ArrayList<>();
        JsonObject snap = new JsonObject();
        for (Flag f : flags) {
            boolean was = f.get();
            snap.addProperty(f.key(), was);
            undo.add(() -> f.set(was));
            f.set(false);
        }
        RESTORES.put(id, undo);
        LogUtils.getLogger().info("[AeroCore] MUTE {} — set {} flags false", id, flags.size());
        try {
            Path p = snapshotFile(id);
            Files.createDirectories(p.getParent());
            Files.writeString(p, snap.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogUtils.getLogger().warn("[AeroCore] could not write {} snapshot", id, e);
        }
        persist.run();
    }

    /** Puts back the snapshot — from memory this session, or from disk after a restart. */
    private static void unmute(String id, List<Flag> flags, Runnable persist) {
        List<Runnable> undo = RESTORES.remove(id);
        if (undo != null) {
            undo.forEach(Runnable::run);
        } else {
            // Restarted while muted: rebuild from the JSON so per-effect choices survive.
            try {
                Path p = snapshotFile(id);
                if (Files.exists(p)) {
                    JsonObject snap = JsonParser.parseString(
                        Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (Flag f : flags) {
                        if (snap.has(f.key())) f.set(snap.get(f.key()).getAsBoolean());
                    }
                }
            } catch (Exception e) {
                LogUtils.getLogger().warn("[AeroCore] could not read {} snapshot — enabling all", id, e);
                flags.forEach(f -> f.set(true));
            }
        }
        try { Files.deleteIfExists(snapshotFile(id)); } catch (IOException ignored) { }
        persist.run();
        LogUtils.getLogger().info("[AeroCore] UNMUTE {} — restored {} flags", id, flags.size());
    }


    /**
     * Re-applies a mute that is still recorded on disk.
     *
     * <p><b>This is what made the toggles look broken.</b> {@link #isMuted} is backed by the
     * snapshot FILE, so after a restart the switch read OFF while the mod's in-memory flags were
     * back at their defaults — the UI said off, the effects still played. The snapshot survives a
     * restart; the field writes do not, so they have to be redone when the toggle list is built.
     * Deliberately does NOT rewrite the snapshot: the values already captured there are the ones to
     * restore later.
     */
    private static void reapplyIfMuted(String id, List<Flag> flags, Runnable persist) {
        if (!Files.exists(snapshotFile(id))) return;
        List<Runnable> undo = new java.util.ArrayList<>();
        for (Flag f : flags) {
            boolean was = f.get();
            undo.add(() -> f.set(was));
            f.set(false);
        }
        RESTORES.put(id, undo);
        persist.run();
        LogUtils.getLogger().info("[AeroCore] re-applied mute for {} on startup — {} flags", id, flags.size());
    }

    /** One toggleable flag, wherever it lives. */
    private record Flag(String key, java.util.function.BooleanSupplier getter,
                        java.util.function.Consumer<Boolean> setter) {
        void set(boolean v) { setter.accept(v); }
        boolean get() { return getter.getAsBoolean(); }
    }

    /**
     * Subtle Effects — flags are {@code ValidatedBoolean} (fzzy_config: {@code get()}/{@code accept()})
     * and plain booleans, spread across the five ModConfigs holders.
     */
    private static void addSubtleEffects(List<Toggle> out) {
        if (!ModList.get().isLoaded("subtle_effects")) return;
        try {
            Class<?> mc = Class.forName("einstein.subtle_effects.init.ModConfigs");
            List<Flag> flags = new java.util.ArrayList<>();
            for (String holder : new String[]{"GENERAL", "BLOCKS", "ENTITIES", "ENVIRONMENT", "ITEMS"}) {
                Field hf = mc.getField(holder);
                Object cfg = hf.get(null);
                if (cfg == null) continue;
                collectFlags(cfg, holder, flags);
            }
            if (flags.isEmpty()) throw new IllegalStateException("no flags found");
            final int n = flags.size();

            // fzzy_config caches/propagates through the Config object — setting the ValidatedBoolean
            // alone is not enough, the holder must be told. save() also persists, and onUpdateClient()
            // is what fzzy_config fires so listeners re-read. Both are best-effort.
            final List<Object> holders = new java.util.ArrayList<>();
            for (String holder : new String[]{"GENERAL", "BLOCKS", "ENTITIES", "ENVIRONMENT", "ITEMS"}) {
                try { holders.add(mc.getField(holder).get(null)); } catch (Throwable ignored) { }
            }
            final Runnable persist = () -> {
                for (Object h : holders) {
                    if (h == null) continue;
                    for (String m : new String[]{"onUpdateClient", "save"}) {
                        try { h.getClass().getMethod(m).invoke(h); }
                        catch (Throwable ignored) { }
                    }
                }
            };

            reapplyIfMuted("subtle_effects", flags, persist);
            out.add(new Toggle(
                "Subtle Effects",
                "Extra particles and visual effects (" + n + " effects).",
                () -> !isMuted("subtle_effects"),
                v -> {
                    if (v) unmute("subtle_effects", flags, persist);
                    else   mute("subtle_effects", flags, persist);
                }));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] Subtle Effects toggle unavailable: {}", t.toString());
        }
    }

    /** Sounds — every sound is a {@code ConfiguredSound} with a public {@code enabled} boolean. */
    private static void addSounds(List<Toggle> out) {
        if (!ModList.get().isLoaded("sounds")) return;
        try {
            Class<?> sc = Class.forName("dev.imb11.sounds.config.SoundsConfig");
            Object[] groups = (Object[]) sc.getMethod("getAll").invoke(null);
            Class<?> cs = Class.forName("dev.imb11.sounds.api.config.ConfiguredSound");
            Field enabled = cs.getField("enabled");
            List<Flag> flags = new java.util.ArrayList<>();
            List<Object> groupList = new java.util.ArrayList<>();
            Method getLive = null;
            try { getLive = sc.getMethod("get", Class.class); } catch (Throwable ignored) { }
            for (Object g : groups) {
                if (g == null) continue;
                // SoundsConfig.get(clazz) hands back the instance the mod actually reads, which is
                // not necessarily the one getAll() lists. Mutate that one when it differs.
                Object live = g;
                if (getLive != null) {
                    try {
                        Object x = getLive.invoke(null, g.getClass());
                        if (x != null) live = x;
                    } catch (Throwable ignored) { }
                }
                if (!groupList.contains(live)) groupList.add(live);
                if (live != g && !groupList.contains(g)) groupList.add(g);

                for (Object target : (live == g ? List.of(g) : List.of(g, live))) {
                    for (Field f : target.getClass().getFields()) {
                        if (!cs.isAssignableFrom(f.getType())) continue;
                        Object sound = f.get(target);
                        if (sound == null) continue;
                        String key = target.getClass().getSimpleName() + "."
                            + f.getName() + (target == g ? "" : "#live");
                        flags.add(new Flag(key,
                            () -> { try { return enabled.getBoolean(sound); } catch (Throwable t) { return false; } },
                            v  -> { try { enabled.setBoolean(sound, v); } catch (Throwable ignored) { } }));
                    }
                }
            }
            if (flags.isEmpty()) throw new IllegalStateException("no ConfiguredSound fields found");
            final Runnable persist = () -> {
                for (Object g : groupList) {
                    try { g.getClass().getMethod("save").invoke(g); } catch (Throwable ignored) { }
                }
            };
            final int n = flags.size();
            reapplyIfMuted("sounds", flags, persist);
            out.add(new Toggle(
                "Extra Sounds",
                "Sounds mod — UI, chat, world and event sounds (" + n + " sounds).",
                () -> !isMuted("sounds"),
                v -> {
                    if (v) unmute("sounds", flags, persist);
                    else   mute("sounds", flags, persist);
                }));
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] Sounds toggle unavailable: {}", t.toString());
        }
    }

    /**
     * Adds every boolean / ValidatedBoolean field on {@code cfg} to {@code out}, RECURSING into
     * nested config objects.
     *
     * <p>Recursion is the whole point: Subtle Effects groups its real per-effect flags inside nested
     * objects ({@code ModBlockConfigs.sparks}, {@code .steam}, {@code .fallingBlocks}, …). A
     * top-level-only scan finds ~112 mostly-cosmetic options and misses every actual effect switch,
     * which is exactly why the first version of this toggle appeared to do nothing.
     */
    private static void collectFlags(Object cfg, String prefix, List<Flag> out) {
        collectFlags(cfg, prefix, out, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }

    private static void collectFlags(Object cfg, String prefix, List<Flag> out,
                                     java.util.Set<Object> seen, int depth) {
        if (cfg == null || depth > 5 || !seen.add(cfg)) return;
        for (Field f : cfg.getClass().getFields()) {
            String key = prefix + "." + f.getName();
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;

                if (f.getType() == boolean.class) {
                    out.add(new Flag(key,
                        () -> { try { return f.getBoolean(cfg); } catch (Throwable t) { return false; } },
                        v  -> { try { f.setBoolean(cfg, v); } catch (Throwable ignored) { } }));
                    continue;
                }
                Object val = f.get(cfg);
                if (val == null) continue;

                // fzzy_config ValidatedBoolean (and ValidatedCondition<Boolean>): get()/accept(Boolean)
                Method get = findNoArg(val.getClass(), "get");
                Method acc = findOneArg(val.getClass(), "accept");
                if (get != null && acc != null) {
                    Object probe = get.invoke(val);
                    if (probe instanceof Boolean) {
                        out.add(new Flag(key,
                            () -> { try { return Boolean.TRUE.equals(get.invoke(val)); } catch (Throwable t) { return false; } },
                            v  -> { try { acc.invoke(val, v); } catch (Throwable ignored) { } }));
                        continue;
                    }
                }

                // Nested config section — recurse. Only into the mod's own classes, so we never
                // wander off into Minecraft or library objects.
                Package p = val.getClass().getPackage();
                String pkg = p == null ? "" : p.getName();
                if (pkg.startsWith("einstein.subtle_effects") || pkg.startsWith("dev.imb11.sounds")) {
                    collectFlags(val, key, out, seen, depth + 1);
                }
            } catch (Throwable ignored) {
                // Skip anything that doesn't behave like a flag.
            }
        }
    }

    private static Method findNoArg(Class<?> c, String name) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        return null;
    }

    private static Method findOneArg(Class<?> c, String name) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
        return null;
    }

    /** Rewrites one {@code key=value} line in a .properties file, leaving the rest byte-for-byte. */
    private static void writePropertiesFlag(Path path, String key, boolean value) throws IOException {
        if (!Files.exists(path)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                found = true;
                break;
            }
        }
        if (!found) lines.add(key + "=" + value);
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /** {@code set} takes the generic type, so match by name+arity rather than parameter class. */
    private static Method findSetter(Class<?> c) throws NoSuchMethodException {
        for (Method m : c.getMethods()) {
            if (m.getName().equals("set") && m.getParameterCount() == 1) return m;
        }
        throw new NoSuchMethodException("set(T) on " + c.getName());
    }

    /** Best-effort singleton lookup for mods that keep config on an instance rather than statics. */
    private static Object instanceOf(Class<?> c) {
        for (String name : new String[]{"INSTANCE", "instance", "CONFIG", "config"}) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) return v;
            } catch (Throwable ignored) {
                // Try the next candidate name.
            }
        }
        return null;
    }

    /** Rewrites one boolean in a JSON config, leaving every other key untouched. */
    private static void writeJsonFlag(Path path, String key, boolean value) throws IOException {
        if (!Files.exists(path)) return;
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        obj.addProperty(key, value);
        Files.writeString(path, obj.toString(), StandardCharsets.UTF_8);
    }
}
