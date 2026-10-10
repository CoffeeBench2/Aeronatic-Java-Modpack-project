package com.coffeesaerosmp.auth.ticket;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.discord.DiscordRest;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.lobby.NameApprovalQueue;
import com.coffeesaerosmp.auth.mail.MailService;
import com.coffeesaerosmp.auth.ticket.TicketStore.Status;
import com.coffeesaerosmp.auth.ticket.TicketStore.Ticket;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ticket — player support tickets (owner 2026-10-10). A ticket is a DB row plus a Discord embed in
 * {@code ticketChannelId} carrying ✅ Acknowledge / ✉ Reply / 🔒 Close buttons. Every admin action mails
 * the player (MailStore pings them in chat if they are online), and the embed is rebuilt from the row.
 *
 * <p>Button ids are {@code tk<action>:SURVIVAL:<id>} — origin-tagged like the moderation buttons because
 * the lobby shares the bot token and receives every click too. Only the SMP role opens tickets.
 */
public final class TicketService {

    private static final int MIN_LEN = 10, MAX_LEN = 500;
    private static final int OPEN = 0x5865F2, ACK = 0xFEE75C, CLOSED = 0x4F545C;

    /** Players whose /ticket is between "count" and "insert" — stops a double press slipping past the limit. */
    private static final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private TicketService() {}

    // ── In-game ─────────────────────────────────────────────────────────────────

    public static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("ticket")
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getPlayerOrException())))
            .then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> open(ctx.getSource().getPlayerOrException(),
                    StringArgumentType.getString(ctx, "message"))))
            .executes(ctx -> {
                ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX
                    + "§7Usage: §e/ticket <what's wrong>§7 — staff will reply by §e/mail§7. §e/ticket list§7 shows yours. §8("
                    + AuthConfig.TICKETS_PER_DAY.get() + " per day)"));
                return 1;
            }));
    }

    private static boolean usable(ServerPlayer p) {
        boolean ok;
        try {
            ok = AuthConfig.TICKETS_ENABLED.get() && !LobbyHandoff.isLobbyRole()
                && TicketStore.available() && rest() != null
                && !AuthConfig.TICKET_CHANNEL_ID.get().isBlank();
        } catch (Exception e) { ok = false; }
        if (!ok) p.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§cTickets are unavailable right now. §7Please ask in Discord instead."));
        return ok;
    }

    private static int open(ServerPlayer p, String raw) {
        if (!usable(p)) return 0;
        String msg = raw.trim().replaceAll("\\s+", " ");
        if (msg.length() < MIN_LEN) {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cTell us a bit more §7(at least " + MIN_LEN + " characters)."));
            return 0;
        }
        if (msg.length() > MAX_LEN) {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cToo long §7(max " + MAX_LEN + " characters)."));
            return 0;
        }
        UUID uuid = p.getUUID();
        if (!inFlight.add(uuid)) {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Your last ticket is still being sent…"));
            return 0;
        }
        MinecraftServer server = p.getServer();
        String name = p.getGameProfile().getName();
        String loc = p.level().dimension().location().getPath() + " "
            + p.blockPosition().getX() + " " + p.blockPosition().getY() + " " + p.blockPosition().getZ();
        long midnight = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int limit = AuthConfig.TICKETS_PER_DAY.get();

        TicketStore.countSince(server, uuid, midnight, count -> {
            if (count < 0) { inFlight.remove(uuid); tell(server, uuid, "§cTickets are unavailable right now."); return; }
            if (count >= limit) {
                inFlight.remove(uuid);
                tell(server, uuid, "§cYou've used your " + limit + " tickets for today. §7Try again tomorrow, or ask in Discord.");
                return;
            }
            TicketStore.create(server, uuid, name, msg, loc, id -> {
                inFlight.remove(uuid);
                if (id < 0) { tell(server, uuid, "§cCouldn't save your ticket — please try again."); return; }
                Ticket t = new Ticket(id, uuid, name, msg, loc, Status.OPEN, System.currentTimeMillis(), null, null);
                DiscordRest r = rest();
                if (r != null) r.postMessage(AuthConfig.TICKET_CHANNEL_ID.get(), messageJson(t));
                tell(server, uuid, "§a🎫 Ticket #" + id + " sent. §7Staff will answer by §e/mail§7. §8("
                    + (limit - count - 1) + " left today)");
                CoffeesAeroAuth.LOGGER.info("[Ticket] #{} opened by {}: {}", id, name, msg);
            });
        });
        return 1;
    }

    private static int list(ServerPlayer p) {
        if (!usable(p)) return 0;
        MinecraftServer server = p.getServer();
        UUID uuid = p.getUUID();
        TicketStore.listFor(server, uuid, rows -> {
            ServerPlayer now = server.getPlayerList().getPlayer(uuid);
            if (now == null) return;
            if (rows == null) { now.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cTickets are unavailable right now.")); return; }
            if (rows.isEmpty()) { now.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7You have no tickets.")); return; }
            now.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§6Your tickets:"));
            SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm");
            for (Ticket t : rows) {
                String st = switch (t.status()) { case OPEN -> "§9OPEN"; case ACK -> "§eSEEN"; case CLOSED -> "§8CLOSED"; };
                String text = t.message().length() > 40 ? t.message().substring(0, 40) + "…" : t.message();
                now.sendSystemMessage(Component.literal(" §7#" + t.id() + " " + st + " §8" + f.format(new Date(t.createdAt())) + " §f" + text));
            }
        });
        return 1;
    }

    private static void tell(MinecraftServer server, UUID uuid, String text) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p != null) p.sendSystemMessage(Component.literal(TextUtil.PREFIX + text));
    }

    private static DiscordRest rest() {
        DiscordRest r = CoffeesAeroAuth.DISCORD_REST;
        return r != null && r.isConfigured() ? r : null;
    }

    // ── Discord (called from DiscordInteractions on the gateway thread) ─────────

    /**
     * Handles a {@code tk*} custom id whose origin has already been checked. Returns the interaction
     * response JSON to send.
     */
    public static String handle(MinecraftServer server, String action, long id, String clicker, String modalText) {
        if (action.equals("tkreply"))
            return modal("tkreplymodal", id, "Reply to ticket #" + id, "text", "Your reply (mailed to the player)", true);
        if (action.equals("tkclose"))
            return modal("tkclosemodal", id, "Close ticket #" + id, "note", "Closing note (optional, mailed)", false);

        Ticket t = TicketStore.getNow(id);
        if (t == null) return ephemeral("⚠️ Ticket #" + id + " not found (or the database is down).");
        if (t.status() == Status.CLOSED) return update(t);   // already closed: just re-render

        String c = trim(clicker, 64);
        Ticket next;
        switch (action) {
            case "tkack" -> {
                next = with(t, Status.ACK, null, t.lastReply());
                TicketStore.update(id, Status.ACK, null, null);
                mail(server, t, "Ticket #" + id + " received",
                    "Hi " + t.name() + ", we've received your ticket #" + id
                    + " and will take action as soon as we can. Thanks for letting us know!\n\n— " + c
                    + "\n\nYour ticket: " + t.message());
            }
            case "tkreplymodal" -> {
                String text = trim(modalText == null ? "" : modalText.trim(), 900);
                if (text.isEmpty()) return ephemeral("Empty reply — nothing sent.");
                Status st = t.status() == Status.OPEN ? Status.ACK : t.status();
                String stored = c + ": " + text;
                next = with(t, st, null, stored);
                TicketStore.update(id, st, null, stored);
                mail(server, t, "Ticket #" + id + " — reply", text + "\n\n— " + c);
            }
            case "tkclosemodal" -> {
                String note = trim(modalText == null ? "" : modalText.trim(), 900);
                next = with(t, Status.CLOSED, c, note.isEmpty() ? t.lastReply() : c + ": " + note);
                TicketStore.update(id, Status.CLOSED, c, note.isEmpty() ? null : c + ": " + note);
                mail(server, t, "Ticket #" + id + " closed",
                    "Your ticket #" + id + " has been resolved and closed."
                    + (note.isEmpty() ? "" : "\n\n" + note) + "\n\n— " + c
                    + "\n\nNeed more help? Open a new one with /ticket.");
            }
            default -> { return ephemeral("Unknown ticket action."); }
        }
        if (CoffeesAeroAuth.WATCHDOG != null)
            CoffeesAeroAuth.WATCHDOG.recordModAction("TICKET " + action + " #" + id + " by=" + c);
        return update(next);
    }

    private static Ticket with(Ticket t, Status s, String closedBy, String lastReply) {
        return new Ticket(t.id(), t.uuid(), t.name(), t.message(), t.location(), s, t.createdAt(),
            closedBy != null ? closedBy : t.closedBy(), lastReply);
    }

    private static void mail(MinecraftServer server, Ticket t, String subject, String body) {
        server.execute(() -> MailService.sendSystemReward(server, t.uuid(), trim(subject, 64), trim(body, 1024),
            List.of(), 0, null, ok -> {
                if (!ok) {
                    CoffeesAeroAuth.LOGGER.warn("[Ticket] mail for #{} to {} NOT stored", t.id(), t.name());
                    tell(server, t.uuid(), "§6🎫 " + subject + ": §f" + body);
                }
            }));
    }

    // ── JSON ────────────────────────────────────────────────────────────────────

    static String messageJson(Ticket t) {
        return "{" + bodyJson(t) + "}";
    }

    private static String update(Ticket t) {
        return "{\"type\":7,\"data\":{" + bodyJson(t) + "}}";
    }

    private static String bodyJson(Ticket t) {
        int color = switch (t.status()) { case OPEN -> OPEN; case ACK -> ACK; case CLOSED -> CLOSED; };
        String status = switch (t.status()) {
            case OPEN -> "🟦 Open";
            case ACK -> "🟨 Acknowledged";
            case CLOSED -> "⬛ Closed by " + (t.closedBy() == null ? "staff" : t.closedBy());
        };
        StringBuilder fields = new StringBuilder()
            .append(field("Player", t.name(), true))
            .append(',').append(field("Location", "`" + t.location() + "`", true))
            .append(',').append(field("Status", status, true));
        if (t.lastReply() != null && !t.lastReply().isBlank())
            fields.append(',').append(field("Last reply", trim(t.lastReply(), 1000), false));
        String embed = "{\"title\":\"🎫 Ticket #" + t.id() + "\",\"description\":\"" + esc(t.message()) + "\","
            + "\"color\":" + color + ",\"fields\":[" + fields + "],"
            + "\"timestamp\":\"" + Instant.ofEpochMilli(t.createdAt()) + "\"}";
        String components = t.status() == Status.CLOSED ? "[]"
            : "[{\"type\":1,\"components\":["
              + button(3, "✅ Acknowledge", "tkack", t.id(), t.status() == Status.ACK) + ","
              + button(1, "✉ Reply", "tkreply", t.id(), false) + ","
              + button(4, "🔒 Close", "tkclose", t.id(), false) + "]}]";
        return "\"embeds\":[" + embed + "],\"components\":" + components;
    }

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(value) + "\",\"inline\":" + inline + "}";
    }

    private static String button(int style, String label, String action, long id, boolean disabled) {
        return "{\"type\":2,\"style\":" + style + ",\"label\":\"" + esc(label) + "\",\"custom_id\":\""
            + action + ":" + NameApprovalQueue.originTag() + ":" + id + "\",\"disabled\":" + disabled + "}";
    }

    private static String modal(String action, long id, String title, String field, String label, boolean required) {
        return "{\"type\":9,\"data\":{\"custom_id\":\"" + action + ":" + NameApprovalQueue.originTag() + ":" + id + "\","
            + "\"title\":\"" + esc(title) + "\",\"components\":[{\"type\":1,\"components\":[{"
            + "\"type\":4,\"custom_id\":\"" + field + "\",\"label\":\"" + esc(label) + "\","
            + "\"style\":2,\"max_length\":900,\"required\":" + required + "}]}]}}";
    }

    private static String ephemeral(String text) {
        return "{\"type\":4,\"data\":{\"flags\":64,\"content\":\"" + esc(text) + "\"}}";
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                case '\t' -> b.append(' ');
                default   -> { if (ch < 0x20) b.append(' '); else b.append(ch); }
            }
        }
        return b.toString();
    }
}
