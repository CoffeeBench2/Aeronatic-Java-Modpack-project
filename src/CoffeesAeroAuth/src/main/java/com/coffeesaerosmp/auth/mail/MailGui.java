package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One open mailbox: an inbox view (45 mails a page) and a letter view (the message + its attachments).
 *
 * <pre>
 *  INBOX                                   LETTER
 *  [ mail × 45 ...................... ]    [ . . . . ✉ . . . . ]   ✉ = subject, sender, date, body
 *  [ ... ]                                  [ attachments ×27   ]   (display copies — never takeable)
 *  [◀][ ][⇩ Claim all][ ][ℹ][ ][🗑][ ][▶]   [↩ Back][ ][ ][ ][✔ Claim][ ][ ][ ][🗑 Delete]
 * </pre>
 *
 * Unclaimed parcels sort first and glow; read letters are plain paper; claimed parcels turn into an
 * empty minecart so the inbox doubles as a history.
 */
public final class MailGui {

    private static final int PER_PAGE = 45;
    private static final int B_PREV = 45, B_CLAIM_ALL = 47, B_INFO = 49, B_CLEAN = 51, B_NEXT = 53;
    private static final int B_BACK = 45, B_READ = 47, B_CLAIM = 49, B_DELETE = 53, LETTER = 4, ATTACH_FROM = 18;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault());

    private final ServerPlayer player;
    private final SimpleContainer display = new SimpleContainer(MailMenu.SIZE);
    private List<MailStore.Mail> mails = List.of();
    private int page;
    private MailStore.Mail open;           // non-null = letter view
    private boolean busy;                  // a DB call is in flight: ignore clicks

    private MailGui(ServerPlayer player) {
        this.player = player;
    }

    /** /mail — load the inbox off-thread, then open. */
    public static void open(ServerPlayer player) {
        MailGui gui = new MailGui(player);
        MailStore.inbox(player.getServer(), player.getUUID(), list -> {
            if (player.hasDisconnected()) return;
            gui.mails = list;
            gui.renderInbox();
            player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new MailMenu(id, inv, gui.display, gui::onSlot),
                Component.literal("✉ Mailbox")));
        });
    }

    // ── clicks ───────────────────────────────────────────────────────────────

    private void onSlot(int slot) {
        if (busy) return;
        if (open == null) onInboxSlot(slot); else onLetterSlot(slot);
    }

    private void onInboxSlot(int slot) {
        if (slot < PER_PAGE) {
            int i = page * PER_PAGE + slot;
            if (i >= mails.size()) return;
            open = mails.get(i);
            if (!open.read()) {
                MailStore.markRead(player.getUUID(), open.id());
                open = copyRead(open);
                mails = replace(mails, open);
            }
            click();
            renderLetter();
            return;
        }
        switch (slot) {
            case B_PREV -> { if (page > 0) { page--; click(); renderInbox(); } }
            case B_NEXT -> { if ((page + 1) * PER_PAGE < mails.size()) { page++; click(); renderInbox(); } }
            case B_CLAIM_ALL -> claimAll();
            case B_CLEAN -> {
                busy = true;
                MailStore.deleteAllClaimed(player.getServer(), player.getUUID(), this::reload);
            }
            default -> { }
        }
    }

    private void onLetterSlot(int slot) {
        switch (slot) {
            case B_BACK -> { open = null; click(); renderInbox(); }
            case B_READ -> MailBook.open(player, open.sender(), open.createdAt(), open.body());
            case B_CLAIM -> { if (open.hasAttachments() && !open.claimed()) claim(open, false); }
            case B_DELETE -> {
                if (open.hasAttachments() && !open.claimed()) return;   // never bin an unclaimed parcel
                busy = true;
                long id = open.id();
                open = null;
                MailStore.delete(player.getServer(), player.getUUID(), id, this::reload);
            }
            default -> { }
        }
    }

    // ── claiming ─────────────────────────────────────────────────────────────

    private void claim(MailStore.Mail m, boolean continueAll) {
        var regs = player.getServer().registryAccess();
        int need = MailItems.slotsNeeded(MailItems.decode(m.itemsSnbt(), regs), m.spurs());
        int free = MailItems.freeSlots(player);
        if (free < need) {
            player.sendSystemMessage(Component.literal("§c✉ Make room first — this mail needs §f" + need
                + "§c free inventory slots, you have §f" + free + "§c."));
            if (continueAll) reload();
            return;
        }
        busy = true;
        MailStore.claim(player.getServer(), player.getUUID(), m.id(), won -> {
            if (won == null) {
                player.sendSystemMessage(Component.literal("§7✉ That mail was already claimed."));
            } else if (!player.hasDisconnected()) {
                // Give from the row the claim returned, never from what the GUI happened to display.
                MailItems.give(player, MailItems.decode(won.itemsSnbt(), regs), won.spurs());
                player.sendSystemMessage(Component.literal("§a✉ Claimed §f" + won.subject()
                    + (won.spurs() > 0 ? " §7(+" + won.spurs() + " spurs)" : "")));
                player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP,
                    SoundSource.PLAYERS, 0.5f, 1.4f);
            } else {
                CoffeesAeroAuth.LOGGER.warn("[Mail] {} disconnected mid-claim of mail {} — marked claimed, NOT "
                    + "delivered. Resend it.", player.getGameProfile().getName(), m.id());
            }
            if (continueAll) {
                busy = false;
                claimAll();
            } else {
                open = null;
                reload();
            }
        });
    }

    private void claimAll() {
        for (MailStore.Mail m : mails) {
            if (m.hasAttachments() && !m.claimed()) {
                mails = replace(mails, new MailStore.Mail(m.id(), m.sender(), m.subject(), m.body(), m.itemsSnbt(),
                    m.spurs(), m.createdAt(), m.expiresAt(), true, true));   // optimistic: skip it next round
                claim(m, true);
                return;
            }
        }
        reload();
    }

    private void reload() {
        MailStore.inbox(player.getServer(), player.getUUID(), list -> {
            busy = false;
            mails = list;
            if (page * PER_PAGE >= Math.max(1, mails.size())) page = Math.max(0, (mails.size() - 1) / PER_PAGE);
            if (open != null) {
                open = mails.stream().filter(x -> x.id() == open.id()).findFirst().orElse(null);
            }
            if (open == null) renderInbox(); else renderLetter();
        });
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private void renderInbox() {
        display.clearContent();
        int from = page * PER_PAGE;
        for (int s = 0; s < PER_PAGE && from + s < mails.size(); s++) {
            display.setItem(s, mailIcon(mails.get(from + s)));
        }
        fillBottomRow();
        int unclaimed = (int) mails.stream().filter(m -> m.hasAttachments() && !m.claimed()).count();
        int unread = (int) mails.stream().filter(m -> !m.read()).count();
        int pages = Math.max(1, (mails.size() + PER_PAGE - 1) / PER_PAGE);
        if (page > 0) display.setItem(B_PREV, button(Items.ARROW, "§e◀ Previous page"));
        if (page + 1 < pages) display.setItem(B_NEXT, button(Items.ARROW, "§eNext page ▶"));
        display.setItem(B_CLAIM_ALL, button(unclaimed > 0 ? Items.CHEST : Items.ENDER_CHEST,
            unclaimed > 0 ? "§a⇩ Claim all §7(" + unclaimed + ")" : "§7Nothing to claim"));
        display.setItem(B_INFO, button(Items.WRITABLE_BOOK, "§6✉ Mailbox",
            "§7" + mails.size() + " mail, §f" + unread + " §7unread, §f" + unclaimed + " §7to claim",
            "§7Page §f" + (page + 1) + "§7/§f" + pages,
            "§8Letters from players, rewards and level-ups arrive here.",
            "§8Write one with §f/mail send <player>§8 · help: §f/mail help"));
        display.setItem(B_CLEAN, button(Items.LAVA_BUCKET, "§c🗑 Clear claimed mail",
            "§7Deletes every claimed parcel and read letter.", "§7Unclaimed rewards are never deleted."));
    }

    private void renderLetter() {
        display.clearContent();
        MailStore.Mail m = open;
        List<Component> lore = new ArrayList<>();
        lore.add(gray("From §f" + m.sender() + " §7· " + DATE.format(Instant.ofEpochMilli(m.createdAt()))));
        if (m.expiresAt() > 0 && !m.claimed()) {
            lore.add(gray("Expires §f" + DATE.format(Instant.ofEpochMilli(m.expiresAt()))));
        }
        lore.add(Component.empty());
        for (String line : wrap(m.body(), 38)) lore.add(plain("§f" + line));
        display.setItem(LETTER, withLore(named(new ItemStack(Items.WRITTEN_BOOK), "§6✉ Letter from " + m.sender()), lore));

        var regs = player.getServer().registryAccess();
        int slot = ATTACH_FROM;
        if (m.spurs() > 0) {
            display.setItem(slot++, withLore(named(new ItemStack(Items.GOLD_NUGGET), "§e" + m.spurs() + " spurs"),
                List.of(gray("Paid as Numismatics coins."))));
        }
        for (ItemStack s : MailItems.decode(m.itemsSnbt(), regs)) {
            if (slot >= B_BACK) break;
            display.setItem(slot++, s.copy());
        }
        fillBottomRow();
        display.setItem(B_BACK, button(Items.ARROW, "§e↩ Back to inbox"));
        display.setItem(B_READ, button(Items.WRITTEN_BOOK, "§6📖 Read as a book", "§7Opens the letter like a real book.",
            "§8Type /mail to come back to your mailbox."));
        if (m.hasAttachments() && !m.claimed()) {
            int need = MailItems.slotsNeeded(MailItems.decode(m.itemsSnbt(), regs), m.spurs());
            display.setItem(B_CLAIM, glow(button(Items.LIME_DYE, "§a✔ Claim", "§7Needs §f" + need + " §7free slots.")));
        } else if (m.claimed()) {
            display.setItem(B_CLAIM, button(Items.GRAY_DYE, "§7Already claimed"));
        }
        if (!(m.hasAttachments() && !m.claimed())) {
            display.setItem(B_DELETE, button(Items.LAVA_BUCKET, "§c🗑 Delete"));
        }
    }

    private ItemStack mailIcon(MailStore.Mail m) {
        boolean parcel = m.hasAttachments() && !m.claimed();
        Item icon = parcel ? Items.CHEST : m.read() ? Items.PAPER : Items.WRITABLE_BOOK;
        List<Component> lore = new ArrayList<>();
        lore.add(plain("§f“" + m.subject() + "”"));
        lore.add(gray(DATE.format(Instant.ofEpochMilli(m.createdAt()))));
        if (parcel) {
            int stacks = MailItems.decode(m.itemsSnbt(), player.getServer().registryAccess()).size();
            lore.add(plain("§a📦 " + (stacks > 0 ? stacks + " item stack" + (stacks == 1 ? "" : "s") : "")
                + (m.spurs() > 0 ? (stacks > 0 ? " + " : "") + "§e" + m.spurs() + " spurs" : "") + " §ato claim"));
        } else if (m.hasAttachments()) {
            lore.add(plain("§8Claimed"));
        }
        lore.add(plain(m.read() ? "§8Click to open" : "§b● New — click to open"));
        ItemStack s = withLore(named(new ItemStack(icon), (m.read() ? "§7✉ From §f" : "§e§l✉ From ") + m.sender()), lore);
        return (!m.read() || parcel) ? glow(s) : s;
    }

    private void fillBottomRow() {
        for (int s = PER_PAGE; s < MailMenu.SIZE; s++) {
            display.setItem(s, named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " "));
        }
    }

    // ── item helpers ─────────────────────────────────────────────────────────

    private static ItemStack button(Item item, String name, String... lore) {
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(plain(s));
        return withLore(named(new ItemStack(item), name), l);
    }

    private static ItemStack named(ItemStack s, String name) {
        s.set(DataComponents.CUSTOM_NAME, plain(name));
        return s;
    }

    private static ItemStack withLore(ItemStack s, List<Component> lore) {
        if (!lore.isEmpty()) s.set(DataComponents.LORE, new ItemLore(lore));
        return s;
    }

    private static ItemStack glow(ItemStack s) {
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    /** Item names/lore are italic by default; mail text should not be. */
    private static MutableComponent plain(String text) {
        return Component.literal(text).withStyle(st -> st.withItalic(false));
    }

    private static MutableComponent gray(String text) {
        return plain("§7" + text).withStyle(ChatFormatting.GRAY);
    }

    /** Word-wrap for lore. Honours explicit newlines (staff type {@code \n} in /mail admin). */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String para : text.replace("\\n", "\n").split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }

    private static MailStore.Mail copyRead(MailStore.Mail m) {
        return new MailStore.Mail(m.id(), m.sender(), m.subject(), m.body(), m.itemsSnbt(), m.spurs(),
            m.createdAt(), m.expiresAt(), true, m.claimed());
    }

    private static List<MailStore.Mail> replace(List<MailStore.Mail> list, MailStore.Mail m) {
        List<MailStore.Mail> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) if (out.get(i).id() == m.id()) out.set(i, m);
        return out;
    }

    private void click() {
        player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.4f, 1.0f);
    }
}
