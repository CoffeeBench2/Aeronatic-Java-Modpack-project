package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.mail.SystemMail.Audience;
import com.coffeesaerosmp.auth.mail.SystemMail.Type;
import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /mail system}: staff send a Warning / Update / Patch notes / Announcement mail as the SERVER
 * ({@link MailService#SYSTEM}), never under their own name (owner 2026-10-10). The body is a Book & Quill
 * dropped in the window; online recipients also get an on-screen title.
 *
 * <h2>Item custody</h2>
 * The book and attachments live in the DRAFT's container, not the window. Typing a subject or a player
 * name, and the preview book, all close the window — the items stay with the draft and
 * {@code /mail system} reopens it exactly as it was. They go back to the staff member on Cancel, on Send
 * (attachments are COPIED to recipients, like staff parcels), and on logout — before the player is saved.
 */
public final class SystemMailCompose {

    private SystemMailCompose() {}

    private enum Awaiting { NONE, SUBJECT, PLAYER, PREVIEW }

    static final class Draft {
        Type type = Type.UPDATE;
        Audience audience = Audience.ONLINE;
        String subject = "";
        String playerName = "";
        UUID playerUuid;
        final SimpleContainer box = new SimpleContainer(SystemMailMenu.SIZE);
        Awaiting awaiting = Awaiting.NONE;
        long awaitingSince;
        long armedAt;
        boolean sending;
    }

    static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();

    static final int B_TYPE = 0, B_TO = 1, B_SUBJECT = 2, B_PREVIEW = 6, B_CANCEL = 7, B_SEND = 8, B_HELP = 22;
    private static final long INPUT_TIMEOUT_MS = 5 * 60_000L;

    // ── opening ──────────────────────────────────────────────────────────────

    /** Opens the window — the existing draft if there is one, so nothing typed or dropped is lost. */
    public static void open(ServerPlayer player) {
        Draft d = DRAFTS.computeIfAbsent(player.getUUID(), u -> new Draft());
        d.awaiting = Awaiting.NONE;
        render(player, d);
        SystemMailMenu[] menu = new SystemMailMenu[1];
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            menu[0] = new SystemMailMenu(id, inv, d.box, player,
                slot -> onButton(player, d, menu[0], slot),
                () -> { render(player, d); if (menu[0] != null) menu[0].broadcastChanges(); },
                () -> onClosed(player, d));
            return menu[0];
        }, Component.literal("✉ System Mail")));
    }

    private static void onClosed(ServerPlayer player, Draft d) {
        if (d.awaiting != Awaiting.NONE || d.sending) return;     // typing / previewing: the draft keeps them
        SystemMailMenu.returnItems(d.box, player);
        DRAFTS.remove(player.getUUID(), d);
    }

    /** Logout or server stop: everything goes back BEFORE the player is saved. */
    public static void onLogout(ServerPlayer player) {
        Draft d = DRAFTS.remove(player.getUUID());
        if (d != null) SystemMailMenu.returnItems(d.box, player);
    }

    // ── buttons ──────────────────────────────────────────────────────────────

    private static void onButton(ServerPlayer player, Draft d, SystemMailMenu menu, int slot) {
        switch (slot) {
            case B_TYPE -> d.type = d.type.next();
            case B_TO -> {
                d.audience = d.audience.next();
                d.armedAt = 0;
                if (d.audience == Audience.ONE) { ask(player, d, Awaiting.PLAYER); return; }
            }
            case B_SUBJECT -> { ask(player, d, Awaiting.SUBJECT); return; }
            case B_PREVIEW -> { preview(player, d); return; }
            case B_CANCEL -> {
                player.closeContainer();                          // onClosed returns the items
                player.sendSystemMessage(Component.literal("§7✉ System mail cancelled. Your items are back."));
                return;
            }
            case B_SEND -> { send(player, d, menu); return; }
            default -> { return; }
        }
        render(player, d);
        menu.broadcastChanges();
    }

    private static void ask(ServerPlayer player, Draft d, Awaiting what) {
        d.awaiting = what;
        d.awaitingSince = System.currentTimeMillis();
        player.closeContainer();
        player.sendSystemMessage(Component.literal(what == Awaiting.SUBJECT
                ? "§6✉ Type the §fsubject§6 in chat §7(up to " + SystemMail.SUBJECT_MAX + " characters, only you see it). "
                : "§6✉ Type the §fplayer's name§6 in chat§7 (only you see it). ")
            .append(link("§c[cancel]", "cancel")));
        player.playNotifySound(SoundEvents.BOOK_PAGE_TURN, SoundSource.MASTER, 0.8f, 1.0f);
    }

    // ── chat capture (HIGHEST, cancels — never reaches public chat, Discord or the console) ────

    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        Draft d = DRAFTS.get(player.getUUID());
        if (d == null || (d.awaiting != Awaiting.SUBJECT && d.awaiting != Awaiting.PLAYER)) return;
        if (System.currentTimeMillis() - d.awaitingSince > INPUT_TIMEOUT_MS) {
            d.awaiting = Awaiting.PREVIEW;                         // stop capturing, keep the items with the draft
            player.sendSystemMessage(Component.literal("§7✉ System mail timed out waiting, so that went to chat. ")
                .append(link("§e[reopen]", "/mail system")));
            return;
        }
        event.setCanceled(true);
        String text = event.getRawText().trim();
        MinecraftServer server = player.getServer();
        if (!text.equalsIgnoreCase("cancel")) {
            if (d.awaiting == Awaiting.SUBJECT) {
                if (text.length() > SystemMail.SUBJECT_MAX) {
                    player.sendSystemMessage(Component.literal("§c✉ That's " + text.length() + " characters, the limit is "
                        + SystemMail.SUBJECT_MAX + ". Type a shorter one."));
                    return;
                }
                d.subject = text;
            } else {
                UUID u = MailService.resolve(server, text);
                if (u == null) {
                    player.sendSystemMessage(Component.literal("§c✉ Nobody called '" + text + "' has played here. Type another name, or cancel."));
                    return;
                }
                d.playerUuid = u;
                d.playerName = text;
            }
        }
        if (server != null) server.execute(() -> { if (!player.hasDisconnected()) open(player); });
    }

    // ── preview ──────────────────────────────────────────────────────────────

    private static void preview(ServerPlayer player, Draft d) {
        String body = body(d);
        if (body.isBlank()) {
            player.sendSystemMessage(Component.literal("§c✉ Put a Book & Quill with the message in the book slot first."));
            return;
        }
        d.awaiting = Awaiting.PREVIEW;
        MailBook.open(player, MailService.SYSTEM, System.currentTimeMillis(), d.type.header() + body);
        player.sendSystemMessage(Component.literal("§7✉ That's how players will see it. ")
            .append(link("§e[back to System Mail]", "/mail system")));
    }

    // ── sending ──────────────────────────────────────────────────────────────

    private static void send(ServerPlayer player, Draft d, SystemMailMenu menu) {
        if (d.sending) return;
        String body = body(d);
        String problem = SystemMail.problem(d.subject, body, d.audience, d.playerName);
        if (problem != null) {
            player.sendSystemMessage(Component.literal("§c✉ " + problem));
            player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.8f, 1.0f);
            return;
        }
        MinecraftServer server = player.getServer();
        List<UUID> to = recipients(server, d);
        if (to.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c✉ Nobody to send to."));
            return;
        }
        List<ItemStack> items = menu.attachments();
        for (ItemStack s : items) {
            if (MailItems.containsBlocked(s, server.registryAccess())) {
                player.sendSystemMessage(Component.literal("§c✉ Take the coins out of the attachments first."));
                return;
            }
        }
        if (d.audience != Audience.ONE && System.currentTimeMillis() - d.armedAt > 30_000) {
            d.armedAt = System.currentTimeMillis();                // a broadcast needs a second click
            render(player, d, to.size());
            menu.broadcastChanges();
            player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 0.8f, 0.6f);
            return;
        }
        d.sending = true;
        Type type = d.type;
        String subject = type.subject(d.subject);
        String snbt = MailItems.encode(items, server.registryAccess());
        String label = d.audience == Audience.ONE ? d.playerName : d.audience.label.toLowerCase(java.util.Locale.ROOT);

        // Originals go home now: recipients get copies (the staff-parcel rule).
        DRAFTS.remove(player.getUUID(), d);
        SystemMailMenu.returnItems(d.box, player);
        player.closeContainer();

        MailStore.send(server, to, new MailStore.Outgoing(MailService.SYSTEM, subject, type.header() + body, snbt,
            0, MailService.expiry(), null, null), sent -> {
                if (sent < 0) {
                    player.sendSystemMessage(Component.literal("§c✉ Couldn't send right now (database). Nothing was sent; try again."));
                    return;
                }
                titles(server, to, type, d.subject);
                player.sendSystemMessage(Component.literal("§a✉ " + type.icon + " " + type.label + " sent to §f" + label
                    + " §7(" + sent + " mailbox" + (sent == 1 ? "" : "es") + ")"
                    + (items.isEmpty() ? "" : ", " + items.size() + " stack(s) attached") + "."));
                player.playNotifySound(SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.MASTER, 0.8f, 1.2f);
                audit(player, type, subject, label, sent, items);
            });
    }

    private static List<UUID> recipients(MinecraftServer server, Draft d) {
        List<UUID> to = new ArrayList<>();
        switch (d.audience) {
            case ONE -> { if (d.playerUuid != null) to.add(d.playerUuid); }
            case ONLINE -> server.getPlayerList().getPlayers().forEach(p -> to.add(p.getUUID()));
            case ALL -> {
                if (CoffeesAeroAuth.PROFILE_STORE != null)
                    for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll())
                        if (p.getUUID() != null) to.add(p.getUUID());
            }
        }
        return to;
    }

    /** The big on-screen title for everyone who received it and is online. */
    private static void titles(MinecraftServer server, List<UUID> to, Type type, String subject) {
        for (UUID u : to) {
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p == null || p.hasDisconnected()) continue;
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§" + type.color + "§l" + type.icon + " " + type.label)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§f" + subject + " §7· check /mail")));
            p.playNotifySound(type == Type.WARNING ? SoundEvents.NOTE_BLOCK_BELL.value() : SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                SoundSource.MASTER, 0.7f, 1.0f);
        }
    }

    private static void audit(ServerPlayer player, Type type, String subject, String label, int sent, List<ItemStack> items) {
        if (CoffeesAeroAuth.WATCHDOG == null) return;
        StringBuilder summary = new StringBuilder();
        for (ItemStack s : items) {
            if (summary.length() > 0) summary.append(", ");
            summary.append(s.getCount()).append("× ").append(s.getHoverName().getString());
        }
        CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(Severity.LOW,
            "System mail sent", type.label,
            "By", player.getGameProfile().getName(), "To", label + " (" + sent + ")", "Subject", subject,
            "Items", items.isEmpty() ? "none" : summary.toString()));
    }

    // ── book → body ──────────────────────────────────────────────────────────

    static String body(Draft d) {
        return bodyOf(d.box.getItem(SystemMailMenu.BOOK));
    }

    static String bodyOf(ItemStack book) {
        if (book.isEmpty()) return "";
        List<String> pages = new ArrayList<>();
        WritableBookContent w = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (w != null) for (Filterable<String> p : w.pages()) pages.add(p.raw());
        WrittenBookContent wr = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (wr != null) for (Filterable<Component> p : wr.pages()) pages.add(p.raw().getString());
        return SystemMail.bookText(pages);
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private static void render(ServerPlayer player, Draft d) {
        render(player, d, 0);
    }

    private static void render(ServerPlayer player, Draft d, int armedFor) {
        SimpleContainer box = d.box;
        for (int i = 0; i < SystemMailMenu.SIZE; i++) {
            if (SystemMailMenu.isButton(i)) box.setItem(i, named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " "));
        }
        Type t = d.type;
        box.setItem(B_TYPE, named(new ItemStack(switch (t) {
                case WARNING -> Items.RED_BANNER; case UPDATE -> Items.ORANGE_BANNER;
                case PATCH_NOTES -> Items.CYAN_BANNER; case ANNOUNCEMENT -> Items.LIME_BANNER; }),
            "§" + t.color + "§l" + t.icon + " " + t.label, "§7Click to change the type."));
        box.setItem(B_TO, named(new ItemStack(Items.PLAYER_HEAD),
            "§fTo: §e" + (d.audience == Audience.ONE ? (d.playerName.isBlank() ? "one player (not set)" : d.playerName) : d.audience.label),
            "§7Click to switch: one player, everyone online,", "§7every player."));
        box.setItem(B_SUBJECT, named(new ItemStack(Items.NAME_TAG),
            "§fSubject: " + (d.subject.isBlank() ? "§cnot set" : "§e" + d.subject),
            "§7Click, then type it in chat.", "§7Players see: §f" + t.subject(d.subject.isBlank() ? "…" : d.subject)));
        String body = body(d);
        box.setItem(B_PREVIEW, named(new ItemStack(Items.SPYGLASS), "§bPreview",
            body.isBlank() ? "§7Put a Book & Quill in the empty slot." : "§7" + body.length() + "/" + SystemMail.BODY_MAX + " characters.",
            "§7Opens the mail as players will see it."));
        box.setItem(B_CANCEL, named(new ItemStack(Items.BARRIER), "§c✗ Cancel", "§7Nothing is sent. Your book and items come back."));
        box.setItem(B_HELP, named(new ItemStack(Items.OAK_SIGN), "§eHow it works",
            "§71. Pick the type and who it goes to.",
            "§72. Set the subject.",
            "§73. Write the message in a Book & Quill and",
            "§7   drop it in the slot next to the subject.",
            "§74. Middle row: optional items, copied to everyone.",
            "§75. Preview, then Send. Sent as §f" + MailService.SYSTEM + "§7."));
        if (armedFor > 0) {
            box.setItem(B_SEND, named(new ItemStack(Items.RED_CONCRETE),
                "§c§l⚠ Click again to mail " + armedFor + " players", "§7Within 30 seconds."));
        } else {
            box.setItem(B_SEND, named(new ItemStack(Items.LIME_CONCRETE), "§a§l✉ Send",
                "§7" + t.icon + " " + t.label + " to §f" + (d.audience == Audience.ONE ? d.playerName : d.audience.label)));
        }
    }

    private static ItemStack named(ItemStack s, String name, String... lore) {
        s.set(DataComponents.CUSTOM_NAME, plain(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String x : lore) l.add(plain(x));
            s.set(DataComponents.LORE, new ItemLore(l));
        }
        return s;
    }

    private static MutableComponent plain(String t) {
        return Component.literal(t).withStyle(st -> st.withItalic(false));
    }

    private static MutableComponent link(String text, String command) {
        boolean run = command.startsWith("/");
        return Component.literal(text).withStyle(Style.EMPTY
            .withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(run ? "Click" : "Click, then press Enter"))));
    }
}
