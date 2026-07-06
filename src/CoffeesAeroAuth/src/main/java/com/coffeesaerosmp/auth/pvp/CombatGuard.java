package com.coffeesaerosmp.auth.pvp;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PvP combat guard + death-return.
 *
 * COMBAT TAG — when a player damages another player, BOTH are tagged for a short window
 * (refreshed on every hit). While tagged: escape commands are blocked ({@code /logout},
 * {@code /spawn}, {@code /back}, and the FTB Essentials teleports), and disconnecting is
 * punished by killing the player in place so the fight's spoils drop (classic anti-combat-log).
 * The lobby is only reachable via reconnect, so blocking logout IS the lobby-escape fix.
 *
 * /BACK — returns a player to their last DEATH point only: usable once per death, only within
 * {@code backWindowSeconds} (default 5 min) of dying, then locked for {@code backCooldownSeconds}
 * (default 10 min) so it can't be chained. Blocked entirely while combat-tagged.
 *
 * Ops (permission 4) are exempt from command blocking but NOT from tagging (so admin testing
 * still shows the tag), and never punished on logout.
 */
public final class CombatGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroAuth-CombatGuard");

    /** Commands a tagged player may not run (root literal, lowercase). Covers ours + FTB Essentials. */
    private static final Set<String> BLOCKED_WHILE_TAGGED = Set.of(
        "logout", "back", "spawn", "lobby",
        "home", "sethome", "warp", "rtp", "tpa", "tpaccept", "tpahere", "tpdeny", "tpx");

    private record DeathPoint(ResourceKey<net.minecraft.world.level.Level> dim,
                              double x, double y, double z, long epochMs) {}

    private static final Map<UUID, Long>       taggedUntil     = new ConcurrentHashMap<>();
    private static final Map<UUID, DeathPoint> lastDeath       = new ConcurrentHashMap<>();
    private static final Map<UUID, Long>       backLockedUntil = new ConcurrentHashMap<>();

    private CombatGuard() {}

    // ── Tagging ─────────────────────────────────────────────────────────────────

    /** Player-vs-player damage tags both parties. Indirect (arrows etc.) counts via source entity. */
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (attacker == victim) return;
        tag(attacker);
        tag(victim);
    }

    private static void tag(ServerPlayer player) {
        long until = System.currentTimeMillis() + AuthConfig.COMBAT_TAG_SECONDS.get() * 1000L;
        Long prev = taggedUntil.put(player.getUUID(), until);
        if (prev == null || prev < System.currentTimeMillis()) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§c⚔ Combat! §7No escaping for §c" + AuthConfig.COMBAT_TAG_SECONDS.get()
                + "s§7 — logging out drops your items."));
        }
    }

    public static boolean isTagged(UUID uuid) {
        Long until = taggedUntil.get(uuid);
        return until != null && until > System.currentTimeMillis();
    }

    private static long taggedRemainingSec(UUID uuid) {
        Long until = taggedUntil.get(uuid);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    // ── Death tracking (feeds /back) ────────────────────────────────────────────

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        lastDeath.put(uuid, new DeathPoint(player.level().dimension(),
            player.getX(), player.getY(), player.getZ(), System.currentTimeMillis()));
        taggedUntil.remove(uuid);   // death ends your combat
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§7Died? §a/back§7 returns you to your death spot for the next §a"
            + (AuthConfig.BACK_WINDOW_SECONDS.get() / 60) + " min§7."));
    }

    // ── Combat logging punishment ───────────────────────────────────────────────

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        boolean tagged = isTagged(uuid);
        taggedUntil.remove(uuid);
        if (!tagged || !player.isAlive() || player.hasPermissions(4)) return;
        if (!AuthConfig.COMBAT_LOG_PUNISH.get()) return;
        // Kill in place BEFORE the entity is discarded: inventory drops at the fight.
        player.kill();
        LOGGER.info("[CombatGuard] {} combat-logged — killed in place, items dropped.",
            player.getGameProfile().getName());
        if (player.getServer() != null) {
            player.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                TextUtil.PREFIX + "§c" + player.getDisplayName().getString()
                + " §7combat-logged and dropped their items!"), false);
        }
    }

    // ── Escape-command blocking ─────────────────────────────────────────────────

    public static void onCommand(CommandEvent event) {
        CommandSourceStack src = event.getParseResults().getContext().getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(4)) return;
        if (!isTagged(player.getUUID())) return;

        String input = event.getParseResults().getReader().getString().trim();
        if (input.startsWith("/")) input = input.substring(1);
        int sp = input.indexOf(' ');
        String root = (sp < 0 ? input : input.substring(0, sp)).toLowerCase(Locale.ROOT);
        // strip a namespace prefix like "ftbessentials:back"
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);

        if (BLOCKED_WHILE_TAGGED.contains(root)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§c⚔ You're in combat! §7Wait §c" + taggedRemainingSec(player.getUUID())
                + "s§7 before using §f/" + root + "§7."));
        }
    }

    // ── /back ───────────────────────────────────────────────────────────────────

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("back")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                return handleBack(player) ? 1 : 0;
            })
        );
    }

    private static boolean handleBack(ServerPlayer player) {
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();

        if (CoffeesAeroAuth.AUTH_MANAGER == null || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(uuid)) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cLog in first."));
            return false;
        }
        if (isTagged(uuid)) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§c⚔ You're in combat! §7No /back for §c" + taggedRemainingSec(uuid) + "s§7."));
            return false;
        }
        Long locked = backLockedUntil.get(uuid);
        if (locked != null && locked > now) {
            long m = (locked - now) / 60000, s = ((locked - now) / 1000) % 60;
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§7/back is recharging — §c" + m + "m " + s + "s§7 left."));
            return false;
        }
        DeathPoint death = lastDeath.get(uuid);
        long windowMs = AuthConfig.BACK_WINDOW_SECONDS.get() * 1000L;
        if (death == null || now - death.epochMs() > windowMs) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§7Nothing to return to — /back only works within §c"
                + (AuthConfig.BACK_WINDOW_SECONDS.get() / 60) + " min§7 of a death."));
            return false;
        }
        ServerLevel target = player.getServer() == null ? null : player.getServer().getLevel(death.dim());
        if (target == null) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cThat dimension isn't loaded."));
            return false;
        }
        player.teleportTo(target, death.x(), death.y(), death.z(),
            Set.of(), player.getYRot(), player.getXRot());
        lastDeath.remove(uuid);                                                   // one use per death
        backLockedUntil.put(uuid, now + AuthConfig.BACK_COOLDOWN_SECONDS.get() * 1000L);
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§a↩ Back at your death spot. §7(Recharges in "
            + (AuthConfig.BACK_COOLDOWN_SECONDS.get() / 60) + " min.)"));
        return true;
    }
}
