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
            addSoundPhysics(list);
            addPunchy(list);
            addRagdolls(list);
            addGrandTeleport(list);
            cached = list;
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
            Object config = cfgField.get(null);
            if (config == null) throw new IllegalStateException("SoundPhysicsMod.CONFIG is null");
            Field f = config.getClass().getField("enabled");
            f.setAccessible(true);
            Object entry = f.get(config);
            Method get = entry.getClass().getMethod("get");
            Method set = findSetter(entry.getClass());
            Method save = entry.getClass().getMethod("save");
            get.setAccessible(true); set.setAccessible(true); save.setAccessible(true);
            out.add(new Toggle(
                "Realistic Sounds",
                "Sound Physics Remastered — reverb and occlusion.",
                () -> {
                    try { return Boolean.TRUE.equals(get.invoke(entry)); }
                    catch (Throwable t) { return false; }
                },
                v -> {
                    try { set.invoke(entry, v); save.invoke(entry); }
                    catch (Throwable t) { LogUtils.getLogger().warn("[AeroCore] sound physics toggle failed", t); }
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
            // The flags live on a private static PunchyConfig.DATA (a PunchyConfig$Data), not on
            // PunchyConfig itself — "enableMod" in the class strings is the JSON key. Verified
            // with javap. PunchyConfig.isModEnabled() reads DATA.enableMod, so setting the field
            // takes effect immediately.
            Class<?> cfg = Class.forName("punchy.config.PunchyConfig");
            Field dataField = cfg.getDeclaredField("DATA");
            dataField.setAccessible(true);
            Object data = dataField.get(null);
            if (data == null) throw new IllegalStateException("PunchyConfig.DATA is null");
            Field f = data.getClass().getField("enableMod");
            f.setAccessible(true);
            out.add(new Toggle(
                "Punchy Animations",
                "First-person swing and item animations.",
                () -> {
                    try { return f.getBoolean(data); }
                    catch (Throwable t) { return false; }
                },
                v -> {
                    try {
                        f.setBoolean(data, v);
                        // No save() on PunchyConfig, so persist by rewriting the JSON key.
                        writeJsonFlag(FMLPaths.CONFIGDIR.get().resolve("punchy/punchy_config.json"),
                            "enableMod", v);
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
