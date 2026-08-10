package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * {@code /shipname <name>} — lets a PLAYER name the ship they are standing on.
 *
 * <p>Renaming a sub-level is otherwise Sable's {@code /sable sub_level name set}, which is op-only,
 * so players had to ask an admin for what is a purely cosmetic change. The AeroClaims claim screen
 * shows that same name — every ship reads "ship" until someone sets one.
 *
 * <p><b>1.7.8 — rewritten against Sable's API.</b> The first cut shelled out to Sable's command with
 * a guessed selector token ({@code @i}/{@code inside}/…), probing candidates until one parsed. None
 * ever did, so {@code selector()} returned null and every single use answered "you need to be
 * standing on the ship" — including when the player plainly was. Reading the jar settled it: the
 * selector grammar is a single char from {@code SubLevelSelectorType} (ALL/NEAREST/RANDOM/VIEWED/
 * LATEST/TRACKING/INSIDE), so all four guesses were wrong shapes.
 *
 * <p>None of that probing is needed, because Sable exposes the question directly:
 * {@code Sable.HELPER.getTrackingSubLevel(Entity)} returns the sub-level an entity is riding — which
 * IS "the ship you are standing on", and is the same notion as the TRACKING selector. From there
 * {@code SubLevel} gives {@code getUniqueId()}, {@code getName()} and {@code setName(String)}, and
 * {@code ServerSubLevel.setName} pushes {@code ClientboundChangeSubLevelNamePacket} so clients
 * update live. Reflection only because there is no compile dependency on Sable (the same reason
 * {@code AeroClaimsProtectionBypassMixin} targets its host by string).
 *
 * <p><b>Both names are written.</b> Sable's sub-level name and AeroClaims' own
 * {@code ShipRegistration.name} are separate stores; AeroClaims' own rename packet writes both, so
 * writing only Sable's left the claim screen showing the old name until someone ran
 * {@code /aeroclaims sublevels refresh all}. This mirrors what {@code RenameShipPacket} does.
 *
 * <p><b>Ownership</b> is best-effort via aeroclaims and denies ONLY when it positively resolves a
 * claim owned by someone else. Any reflection failure falls through to allow: the fallback bar —
 * you must be physically standing on the ship — is already reasonable for a cosmetic, reversible
 * action, and failing closed on a missing class would break the command for everyone.
 */
public final class ShipNameCommand {

    private ShipNameCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shipname")
            .then(Commands.literal("info")
                .executes(ctx -> info(ctx.getSource().getPlayerOrException())))
            .then(Commands.literal("clear")
                .executes(ctx -> apply(ctx.getSource().getPlayerOrException(), null)))
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> apply(ctx.getSource().getPlayerOrException(),
                    StringArgumentType.getString(ctx, "name"))))
            .executes(ctx -> info(ctx.getSource().getPlayerOrException()))
        );
    }

    // ── Command bodies ────────────────────────────────────────────────────────

    private static int info(ServerPlayer player) {
        if (!enabled()) { msg(player, "§cShip naming is disabled."); return 0; }
        Object sub = trackingSubLevel(player);
        if (sub == null) { msg(player, notOnShip()); return 0; }
        String name = nameOf(sub);
        msg(player, name == null || name.isBlank()
            ? "§7This ship has no name yet — set one with §f/shipname <name>§7."
            : "§7This ship is called §f" + name + "§7.");
        return 1;
    }

    /** {@code name == null} clears it. */
    private static int apply(ServerPlayer player, String name) {
        if (!enabled()) { msg(player, "§cShip naming is disabled."); return 0; }

        String clean = null;
        if (name != null) {
            clean = sanitize(name);
            if (clean.isBlank()) {
                msg(player, "§cThat name is empty once formatting codes are stripped.");
                return 0;
            }
        }

        Object sub = trackingSubLevel(player);
        if (sub == null) { msg(player, notOnShip()); return 0; }

        String shipId = uniqueIdOf(sub);
        Denial denial = checkOwnership(player, shipId);
        if (denial != null) {
            msg(player, "§cThis ship belongs to §f" + denial.ownerName + "§c — only they can rename it.");
            return 0;
        }

        if (!setName(sub, clean)) {
            msg(player, "§cCouldn't rename that ship — tell an admin.");
            return 0;
        }
        syncAeroClaimsName(shipId, clean);

        if (clean == null) {
            msg(player, "§7Ship name cleared.");
        } else {
            msg(player, "§aShip renamed to §f" + clean + "§a.");
        }
        CoffeesAeroAuth.LOGGER.info("[ShipName] {} renamed sub-level {} to '{}'.",
            player.getGameProfile().getName(), shipId, clean);
        return 1;
    }

    private static String notOnShip() {
        return "§cStand on the ship you want to name — §7you're not on one right now.";
    }

    // ── Sable API (reflection; no compile dependency) ─────────────────────────

    /**
     * The sub-level this player is riding, or null when they are on ordinary ground. This is the
     * single call that the whole command turns on — see the class javadoc for why the previous
     * command-selector probe could never work.
     */
    private static Object trackingSubLevel(ServerPlayer player) {
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Field helperField = sable.getDeclaredField("HELPER");
            helperField.setAccessible(true);
            Object helper = helperField.get(null);
            if (helper == null) return null;
            Method m = find(helper.getClass(), "getTrackingSubLevel", 1);
            if (m == null) return null;
            return m.invoke(helper, player);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[ShipName] Sable sub-level lookup unavailable: {}", t.toString());
            return null;
        }
    }

    private static String nameOf(Object subLevel) {
        try {
            Method m = find(subLevel.getClass(), "getName", 0);
            Object v = m != null ? m.invoke(subLevel) : null;
            return v != null ? v.toString() : null;
        } catch (Throwable t) { return null; }
    }

    private static String uniqueIdOf(Object subLevel) {
        try {
            Method m = find(subLevel.getClass(), "getUniqueId", 0);
            Object v = m != null ? m.invoke(subLevel) : null;
            return v != null ? v.toString() : null;
        } catch (Throwable t) { return null; }
    }

    /** ServerSubLevel.setName also broadcasts the name change to clients, so this is the whole job. */
    private static boolean setName(Object subLevel, String nameOrNull) {
        try {
            Method m = find(subLevel.getClass(), "setName", 1);
            if (m == null) return false;
            m.invoke(subLevel, nameOrNull);
            return true;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[ShipName] setName failed: {}", t.toString());
            return false;
        }
    }

    // ── AeroClaims (best effort, never fails the command) ─────────────────────

    /**
     * AeroClaims keeps its OWN copy of the ship name in {@code ShipRegistration.name}, and that is
     * what the claim screen renders. Writing only Sable's name left the UI stale, so mirror it the
     * way AeroClaims' own {@code RenameShipPacket} does.
     */
    private static void syncAeroClaimsName(String shipId, String nameOrNull) {
        if (shipId == null) return;
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("aeroclaims")) return;
            Class<?> mgr = Class.forName("com.mapter.aeroclaims.sublevel.RegisteredSublevelManager");
            Method get = find(mgr, "getRegistration", 1);
            if (get == null) return;
            Object reg = get.invoke(null, shipId);
            if (reg == null) return;                       // ship not registered with a claim yet
            Field nameField = reg.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(reg, nameOrNull);
            Method save = find(mgr, "saveNow", 0);
            if (save != null) save.invoke(null);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[ShipName] AeroClaims name sync unavailable: {}", t.toString());
        }
    }

    private record Denial(String ownerName) {}

    /**
     * Non-null = a claim positively resolved to a DIFFERENT owner. Null means allow: either the
     * player owns it, it is unclaimed, or aeroclaims could not be consulted at all.
     */
    private static Denial checkOwnership(ServerPlayer player, String shipId) {
        if (shipId == null) return null;
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("aeroclaims")) return null;
            Class<?> claimManager = Class.forName("com.mapter.aeroclaims.claim.ClaimManager");
            Method m = find(claimManager, "getClaimByShipId", 2);
            if (m == null) return null;
            Object claim = m.invoke(null, player.serverLevel(), shipId);
            if (claim == null) return null;                // unclaimed -> presence rule applies

            Method ownerGetter = find(claim.getClass(), "getOwner", 0);
            Object owner = ownerGetter != null ? ownerGetter.invoke(claim) : null;
            if (!(owner instanceof UUID ownerId)) return null;
            if (ownerId.equals(player.getUUID())) return null;

            var profile = CoffeesAeroAuth.PROFILE_STORE != null
                ? CoffeesAeroAuth.PROFILE_STORE.get(ownerId) : null;
            String name = profile != null && profile.displayName != null ? profile.displayName : "someone else";
            return new Denial(name);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[ShipName] Ownership check unavailable: {}", t.toString());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Walks supers AND interfaces — the Sable helper is reached through an interface type. */
    private static Method find(Class<?> type, String name, int params) {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Class<?> i : k.getInterfaces()) {
                Method m = find(i, name, params);
                if (m != null) return m;
            }
        }
        return null;
    }

    private static boolean enabled() {
        try { return AuthConfig.SHIPNAME_ENABLED.get(); } catch (Exception e) { return true; }
    }

    /** Strips section signs (colour + §k scramble injection into the claim UI) and caps length. */
    private static String sanitize(String raw) {
        int max;
        try { max = AuthConfig.SHIPNAME_MAX_LENGTH.get(); } catch (Exception e) { max = 32; }
        String s = raw.replace('§', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() > max) s = s.substring(0, max).trim();
        return s;
    }

    private static void msg(ServerPlayer player, String text) {
        TextUtil.msg(player, text);
    }
}
