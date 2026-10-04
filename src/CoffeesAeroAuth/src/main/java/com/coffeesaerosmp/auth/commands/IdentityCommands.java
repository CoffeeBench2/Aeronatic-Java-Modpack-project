package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.UUIDUtil;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /aeroid} — staff tools for the identity gate ({@code admin/IdentityGate}).
 *
 * <ul>
 *   <li>{@code status <name>} — what the gate will decide from: type, link, link source, hold, password.</li>
 *   <li>{@code hold <name> <reason>} / {@code release <name>} — lock a profile against every login.
 *       Lock-don't-move: nothing about the account is touched, so releasing it restores it exactly.</li>
 *   <li>{@code bind <name> <mojangUuid>} — link a profile to a Mojang account checked by hand
 *       ({@code link_source='ADMIN'}). Only binds an UNLINKED profile, like the login path.</li>
 *   <li>{@code unbind <name>} — the one deliberate way to clear a link (e.g. a wrong BACKFILL inference).</li>
 * </ul>
 *
 * <p>Names are EXACT-case: identity is {@code md5("OfflinePlayer:" + name)}, which is case-sensitive, and
 * two case-duplicate pairs exist live. A miss lists the case-insensitive matches so the right one can be
 * picked. All DB work is on {@link AsyncIo}; replies come back on the server thread.
 */
public final class IdentityCommands {

    private IdentityCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("aeroid")
            .requires(src -> src.hasPermission(3))
            .then(Commands.literal("status")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> status(ctx, name(ctx)))))
            .then(Commands.literal("hold")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> hold(ctx, name(ctx), StringArgumentType.getString(ctx, "reason"))))))
            .then(Commands.literal("release")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> hold(ctx, name(ctx), null))))
            .then(Commands.literal("bind")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("mojangUuid", StringArgumentType.word())
                        .executes(ctx -> bind(ctx, name(ctx), StringArgumentType.getString(ctx, "mojangUuid"))))))
            .then(Commands.literal("unbind")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> unbind(ctx, name(ctx)))))
            // UUID-to-UUID account move. /authmod transferaccount is name-based and refuses equal names, but
            // under premiumKeepsMojangUuid an offline player who buys the game keeps their NAME and gets a
            // new (Mojang) uuid — the one move the name form cannot express. Level 4: it re-keys an account.
            .then(Commands.literal("move")
                .requires(src -> src.hasPermission(4))
                .then(Commands.argument("fromUuid", StringArgumentType.word())
                    .then(Commands.argument("toUuid", StringArgumentType.word())
                        .executes(ctx -> move(ctx, false))
                        .then(Commands.literal("confirm")
                            .executes(ctx -> move(ctx, true)))))));
    }

    private static int move(CommandContext<CommandSourceStack> ctx, boolean confirm) {
        CommandSourceStack src = ctx.getSource();
        UUID from = parseUuid(StringArgumentType.getString(ctx, "fromUuid"));
        UUID to = parseUuid(StringArgumentType.getString(ctx, "toUuid"));
        if (from == null || to == null) {
            src.sendFailure(Component.literal("Both arguments must be uuids (see /aeroid status <name>)."));
            return 0;
        }
        PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE == null ? null : CoffeesAeroAuth.PROFILE_STORE.get(from);
        String name = p != null ? p.username : "?";
        // Commands run on the server thread, which AccountTransfer requires.
        var r = confirm
            ? com.coffeesaerosmp.auth.admin.AccountTransfer.execute(src.getServer(), from, to, name)
            : com.coffeesaerosmp.auth.admin.AccountTransfer.plan(src.getServer(), from, to, name);
        for (String line : r.lines()) {
            if (r.ok()) src.sendSuccess(() -> Component.literal("  " + line), false);
            else src.sendFailure(Component.literal(line));
        }
        if (!r.ok()) return 0;
        if (confirm) {
            CoffeesAeroAuth.LOGGER.warn("[Identity] {} MOVED account {} ({}) -> {}.", src.getTextName(), name, from, to);
            src.sendSuccess(() -> Component.literal("§a✔ Moved. Have them rejoin and check everything."), false);
        } else {
            src.sendSuccess(() -> Component.literal("§ePLAN ONLY. §7Add §fconfirm§7 to apply."), false);
        }
        return 1;
    }

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name");
    }

    // ── subcommands ──────────────────────────────────────────────────────────

    private static int status(CommandContext<CommandSourceStack> ctx, String name) {
        UUID id = resolve(ctx, name);
        if (id == null) return 0;
        withDb(ctx, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT username, account_type, mojang_uuid, link_source, identity_hold, "
                  + "password_hash IS NOT NULL AND password_hash<>'' AS has_pw, total_playtime "
                  + "FROM players WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return List.of("§cNo database row for " + id + ".");
                    List<String> out = new ArrayList<>();
                    out.add("§6Identity §f" + rs.getString(1) + " §8" + id);
                    out.add("§7type §f" + rs.getString(2) + "§7, password §f" + (rs.getBoolean(6) ? "yes" : "no")
                          + "§7, playtime §f" + (rs.getLong(7) / 3600) + "h");
                    String link = rs.getString(3);
                    out.add("§7link §f" + (link == null || link.isBlank() ? "(none)" : link)
                          + (rs.getString(4) != null ? " §7(" + rs.getString(4) + ")" : ""));
                    String hold = rs.getString(5);
                    out.add(hold == null ? "§7hold §a(none)" : "§7hold §c" + hold);
                    return out;
                }
            }
        });
        return 1;
    }

    /** {@code reason == null} releases. */
    private static int hold(CommandContext<CommandSourceStack> ctx, String name, String reason) {
        UUID id = resolve(ctx, name);
        if (id == null) return 0;
        String stamped = reason == null ? null
            : truncate(reason.trim() + " — " + ctx.getSource().getTextName() + " " + java.time.LocalDate.now(), 255);
        withDb(ctx, c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET identity_hold=? WHERE uuid=?")) {
                ps.setString(1, stamped);
                ps.setString(2, id.toString());
                if (ps.executeUpdate() != 1) return List.of("§cNo database row for " + name + ".");
            }
            MinecraftServer server = ctx.getSource().getServer();
            server.execute(() -> {
                PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE.get(id);
                if (p != null) p.identityHold = stamped;
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null && stamped != null) {
                    online.connection.disconnect(Component.literal(
                        "§cThis profile is on hold while staff verify who owns it."));
                }
            });
            CoffeesAeroAuth.LOGGER.warn("[Identity] {} {} profile {} ({}){}", ctx.getSource().getTextName(),
                stamped == null ? "RELEASED" : "HELD", name, id, stamped == null ? "" : ": " + stamped);
            return List.of(stamped == null ? "§a✔ Hold released on §f" + name
                                           : "§a✔ §f" + name + "§a is on hold. Nobody can log into it until §f/aeroid release§a.");
        });
        return 1;
    }

    private static int bind(CommandContext<CommandSourceStack> ctx, String name, String raw) {
        UUID mojang = parseUuid(raw);
        if (mojang == null) {
            ctx.getSource().sendFailure(Component.literal("Not a uuid: " + raw));
            return 0;
        }
        UUID id = resolve(ctx, name);
        if (id == null) return 0;
        withDb(ctx, c -> {
            // Refuse if this Mojang account already owns another profile — that is a rename, and the
            // RenameHealer path (or /authmod transfer) is the right tool, not a second link.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT username FROM players WHERE mojang_uuid=? AND uuid<>? LIMIT 1")) {
                ps.setString(1, mojang.toString());
                ps.setString(2, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return List.of("§c" + mojang + " is already linked to §f" + rs.getString(1)
                        + "§c. That is a rename — transfer the account instead of linking twice.");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET mojang_uuid=?, link_source='ADMIN' "
                  + "WHERE uuid=? AND (mojang_uuid IS NULL OR mojang_uuid='')")) {
                ps.setString(1, mojang.toString());
                ps.setString(2, id.toString());
                if (ps.executeUpdate() != 1) {
                    return List.of("§c" + name + " is already linked (or has no row). Check §f/aeroid status§c; "
                        + "use §f/aeroid unbind§c first if the existing link is wrong.");
                }
            }
            refreshCached(ctx, id, mojang.toString(), "ADMIN");
            CoffeesAeroAuth.LOGGER.warn("[Identity] {} BOUND profile {} ({}) to Mojang {}.",
                ctx.getSource().getTextName(), name, id, mojang);
            return List.of("§a✔ §f" + name + "§a linked to §f" + mojang + " §7(ADMIN)");
        });
        return 1;
    }

    private static int unbind(CommandContext<CommandSourceStack> ctx, String name) {
        UUID id = resolve(ctx, name);
        if (id == null) return 0;
        withDb(ctx, c -> {
            String was;
            try (PreparedStatement ps = c.prepareStatement("SELECT mojang_uuid, link_source FROM players WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return List.of("§cNo database row for " + name + ".");
                    was = rs.getString(1) + " (" + rs.getString(2) + ")";
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET mojang_uuid=NULL, link_source=NULL WHERE uuid=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            refreshCached(ctx, id, null, null);
            // Logged at WARN with the old value: clearing a link is the one way to erase takeover evidence,
            // so the log must keep what was there.
            CoffeesAeroAuth.LOGGER.warn("[Identity] {} UNBOUND profile {} ({}) — link was {}.",
                ctx.getSource().getTextName(), name, id, was);
            return List.of("§e✔ §f" + name + "§e unlinked §7(was " + was + ")§e. "
                + "Consider §f/aeroid hold§e until the owner is confirmed — the next premium login binds it.");
        });
        return 1;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Exact-case profile uuid for a name, or null (with the case-insensitive near misses reported). */
    private static UUID resolve(CommandContext<CommandSourceStack> ctx, String name) {
        UUID id = UUIDUtil.expectedOfflineUUID(name);
        if (CoffeesAeroAuth.PROFILE_STORE != null && CoffeesAeroAuth.PROFILE_STORE.get(id) != null) return id;
        // premiumKeepsMojangUuid: a premium profile lives under its Mojang uuid, which the name cannot
        // derive. Accept exactly one profile whose ACCOUNT name matches exact-case.
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            UUID exact = null;
            int hits = 0;
            for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.matchesByName(name)) {
                if (name.equals(p.username)) { exact = p.getUUID(); hits++; }
            }
            if (hits == 1) return exact;
        }
        StringBuilder msg = new StringBuilder("No profile named exactly '" + name + "' (names are case-sensitive).");
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            List<String> near = new ArrayList<>();
            for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.matchesByName(name)) near.add(p.username);
            if (!near.isEmpty()) msg.append(" Did you mean: ").append(String.join(", ", near));
        }
        ctx.getSource().sendFailure(Component.literal(msg.toString()));
        return null;
    }

    private static void refreshCached(CommandContext<CommandSourceStack> ctx, UUID id, String link, String source) {
        ctx.getSource().getServer().execute(() -> {
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE.get(id);
            if (p != null) { p.mojangLink = link; p.linkSource = source; }
        });
    }

    private interface DbWork { List<String> run(Connection c) throws SQLException; }

    private static void withDb(CommandContext<CommandSourceStack> ctx, DbWork work) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) {
            src.sendFailure(Component.literal("MySQL is DOWN — identity changes need the live database."));
            return;
        }
        AsyncIo.submit(() -> {
            List<String> lines;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
                lines = work.run(c);
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[Identity] /aeroid failed", e);
                lines = List.of("§cDatabase error: " + e.getMessage());
            }
            List<String> out = lines;
            server.execute(() -> out.forEach(l -> src.sendSuccess(() -> Component.literal(l), false)));
        });
    }

    static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.matches("[0-9a-fA-F]{32}")) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
              + s.substring(16, 20) + "-" + s.substring(20);
        }
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
