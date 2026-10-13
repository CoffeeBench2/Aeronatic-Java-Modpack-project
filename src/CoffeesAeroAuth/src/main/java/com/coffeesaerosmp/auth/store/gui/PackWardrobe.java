package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.cosmetics.FittingRoom;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.NameRender;
import com.coffeesaerosmp.auth.store.Rank;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.store.gui.ButtonMenu.Click;
import com.coffeesaerosmp.auth.store.gui.ShopGui.Category;
import com.coffeesaerosmp.auth.store.gui.ShopGui.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Wardrobe for players WITHOUT AeroCore, drawn by the server resource pack: a full-screen menu (a "You" panel
 * with a window onto the mannequin, the list in the middle, details on the right) painted through a chest window's
 * title by {@link TitleCanvas}. The owner asked for the AeroCore Wardrobe look on every client (2026-10-12).
 *
 * <p>Slots, in a 6-row chest: row 0 = tabs; rows 1-5 and the three inventory rows = eight list rows (the icon in
 * the first cell, the whole row clickable); the hotbar = page arrows. The inventory area shows buttons, not the
 * player's items ({@link ButtonMenu}'s hidden-inventory mode).
 *
 * <p>Clicks on a row: left = select + preview on the mannequin, right = wear / take off / buy, shift = try in the
 * world. Everything that changes state reuses {@link ShopGui}'s actions.
 */
public final class PackWardrobe {

    private PackWardrobe() {}

    static final int PAGE = 8;
    private static final Category[] TABS = java.util.Arrays.stream(new Category[]{Category.TRAILS, Category.CONTRAILS,
            Category.COLOURS, Category.GRADIENTS, Category.EXTRAS, Category.WARDROBE})
        .filter(c -> c != Category.CONTRAILS || CosmeticCatalog.CONTRAILS_ON)    // contrails parked for a later update
        .toArray(Category[]::new);

    /** What each player last had selected (cleared on logout). */
    private static final Map<UUID, String> SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Category> LAST_TAB = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_PAGE = new ConcurrentHashMap<>();

    // colours (match the pack art)
    static final int GOLD = 0xF0C05A, WHITE = 0xFFFFFF, GREY = 0xA89A86, DIM = 0x7A6C5A, GREEN = 0x6FD872,
        PURPLE = 0xD08CFF, RED = 0xFF6A5A, YELLOW = 0xFFE08A;

    public static Category lastTab(UUID id) { return LAST_TAB.getOrDefault(id, Category.TRAILS); }

    public static void forget(UUID id) {
        SELECTED.remove(id);
        LAST_TAB.remove(id);
        LAST_PAGE.remove(id);
    }

    /** Draw and open (or redraw in place) the Wardrobe on {@code cat}, {@code page}. */
    static void show(ServerPlayer p, Category cat, int page, int bal) {
        List<Entry> all = ShopGui.entries(p, cat);
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        List<Entry> shown = all.subList(pg * PAGE, Math.min(all.size(), (pg + 1) * PAGE));
        LAST_TAB.put(p.getUUID(), cat);
        LAST_PAGE.put(p.getUUID(), pg);

        String selId = SELECTED.get(p.getUUID());
        Entry sel = null;
        for (Entry e : all) if (e.id().equals(selId)) sel = e;
        if (sel == null) for (Entry e : shown) if (e.equipped()) sel = e;
        if (sel == null && !shown.isEmpty()) sel = shown.get(0);
        if (sel != null) SELECTED.put(p.getUUID(), sel.id());

        // ── slots ──
        SimpleContainer top = new SimpleContainer(54);
        SimpleContainer lower = new SimpleContainer(36);
        for (int k = 0; k < TABS.length; k++) top.setItem(k, tabIcon(TABS[k], TABS[k] == cat));
        top.setItem(6, named(Items.NETHER_STAR, "Ranks", ChatFormatting.LIGHT_PURPLE, "What each rank includes"));
        top.setItem(7, named(Items.EMERALD, "Get Coffee Beans", ChatFormatting.GREEN, "Opens the store page"));
        top.setItem(8, named(Items.BARRIER, "Close", ChatFormatting.RED, null));
        String plainName = p.getGameProfile().getName();
        for (int k = 0; k < shown.size(); k++) put(top, lower, rowSlot(k), ShopGui.entryIcon(shown.get(k), plainName, bal));
        if (pg > 0) lower.setItem(27, named(Items.ARROW, "Previous page", ChatFormatting.WHITE, null));
        if (pg < pages - 1) lower.setItem(35, named(Items.ARROW, "Next page", ChatFormatting.WHITE, null));

        // ── title (the whole picture) ──
        Component title = paint(p, cat, pg, pages, shown, sel, bal);

        final Entry selected = sel;
        ButtonMenu.Handler h = (slot, click) -> {
            if (slot >= 0 && slot < 9) {
                if (slot < TABS.length) { SELECTED.remove(p.getUUID()); ShopGui.openCategory(p, TABS[slot], 0); }
                else if (slot == 6) ShopGui.showRanks(p);
                else if (slot == 7) { p.closeContainer(); ShopGui.storeLink(p); }
                else p.closeContainer();
                return;
            }
            if (slot == 54 + 27 && pg > 0) { ShopGui.openCategory(p, cat, pg - 1); return; }
            if (slot == 54 + 35 && pg < pages - 1) { ShopGui.openCategory(p, cat, pg + 1); return; }
            int row = rowOf(slot);
            if (row < 0 || row >= shown.size()) return;
            Entry e = shown.get(row);
            if (click == Click.LEFT) {                         // select: the doll and the details show it
                SELECTED.put(p.getUUID(), e.id());
                show(p, cat, pg, bal);
                return;
            }
            ShopGui.act(p, e, click, cat, pg, bal);
        };
        ButtonMenu.open(p, new SimpleMenuProvider((id, inv, pl) -> new ButtonMenu(id, inv, 6, top, h, lower), title), true);
        FittingRoom.close(p);                                  // the doll is the preview; nothing in the world
    }

    /** The skin arrived after the window was drawn: redraw it in place if it's still open. */
    private static void redrawIfOpen(ServerPlayer p) {
        if (p.hasDisconnected() || !(p.containerMenu instanceof ButtonMenu bm) || !bm.hidesInventory()) return;
        ShopGui.openCategory(p, lastTab(p.getUUID()), LAST_PAGE.getOrDefault(p.getUUID(), 0));
    }

    // ── layout ────────────────────────────────────────────────────────────────

    /** Menu slot index of list row k: rows 0-4 in the chest, 5-7 in the (hidden) inventory. */
    static int rowSlot(int k) { return k < 5 ? 9 + k * 9 : 54 + (k - 5) * 9; }

    /** Which list row a menu slot belongs to (any cell of the row), or -1. */
    static int rowOf(int slot) {
        if (slot >= 9 && slot < 54) return (slot - 9) / 9;
        if (slot >= 54 && slot < 81) return 5 + (slot - 54) / 9;
        return -1;
    }

    private static void put(SimpleContainer top, SimpleContainer lower, int slot, ItemStack s) {
        if (slot < 54) top.setItem(slot, s); else lower.setItem(slot - 54, s);
    }

    static Component paint(ServerPlayer p, Category cat, int pg, int pages, List<Entry> shown, Entry sel, int bal) {
        var M = TitleCanvas.M;
        TitleCanvas c = new TitleCanvas();
        // art: the two canvas tiles, the active tab, the selected row
        c.art(M.canvasLeft, TitleCanvas.TILE_L, M.wdAdvance.get(String.valueOf(TitleCanvas.TILE_L)));
        // place the right tile at its true position: an advance includes the client's +1 spacing
        int half = M.wdAdvance.get(String.valueOf(TitleCanvas.TILE_L)) - 1;
        c.art(M.canvasLeft + half, TitleCanvas.TILE_R, M.wdAdvance.get(String.valueOf(TitleCanvas.TILE_R)));
        for (int k = 0; k < TABS.length; k++) {
            if (TABS[k] == cat) c.art(7 + k * 18, TitleCanvas.TAB, M.wdAdvance.get(String.valueOf(TitleCanvas.TAB)));
        }
        for (int k = 0; k < shown.size(); k++) {
            if (sel != null && shown.get(k).id().equals(sel.id())) {
                c.art(7, (char) (TitleCanvas.ROW0 + k), M.wdAdvance.get("row"));
            }
        }

        int L0 = M.left[0] + 7, L1 = M.left[1] - 7, R0 = M.right[0] + 7, R1 = M.right[1] - 7;
        // headers
        c.text(L0, M.headerY, Component.literal(TitleCanvas.BEAN + " Wardrobe"), GOLD, false);
        c.text(8, M.headerY, Component.literal(tabName(cat)), GOLD, false);
        if (pages > 1) c.textRight(168, M.headerY, Component.literal((pg + 1) + "/" + pages), DIM, false);
        c.text(R0, M.headerY, Component.literal(TitleCanvas.BEAN + " " + (bal < 0 ? "-" : String.format("%,d", bal)) + " Beans"), GOLD, false);

        // list rows
        String plainName = p.getGameProfile().getName();
        for (int k = 0; k < shown.size(); k++) {
            Entry e = shown.get(k);
            int y = M.rows[k];
            Component status = status(e, bal);
            int statusW = TitleCanvas.width(status, GREEN, false);
            int nameMax = 167 - 28 - statusW - 4;
            Component name = rowName(e, plainName, nameMax);
            c.text(28, y, name, WHITE, false);
            c.textRight(167, y, status, GREEN, false);
            String sub = e.cat() == Category.COLOURS || e.cat() == Category.GRADIENTS ? e.label()
                : e.blurb() == null ? "" : e.blurb();
            c.text(28, y + 9, TitleCanvas.fit(sub, 139), GREY);
        }
        if (shown.isEmpty()) {
            c.text(28, M.rows[0] + 4, cat == Category.WARDROBE ? "Nothing to wear yet." : "Nothing here yet.", GREY);
        }
        c.textCentre(88, M.hotbarY + 4, Component.literal("Page " + (pg + 1) + " / " + pages), DIM, false);

        // left: the "You" panel: your skin as a doll wearing the selected effect, your name in the selected style
        var selection = StoreState.selectionFor(p.getUUID());
        int cx = (M.left[0] + M.left[1]) / 2;
        String effect = sel != null && (sel.cat() == Category.TRAILS || sel.cat() == Category.CONTRAILS) ? sel.id() : selection.trail();
        boolean ship = effect != null && effect.startsWith(com.coffeesaerosmp.auth.store.Entitlements.CONTRAIL_PREFIX);
        if (ship) {
            c.textCentre(cx, 24, Component.literal("Your airship"), GOLD, false);
        } else {
            String style = sel != null && (sel.cat() == Category.COLOURS || sel.cat() == Category.GRADIENTS) ? sel.id() : selection.nameStyle();
            boolean bold = selection.bold() || sel != null && sel.id().equals(com.coffeesaerosmp.auth.store.Entitlements.BOLD_ID);
            Component plate = NameRender.preview(plainName, style, bold);
            if (TitleCanvas.width(plate, WHITE, true) <= L1 - L0) c.textCentre(cx, 22, plate, WHITE, true);
            else c.textCentre(cx, 26, plate, WHITE, false);
            SkinDoll.Doll doll = SkinDoll.get(p, () -> redrawIfOpen(p));
            if (doll == null) doll = SkinDoll.fallback();
            int x0 = cx - doll.width() * M.pixel / 2;
            for (int r = 0; r < 32; r++) c.pixels(x0, M.dollTop + r * M.pixel, doll.px()[r]);
        }
        var scene = effect == null ? null : M.scenes.get(effect);
        if (scene != null) c.scene(cx - scene.width() / 2, scene);
        Rank rank = StoreState.get(p.getUUID()).effectiveRank();
        c.text(L0, 186, TitleCanvas.fit("Trail: " + wearing(selection.trail()), L1 - L0), GREY);
        c.text(L0, 196, TitleCanvas.fit("Contrail: " + wearing(selection.contrail()), L1 - L0), GREY);
        c.text(L0, 206, TitleCanvas.fit("Name: " + (selection.nameStyle() == null ? "plain" : ShopGui.label(selection.nameStyle())), L1 - L0), GREY);
        c.text(L0, 216, TitleCanvas.fit("Rank: " + (rank == Rank.NONE ? "none" : rank.displayName()), L1 - L0), GREY);

        // right: details of the selected thing
        if (sel != null) {
            Component big = rowName(sel, plainName, Integer.MAX_VALUE);
            if (TitleCanvas.width(big, WHITE, true) <= R1 - R0) c.text(R0, 24, big, WHITE, true);
            else c.text(R0, 26, Component.literal(TitleCanvas.fit(big.getString(), R1 - R0)).withStyle(big.getStyle()), WHITE, false);
            c.text(R0, 47, TitleCanvas.fit(categoryLine(sel), R1 - R0), DIM);
            int y = 58;
            String about = sel.blurb() != null ? sel.blurb()
                : sel.cat() == Category.COLOURS || sel.cat() == Category.GRADIENTS
                    ? "Your name in this style in chat, on the tab list and above your head."
                    : null;
            if (about != null) {
                for (String line : TitleCanvas.wrap(about, R1 - R0, 6)) { c.text(R0, y, line, GREY); y += 10; }
            }
            y = Math.max(y + 6, 150);
            c.text(R0, y, status(sel, bal), GREEN, false);
            y += 11;
            if (!sel.available() && sel.price().isPresent() && bal >= 0) {
                int price = sel.price().getAsInt();
                c.text(R0, y, bal >= price ? "You'd have " + (bal - price) + " left" : "You need " + (price - bal) + " more",
                    bal >= price ? GREY : RED);
            }
        }
        boolean particle = sel != null && (sel.cat() == Category.TRAILS || sel.cat() == Category.CONTRAILS);
        String right = sel == null ? "" : sel.equipped() ? "take off" : sel.available() ? "wear" : sel.price().isPresent() ? "buy" : "-";
        legend(c, R0, R1, 186, "Left-click", "preview");
        legend(c, R0, R1, 196, "Right-click", right);
        if (particle) legend(c, R0, R1, 206, "Shift-click", "try live");
        c.text(R0, 218, TitleCanvas.fit("Preview: left panel", R1 - R0), DIM);
        return c.build();
    }

    /** "Key action", the action cut to end before {@code maxX} (the panel's inner edge). */
    private static void legend(TitleCanvas c, int x, int maxX, int y, String key, String action) {
        int end = c.text(x, y, key, YELLOW);
        c.text(end + 4, y, TitleCanvas.fit(action, maxX - end - 4), GREY);
    }

    // ── pieces ────────────────────────────────────────────────────────────────

    /** A row's title: colours and gradients show the player's own name in that style, else the label. */
    private static Component rowName(Entry e, String plainName, int max) {
        if (e.cat() == Category.COLOURS || e.cat() == Category.GRADIENTS) return NameRender.preview(plainName, e.id(), false);
        if (e.id().equals(com.coffeesaerosmp.auth.store.Entitlements.BOLD_ID)) {
            return Component.literal(plainName).withStyle(s -> s.withBold(true).withColor(WHITE));
        }
        return Component.literal(max == Integer.MAX_VALUE ? TitleCanvas.sanitize(e.label()) : TitleCanvas.fit(e.label(), max));
    }

    /** The short status at the right of a row. */
    static Component status(Entry e, int bal) {
        if (e.equipped()) return Component.literal(TitleCanvas.STAR + " Wearing").withStyle(s -> s.withColor(GOLD));
        if (e.owned()) return Component.literal("Yours " + TitleCanvas.CHECK).withStyle(s -> s.withColor(GREEN));
        if (e.available()) return Component.literal("Rank " + TitleCanvas.CHECK).withStyle(s -> s.withColor(GREEN));
        if (e.price().isPresent()) {
            int price = e.price().getAsInt();
            int colour = bal >= 0 && bal < price ? RED : GOLD;
            return Component.literal(TitleCanvas.BEAN + " " + price).withStyle(s -> s.withColor(colour));
        }
        if (e.rankNeeded() != null) {
            return Component.literal(TitleCanvas.LOCK + " " + e.rankNeeded().displayName()).withStyle(s -> s.withColor(PURPLE));
        }
        return Component.literal("Event").withStyle(s -> s.withColor(PURPLE));
    }

    private static String categoryLine(Entry e) {
        return switch (e.cat()) {
            case TRAILS -> "Trail";
            case CONTRAILS -> "Airship contrail";
            case WINGS -> "Wings (AeroCore)";
            case AURAS -> "Aura (AeroCore)";
            case COLOURS -> e.label();
            case GRADIENTS -> e.label();
            default -> "Extra";
        };
    }

    private static String wearing(String id) {
        if (id == null) return "none";
        return CosmeticCatalog.get(id).map(CosmeticCatalog.Cosmetic::label).orElse(id);
    }

    static String tabName(Category c) {
        return switch (c) {
            case TRAILS -> "Trails";
            case CONTRAILS -> "Contrails";
            case COLOURS -> "Name colours";
            case GRADIENTS -> "Gradients";
            case EXTRAS -> "Extras";
            case WARDROBE -> "Owned";
            case WINGS -> "Wings";
            case AURAS -> "Auras";
        };
    }

    private static ItemStack tabIcon(Category c, boolean active) {
        Item item = switch (c) {
            case TRAILS -> Items.FEATHER;
            case CONTRAILS -> Items.PHANTOM_MEMBRANE;
            case COLOURS -> Items.ORANGE_DYE;
            case GRADIENTS -> Items.PRISMARINE_CRYSTALS;
            case EXTRAS -> Items.NAME_TAG;
            case WARDROBE -> Items.ARMOR_STAND;
            case WINGS -> Items.ELYTRA;
            case AURAS -> Items.GOLD_INGOT;
        };
        ItemStack s = named(item, tabName(c), active ? ChatFormatting.GOLD : ChatFormatting.WHITE, null);
        if (active) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    private static ItemStack named(Item item, String name, ChatFormatting colour, String lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withItalic(false).withColor(colour)));
        if (lore != null) s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal(lore).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GRAY)))));
        s.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return s;
    }
}
