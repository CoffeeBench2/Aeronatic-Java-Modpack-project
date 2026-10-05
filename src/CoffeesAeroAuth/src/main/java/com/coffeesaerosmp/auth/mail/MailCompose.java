package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.chat.ChatFilter;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writing a letter: {@code /mail send <player>} → type the letter in chat → pack the parcel → Send.
 *
 * <h2>Chat capture</h2>
 * {@link #onChat} runs at HIGHEST priority and cancels the event while a player is writing, so the
 * letter never reaches public chat, the Discord bridge or the console (all of them sit behind
 * {@code ChatEvents} at normal priority, which does not receive cancelled events). The chat filter is
 * applied here instead: a BLOCK word refuses the letter, a CENSOR word is starred out.
 *
 * <h2>Who may send what</h2>
 * Players: one recipient, {@link MailRules#DAILY_SENDS} per rolling 24 h, their items MOVE into the
 * parcel. Staff (permission 3, {@code /mail admin …}): any number of recipients, no limit, and the
 * packed items are COPIED to every recipient — the staff member's own stacks come back on close.
 * Nobody can mail Numismatics (see {@link MailRules}).
 */
public final class MailCompose {

    private MailCompose() {}

    private enum Stage { WRITING, PACKING }

    private static final class Draft {
        final List<UUID> to;
        final String toLabel;
        final boolean staff;
        final long startedAt = System.currentTimeMillis();
        Stage stage = Stage.WRITING;
        String letter = "";
        long armedAt;               // broadcast confirm: first Send click time
        boolean sending;
        Draft(List<UUID> to, String toLabel, boolean staff) { this.to = to; this.toLabel = toLabel; this.staff = staff; }
    }

    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();

    private static final int B_LETTER = 13, B_CANCEL = 18, B_HELP = 22, B_SEND = 26;

    // ── starting a letter ────────────────────────────────────────────────────

    /** Opens a draft. Caller has already resolved recipients and checked permissions. */
    public static void begin(ServerPlayer player, List<UUID> to, String toLabel, boolean staff) {
        DRAFTS.put(player.getUUID(), new Draft(List.copyOf(to), toLabel, staff));
        player.sendSystemMessage(Component.literal("§6✉ ─────────── §fNew letter §6───────────"));
        player.sendSystemMessage(Component.literal("§7To: §f" + toLabel
            + (to.size() > 1 ? " §7(" + to.size() + " players)" : "")));
        player.sendSystemMessage(Component.literal("§eType your letter in chat now§7 — up to "
            + MailRules.LETTER_MAX + " characters. Only you can see it. ").append(link("§c[cancel]", "cancel")));
        player.playNotifySound(SoundEvents.BOOK_PAGE_TURN, SoundSource.MASTER, 0.8f, 1.0f);
    }

    public static boolean isWriting(UUID player) {
        Draft d = DRAFTS.get(player);
        return d != null && d.stage == Stage.WRITING;
    }

    // ── chat capture ─────────────────────────────────────────────────────────

    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        Draft d = DRAFTS.get(player.getUUID());
        if (d == null || d.stage != Stage.WRITING) return;
        if (System.currentTimeMillis() - d.startedAt > MailRules.DRAFT_TIMEOUT_MS) {
            DRAFTS.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("§7✉ Your letter to §f" + d.toLabel
                + "§7 timed out, so that message went to chat as normal."));
            return;                                                 // let it through as ordinary chat
        }
        event.setCanceled(true);
        String text = event.getRawText().trim();
        if (text.equalsIgnoreCase("cancel")) {
            DRAFTS.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("§7✉ Letter cancelled. Nothing was sent."));
            return;
        }
        String problem = MailRules.letterProblem(text);
        if (problem != null) {
            player.sendSystemMessage(Component.literal("§c✉ " + problem));
            return;
        }
        ChatFilter.Result screened = ChatFilter.check(text);
        if (screened != null && screened.action() == ChatFilter.Action.BLOCK) {
            player.sendSystemMessage(Component.literal(
                "§c✉ That letter has a word that isn't allowed here. Write it again, or type §fcancel§c."));
            return;
        }
        d.letter = screened != null ? screened.text() : text;
        d.stage = Stage.PACKING;
        MinecraftServer server = player.getServer();
        if (server != null) server.execute(() -> openParcel(player, d));
    }

    /** Logout: items in an open parcel go back BEFORE the player is saved; the draft is dropped. */
    public static void onLogout(ServerPlayer player) {
        DRAFTS.remove(player.getUUID());
        if (player.containerMenu instanceof ParcelMenu pm) pm.returnItems(player);
    }

    // ── the parcel window ────────────────────────────────────────────────────

    private static void openParcel(ServerPlayer player, Draft d) {
        if (player.hasDisconnected()) return;
        SimpleContainer box = new SimpleContainer(ParcelMenu.SIZE);
        ParcelMenu[] menu = new ParcelMenu[1];
        render(box, d, false);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            menu[0] = new ParcelMenu(id, inv, box, player, slot -> onButton(player, d, box, menu[0], slot)) {
                @Override
                public void removed(net.minecraft.world.entity.player.Player pl) {
                    super.removed(pl);
                    if (!d.sending) DRAFTS.remove(player.getUUID(), d);
                }
            };
            return menu[0];
        }, Component.literal("✉ Parcel for " + d.toLabel)));
        player.sendSystemMessage(Component.literal("§a✉ Letter written. §7Drop up to " + ParcelMenu.PARCEL
            + " stacks in the top row if you want to send items, then press §a✉ Send§7."));
    }

    private static void onButton(ServerPlayer player, Draft d, SimpleContainer box, ParcelMenu menu, int slot) {
        switch (slot) {
            case B_CANCEL -> {
                player.closeContainer();                               // removed() returns the items
                player.sendSystemMessage(Component.literal("§7✉ Letter cancelled. Your items are back in your inventory."));
            }
            case B_SEND -> send(player, d, box, menu);
            default -> { }
        }
    }

    private static void send(ServerPlayer player, Draft d, SimpleContainer box, ParcelMenu menu) {
        if (d.sending) return;
        MinecraftServer server = player.getServer();
        var regs = server.registryAccess();
        List<ItemStack> items = menu.packed();
        for (ItemStack s : items) {
            if (MailItems.containsBlocked(s, regs)) {                  // belt and braces: slots already refuse
                player.sendSystemMessage(Component.literal("§c✉ Take the coins out of the parcel first."));
                return;
            }
        }
        if (d.to.size() > 1 && System.currentTimeMillis() - d.armedAt > 30_000) {
            d.armedAt = System.currentTimeMillis();                    // a broadcast needs a second click
            render(box, d, true);
            menu.broadcastChanges();
            return;
        }
        d.sending = true;
        if (d.staff) {
            deliver(player, d, items, menu);
            return;
        }
        MailStore.countSentSince(server, player.getUUID(), System.currentTimeMillis() - 86_400_000L, n -> {
            if (n < 0) {
                d.sending = false;
                player.sendSystemMessage(Component.literal("§c✉ The Mail Office is closed right now (database). Try again soon."));
            } else if (MailRules.limitReached(n, MailRules.DAILY_SENDS)) {
                d.sending = false;
                player.sendSystemMessage(Component.literal("§c✉ You've sent " + MailRules.DAILY_SENDS
                    + " letters in the last 24 hours. The Mail Office needs a break — try again later!"));
            } else if (!player.hasDisconnected() && player.containerMenu == menu) {
                deliver(player, d, menu.packed(), menu);
            } else {
                d.sending = false;                                     // window closed meanwhile: items already returned
            }
        });
    }

    private static void deliver(ServerPlayer player, Draft d, List<ItemStack> items, ParcelMenu menu) {
        MinecraftServer server = player.getServer();
        String snbt = MailItems.encode(items, server.registryAccess());
        if (!d.staff) menu.takeAll();                                  // the items now belong to the mail
        DRAFTS.remove(player.getUUID(), d);
        player.closeContainer();                                       // staff: removed() hands their originals back

        String sender = senderName(player);
        MailStore.send(server, d.to, new MailStore.Outgoing(sender, MailRules.preview(d.letter), d.letter, snbt,
            0, MailService.expiry(), null, player.getUUID()), sent -> {
                if (sent > 0) {
                    player.sendSystemMessage(Component.literal("§a✉ Sent to §f" + d.toLabel
                        + (d.to.size() > 1 ? " §7(" + sent + " players)" : "") + "§a!"
                        + (items.isEmpty() ? "" : " §7" + items.size() + " stack(s) packed.")));
                    player.playNotifySound(SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.MASTER, 0.8f, 1.2f);
                    for (UUID u : d.to) MailService.notifyNew(server.getPlayerList().getPlayer(u), 1);
                    if (!items.isEmpty()) audit(player, d, items, Severity.LOW, "Mail parcel sent");
                } else if (!d.staff && !items.isEmpty()) {
                    // Nothing was written: the items must come back.
                    if (!player.hasDisconnected()) {
                        MailItems.give(player, items, 0);
                        player.sendSystemMessage(Component.literal("§c✉ Couldn't send right now — your items are back."));
                    } else {
                        audit(player, d, items, Severity.HIGH,
                            "Mail send FAILED while the sender was offline — RESTORE these items: " + snbt);
                    }
                } else {
                    player.sendSystemMessage(Component.literal("§c✉ Couldn't send right now. Try again soon."));
                }
            });
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private static void render(SimpleContainer box, Draft d, boolean armed) {
        for (int i = ParcelMenu.PARCEL; i < ParcelMenu.SIZE; i++) {
            box.setItem(i, named(new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE), " "));
        }
        List<String> lore = new ArrayList<>();
        lore.add("§7To §f" + d.toLabel);
        lore.add("");
        for (List<String> page : MailRules.bookPages(d.letter, 32, 99)) for (String l : page) lore.add("§f" + l);
        box.setItem(B_LETTER, named(new ItemStack(Items.WRITABLE_BOOK), "§6✎ Your letter", lore.toArray(String[]::new)));
        box.setItem(B_CANCEL, named(new ItemStack(Items.BARRIER), "§c✗ Cancel", "§7Nothing is sent and your items come back."));
        box.setItem(B_HELP, named(new ItemStack(Items.OAK_SIGN), "§eWhat can I send?",
            "§7Up to §f" + ParcelMenu.PARCEL + "§7 stacks of almost anything.",
            "§cNo coins or Numismatics items §7— not even",
            "§7inside a shulker box. Coins change hands",
            "§7in person, or at a shop or vendor.",
            d.staff ? "§7Staff mail: items are §fcopied§7 to everyone." : "§7Unclaimed parcels come back to you after a while."));
        Item sendIcon = armed ? Items.RED_CONCRETE : Items.LIME_CONCRETE;
        box.setItem(B_SEND, armed
            ? named(new ItemStack(sendIcon), "§c§l⚠ Click again to mail " + d.to.size() + " players", "§7Within 30 seconds.")
            : named(new ItemStack(sendIcon), "§a§l✉ Send", "§7To §f" + d.toLabel));
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

    static MutableComponent link(String text, String chat) {
        return Component.literal(text).withStyle(Style.EMPTY
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, chat))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click, then press Enter"))));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String senderName(ServerPlayer player) {
        try {
            PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
            if (p != null && p.displayName != null && !p.displayName.isBlank()) return p.displayName;
        } catch (Exception ignored) { }
        return player.getGameProfile().getName();
    }

    private static void audit(ServerPlayer player, Draft d, List<ItemStack> items, Severity sev, String title) {
        if (CoffeesAeroAuth.WATCHDOG == null) return;
        StringBuilder summary = new StringBuilder();
        for (ItemStack s : items) {
            if (summary.length() > 0) summary.append(", ");
            summary.append(s.getCount()).append("× ").append(s.getHoverName().getString());
        }
        CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(sev, title, d.staff ? "Staff mail (copied)" : "Logged",
            "From", player.getGameProfile().getName(), "To", d.toLabel, "Items", summary.toString()));
    }
}
