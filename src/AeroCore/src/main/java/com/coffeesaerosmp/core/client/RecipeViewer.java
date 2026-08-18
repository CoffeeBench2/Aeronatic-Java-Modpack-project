package com.coffeesaerosmp.core.client;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which recipe viewer the player wants: EMI (default) or JEI.
 *
 * <h2>Why a one-line file and not the NeoForge config</h2>
 * {@link com.coffeesaerosmp.core.mixin.JemiPluginMixin} has to read this during JEI's <b>plugin
 * registration</b>, which is part of mod loading. A {@code ModConfigSpec} value read that early is
 * a timing gamble — and a wrong answer here silently leaves the player with no recipe viewer at
 * all. A three-byte file has no load order.
 *
 * <h2>Why switching needs a restart</h2>
 * EMI replaces JEI's runtime once, in {@code JemiPlugin.registerRuntime}. Nothing can un-swap it
 * afterwards, so the choice can only take effect on the next launch. The settings toggle says so
 * rather than pretending otherwise — the previous version flipped a live flag and produced neither
 * viewer, which is worse than an honest "restart required".
 */
public final class RecipeViewer {

    private RecipeViewer() {}

    private static final String FILE = "coffeesaero_recipe_viewer.txt";
    private static volatile Boolean cachedJei;
    /** What THIS launch actually booted with — captured on the first read, during mod loading. */
    private static volatile Boolean sessionJei;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    /** True when the player has chosen JEI. Defaults to false (EMI) — the pack's historic behaviour. */
    public static boolean preferJei() {
        Boolean c = cachedJei;
        if (c != null) return c;
        boolean jei = false;
        try {
            Path p = file();
            if (Files.isRegularFile(p)) {
                jei = "JEI".equalsIgnoreCase(Files.readString(p, StandardCharsets.UTF_8).trim());
            }
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] could not read recipe viewer preference: {}", t.toString());
        }
        cachedJei = jei;
        if (sessionJei == null) sessionJei = jei;      // first read wins = the value we booted with
        return jei;
    }

    /**
     * True when the player has picked a viewer that is NOT the one running right now, i.e. a restart
     * is genuinely outstanding. Drives the warning on the settings screen, so the notice only
     * appears when it actually applies rather than sitting there permanently and being ignored.
     */
    public static boolean needsRestart() {
        Boolean boot = sessionJei;
        return boot != null && boot != preferJei();
    }

    /** Records the choice for the NEXT launch. Returns true if it actually changed. */
    public static boolean setPreferJei(boolean jei) {
        boolean changed = preferJei() != jei;
        cachedJei = jei;
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), jei ? "JEI" : "EMI", StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] could not save recipe viewer preference: {}", t.toString());
        }
        // Hide EMI's own sidebar straight away when JEI was picked. Cancelling the JEI takeover only
        // stops EMI REPLACING JEI; EMI still draws its own overlay, so without this the player would
        // see both at once after restarting.
        applyEmiVisibility();
        return changed;
    }

    /**
     * Pushes the preference into {@code EmiConfig.enabled} and persists it in EMI's own config.
     * Safe to call whenever; a no-op when EMI is absent.
     */
    public static void applyEmiVisibility() {
        if (!ModList.get().isLoaded("emi")) return;
        try {
            Class<?> cfg = Class.forName("dev.emi.emi.config.EmiConfig");
            Field enabled = cfg.getField("enabled");
            enabled.setBoolean(null, !preferJei());
            try {
                Method write = cfg.getMethod("writeConfig");
                write.invoke(null);
            } catch (Throwable ignored) {
                // Not fatal — the live field is what EMI reads each frame.
            }
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroCore] could not apply EMI visibility: {}", t.toString());
        }
    }

    /** True when both viewers are installed, i.e. the choice is meaningful. */
    public static boolean bothInstalled() {
        return ModList.get().isLoaded("emi") && ModList.get().isLoaded("jei");
    }
}
