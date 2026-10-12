package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.commands.CosmeticCommands;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.cosmetics.TryOn;
import com.coffeesaerosmp.auth.store.BeanLedger;
import com.coffeesaerosmp.auth.store.BeanPrices;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.Entitlements;
import com.coffeesaerosmp.auth.store.MojangIds;
import com.coffeesaerosmp.auth.store.NameRender;
import com.coffeesaerosmp.auth.store.Palette;
import com.coffeesaerosmp.auth.store.PremiumGate;
import com.coffeesaerosmp.auth.store.Rank;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.store.gui.ButtonMenu.Click;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Coffee Bean shop as chest windows (vanilla client, every pack version). Screens: main, category, ranks,
 * wardrobe, confirm. Premium accounts only ({@link PremiumGate}).
 *
 * <p>Clicks, everywhere: on something you can wear, <b>left = wear / take off</b>, <b>right = try it on</b>; on
 * something you don't have, <b>left = try it on</b>, <b>right = buy</b> (a confirm screen, then it's equipped).
 * Name colours and gradients show YOUR name in that style as the item's title, so the menu itself is the preview.
 * Trails and contrails preview with {@link TryOn} (30 s, packets to you alone).
 *
 * <p>Wearing goes through the existing {@code /cosmetics} commands, so every permission, preview and moderation
 * rule lives in one place. Database work is on {@link AsyncIo}; windows open back on the server thread.
 */
public final class ShopGui {

    private ShopGui() {}

    public enum Category { TRAILS, CONTRAILS, COLOURS, GRADIENTS, EXTRAS, WARDROBE }

    /** One thing in the shop, and where it stands for this player. */
    record Entry(String id, String label, String blurb, Category cat, OptionalInt price, Rank rankNeeded,
                 boolean owned, boolean available, boolean equipped) {}

    private static final Map<UUID, Integer> BALANCE = new ConcurrentHashMap<>();
    private static final int[] CONTENT = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                                          28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    // ── opening ───────────────────────────────────────────────────────────────

    /** /shop: the AeroCore Wardrobe screen when the client has it, else the chest windows. */
    public static void openMain(ServerPlayer p) {
        if (!PremiumGate.check(p)) return;
        if (WardrobeSync.supported(p)) { WardrobeSync.open(p); return; }
        withBalance(p, bal -> showMain(p, bal));
    }

    /** /shop classic: always the chest windows. */
    public static void openClassic(ServerPlayer p) {
        if (!PremiumGate.check(p)) return;
        withBalance(p, bal -> showMain(p, bal));
    }

    /** /shop tryon <id> (the Wardrobe's "Try in world" button). */
    public static void tryOnById(ServerPlayer p, String id) {
        if (!PremiumGate.check(p)) return;
        if (CosmeticCatalog.get(id).isEmpty()) return;
        tryOn(p, new Entry(id, label(id), null, categoryOf(id), BeanPrices.price(id), rankNeeded(id), false, false, false));
    }

    /** /shop purchase <id> (the Wardrobe's Buy, after its own confirm dialog): buy, then wear it. */
    public static void purchaseById(ServerPlayer p, String id) {
        if (!PremiumGate.check(p)) return;
        if (BeanPrices.price(id).isEmpty()) {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cThat isn't for sale."));
            return;
        }
        buy(p, new Entry(id, label(id), null, categoryOf(id), BeanPrices.price(id), rankNeeded(id), false, false, false), null, 0);
    }

    public static void openCategory(ServerPlayer p, Category cat, int page) {
        if (!PremiumGate.check(p)) return;
        withBalance(p, bal -> showCategory(p, cat, page, bal));
    }

    /** Reads the balance off-thread, then runs {@code then} on the server thread. -1 = not linked / DB down. */
    private static void withBalance(ServerPlayer p, java.util.function.IntConsumer then) {
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            int bal = mojang == null ? -1 : BeanLedger.balance(mojang);
            if (bal >= 0) BALANCE.put(local, bal);
            var server = p.getServer();
            if (server != null) server.execute(() -> { if (!p.hasDisconnected()) then.accept(bal); });
        });
    }

    private static void open(ServerPlayer p, int rows, String title, SimpleContainer box, ButtonMenu.Handler h) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ButtonMenu(id, inv, rows, box, h),
            com.coffeesaerosmp.auth.cosmetics.RpModels.windowTitle(title, rows)));
        com.coffeesaerosmp.auth.cosmetics.FittingRoom.ensure(p);
    }

    // ── main ──────────────────────────────────────────────────────────────────

    private static void showMain(ServerPlayer p, int bal) {
        SimpleContainer box = filled(54);
        var snap = StoreState.get(p.getUUID());
        Rank rank = snap.effectiveRank();
        box.setItem(4, icon(Items.COCOA_BEANS, title("☕ Coffee's Aero Shop", ChatFormatting.GOLD), List.of(
            line("Everything here is cosmetic.", ChatFormatting.GRAY),
            line("No gameplay advantages, ever.", ChatFormatting.GRAY)), true));
        box.setItem(13, head(p, rank, bal));
        box.setItem(20, icon(Items.FEATHER, title("Trails", ChatFormatting.AQUA), List.of(line("Particles at your feet while you walk", ChatFormatting.GRAY)), false));
        box.setItem(21, icon(Items.PHANTOM_MEMBRANE, title("Airship contrails", ChatFormatting.AQUA), List.of(line("A wake behind the ship you're on", ChatFormatting.GRAY)), false));
        box.setItem(22, icon(Items.ORANGE_DYE, title("Name colours", ChatFormatting.AQUA), List.of(line("See your own name in each colour", ChatFormatting.GRAY)), false));
        box.setItem(23, icon(Items.PRISMARINE_CRYSTALS, title("Gradient names", ChatFormatting.AQUA), List.of(line("Two colours blended across your name", ChatFormatting.GRAY)), false));
        box.setItem(24, icon(Items.NAME_TAG, title("Bold & messages", ChatFormatting.AQUA), List.of(line("Bold name, custom join and leave lines", ChatFormatting.GRAY)), false));
        box.setItem(30, icon(Items.NETHER_STAR, title("Ranks", ChatFormatting.LIGHT_PURPLE), List.of(
            line("Deckhand, Navigator, Captain, Admiral", ChatFormatting.GRAY),
            line("Your rank: " + (rank == Rank.NONE ? "none" : rank.displayName()), ChatFormatting.WHITE)), false));
        box.setItem(32, icon(Items.ARMOR_STAND, title("Wardrobe", ChatFormatting.GREEN), List.of(line("Everything you can wear right now", ChatFormatting.GRAY)), false));
        box.setItem(39, beansIcon(bal));
        box.setItem(41, icon(Items.EMERALD, title("Get Coffee Beans", ChatFormatting.GREEN), List.of(
            line("Opens the store page in your browser.", ChatFormatting.GRAY)), false));
        box.setItem(49, icon(Items.BARRIER, title("Close", ChatFormatting.RED), List.of(), false));

        open(p, 6, "Coffee's Aero Shop", box, (slot, click) -> {
            switch (slot) {
                case 20 -> openCategory(p, Category.TRAILS, 0);
                case 21 -> openCategory(p, Category.CONTRAILS, 0);
                case 22 -> openCategory(p, Category.COLOURS, 0);
                case 23 -> openCategory(p, Category.GRADIENTS, 0);
                case 24 -> openCategory(p, Category.EXTRAS, 0);
                case 30 -> showRanks(p);
                case 32 -> openCategory(p, Category.WARDROBE, 0);
                case 41 -> { p.closeContainer(); storeLink(p); }
                case 49 -> p.closeContainer();
                default -> { }
            }
        });
    }

    // ── category ──────────────────────────────────────────────────────────────

    private static void showCategory(ServerPlayer p, Category cat, int page, int bal) {
        List<Entry> all = entries(p, cat);
        int pages = Math.max(1, (all.size() + CONTENT.length - 1) / CONTENT.length);
        int pg = Math.max(0, Math.min(page, pages - 1));
        SimpleContainer box = filled(54);
        List<Entry> shown = all.subList(pg * CONTENT.length, Math.min(all.size(), (pg + 1) * CONTENT.length));
        String plainName = p.getGameProfile().getName();
        for (int i = 0; i < shown.size(); i++) box.setItem(CONTENT[i], entryIcon(shown.get(i), plainName, bal));
        box.setItem(45, icon(Items.ARROW, title("Back", ChatFormatting.WHITE), List.of(), false));
        if (pg > 0) box.setItem(48, icon(Items.PAPER, title("Previous page", ChatFormatting.WHITE), List.of(), false));
        box.setItem(49, beansIcon(bal));
        if (pg < pages - 1) box.setItem(50, icon(Items.PAPER, title("Next page", ChatFormatting.WHITE), List.of(), false));
        box.setItem(53, icon(Items.BARRIER, title("Close", ChatFormatting.RED), List.of(), false));
        if (shown.isEmpty()) box.setItem(22, icon(Items.GRAY_DYE, title(cat == Category.WARDROBE
            ? "Nothing to wear yet" : "Nothing here yet", ChatFormatting.GRAY), List.of(), false));

        String title = "Shop · " + switch (cat) {
            case TRAILS -> "Trails"; case CONTRAILS -> "Contrails"; case COLOURS -> "Name colours";
            case GRADIENTS -> "Gradients"; case EXTRAS -> "Bold & messages"; case WARDROBE -> "Wardrobe";
        } + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : "");
        open(p, 6, title, box, (slot, click) -> {
            if (slot == 45) { openClassic(p); return; }      // stay in the chest shop (openMain prefers the Wardrobe)
            if (slot == 48 && pg > 0) { openCategory(p, cat, pg - 1); return; }
            if (slot == 50 && pg < pages - 1) { openCategory(p, cat, pg + 1); return; }
            if (slot == 53) { p.closeContainer(); return; }
            for (int i = 0; i < shown.size(); i++) {
                if (CONTENT[i] == slot) { act(p, shown.get(i), click, cat, pg, bal); return; }
            }
        });
    }

    /** What a click on an entry does. */
    /**
     * Left = preview on your mannequin beside the window (the window stays open). Right = wear it / take it off if
     * you have it, otherwise buy. Shift = try it on in the world for 30 s (closes the window).
     */
    private static void act(ServerPlayer p, Entry e, Click click, Category back, int page, int bal) {
        boolean particle = e.cat() == Category.TRAILS || e.cat() == Category.CONTRAILS
            || e.id().startsWith(Entitlements.TRAIL_PREFIX) || e.id().startsWith(Entitlements.CONTRAIL_PREFIX);
        if (click == Click.LEFT) {
            if (particle) com.coffeesaerosmp.auth.cosmetics.FittingRoom.preview(p, e.id());
            else if (e.id().equals(Entitlements.BOLD_ID)) com.coffeesaerosmp.auth.cosmetics.FittingRoom.previewName(p, null, true);
            else if (e.cat() == Category.COLOURS || e.cat() == Category.GRADIENTS)
                com.coffeesaerosmp.auth.cosmetics.FittingRoom.previewName(p, e.id(), false);
            else previewName(p, e);
            return;
        }
        if (click == Click.SHIFT) {
            if (particle) tryOn(p, e); else previewName(p, e);
            return;
        }
        if (e.available()) {
            wear(p, e);
            openCategory(p, back, page);
            return;
        }
        if (e.price().isPresent()) showConfirm(p, e, bal, back, page);
        else if (e.rankNeeded() != null) {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7" + e.label() + " comes with the "
                + e.rankNeeded().badge() + e.rankNeeded().displayName() + "§7 rank."));
        } else {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7" + e.label() + " isn't for sale. It comes from events."));
        }
    }

    private static void tryOn(ServerPlayer p, Entry e) {
        p.closeContainer();
        TryOn.start(p, e.id());
        boolean contrail = e.id().startsWith(Entitlements.CONTRAIL_PREFIX);
        p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Trying on §f" + e.label() + "§7 for " + TryOn.SECONDS
            + " seconds. " + (contrail ? "Fly your ship to see it behind you, or look around: the demo stream follows your view."
                : "Walk around to see it.") + " §8Only you can see it."));
    }

    private static void previewName(ServerPlayer p, Entry e) {
        String name = p.getGameProfile().getName();
        boolean bold = e.id().equals(Entitlements.BOLD_ID);
        Component styled = bold
            ? NameRender.preview(name, StoreState.selectionFor(p.getUUID()).nameStyle(), true)
            : NameRender.preview(name, e.id(), StoreState.selectionFor(p.getUUID()).bold());
        p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Preview: ").append(styled)
            .append(Component.literal("§7: this is how your name looks in chat and the tab list.")));
    }

    private static void wear(ServerPlayer p, Entry e) {
        String cmd;
        String id = e.id();
        if (id.startsWith(Entitlements.TRAIL_PREFIX)) cmd = "cosmetics trail " + (e.equipped() ? "off" : id.substring(6));
        else if (id.startsWith(Entitlements.CONTRAIL_PREFIX)) cmd = "cosmetics contrail " + (e.equipped() ? "off" : id.substring(9));
        else if (id.startsWith(Entitlements.COLOUR_PREFIX)) cmd = e.equipped() ? "cosmetics clear" : "cosmetics colour " + id.substring(7);
        else if (id.startsWith(Entitlements.GRADIENT_PREFIX)) cmd = e.equipped() ? "cosmetics clear" : "cosmetics gradient " + id.substring(9);
        else if (id.equals(Entitlements.BOLD_ID)) cmd = "cosmetics bold " + !e.equipped();
        else if (id.equals(Entitlements.JOIN_MESSAGE_ID)) {
            p.closeContainer();
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Write your lines, staff approve them before they show: ")
                .append(Component.literal("§f[join message]").setStyle(Style.EMPTY.withClickEvent(
                    new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cosmetics joinmsg "))))
                .append(Component.literal(" "))
                .append(Component.literal("§f[leave message]").setStyle(Style.EMPTY.withClickEvent(
                    new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cosmetics leavemsg ")))));
            return;
        } else return;
        var server = p.getServer();
        if (server != null) server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd);
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    private static void showConfirm(ServerPlayer p, Entry e, int bal, Category back, int page) {
        int price = e.price().getAsInt();
        boolean enough = bal >= price;
        SimpleContainer box = filled(27);
        box.setItem(13, entryIcon(e, p.getGameProfile().getName(), bal));
        box.setItem(11, enough
            ? icon(Items.LIME_CONCRETE, title("Buy for " + price + " Beans", ChatFormatting.GREEN), List.of(
                line("You'll have " + (bal - price) + " Beans left.", ChatFormatting.GRAY),
                line("Yours to keep. It's put on straight away.", ChatFormatting.GRAY)), false)
            : icon(Items.GRAY_CONCRETE, title("Not enough Beans", ChatFormatting.GRAY), List.of(
                line("It costs " + price + ", you have " + Math.max(0, bal) + ".", ChatFormatting.GRAY),
                line("Click to get more.", ChatFormatting.GREEN)), false));
        box.setItem(15, icon(Items.RED_CONCRETE, title("Cancel", ChatFormatting.RED), List.of(), false));
        open(p, 3, "Buy " + e.label() + "?", box, (slot, click) -> {
            if (slot == 15) { openCategory(p, back, page); return; }
            if (slot != 11) return;
            if (!enough) { p.closeContainer(); storeLink(p); return; }
            p.closeContainer();
            buy(p, e, back, page);
        });
    }

    private static void buy(ServerPlayer p, Entry e, Category back, int page) {
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            BeanLedger.Outcome o = mojang == null ? null : BeanLedger.buy(mojang, e.id());
            var server = p.getServer();
            if (server == null) return;
            if (o != null && o.result() == BeanLedger.Result.APPLIED) {
                // Make sure the new ownership is in the snapshot before equipping it.
                StoreState.loadAsync(local, () -> server.execute(() -> {
                    if (p.hasDisconnected()) return;
                    BALANCE.put(local, o.balance());
                    p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§aBought §f" + e.label() + "§a! §7You have §f"
                        + o.balance() + "§7 Beans left."));
                    wear(p, new Entry(e.id(), e.label(), e.blurb(), e.cat(), e.price(), e.rankNeeded(), true, true, false));
                    if (back != null) openCategory(p, back, page);
                }));
                return;
            }
            String msg = o == null ? "§cYour premium account isn't linked yet. Rejoin through the server address and try again."
                : switch (o.result()) {
                    case ALREADY_OWNED -> "§eYou already own that. Nothing was spent.";
                    case INSUFFICIENT -> "§cNot enough Beans: it costs " + o.price() + ", you have " + o.balance() + ".";
                    case NOT_FOR_SALE -> "§cThat isn't for sale.";
                    default -> "§cThe purchase didn't go through and nothing was spent. Try again in a moment.";
                };
            server.execute(() -> p.sendSystemMessage(Component.literal(TextUtil.PREFIX + msg)));
        });
    }

    // ── ranks ─────────────────────────────────────────────────────────────────

    private static void showRanks(ServerPlayer p) {
        Rank mine = StoreState.get(p.getUUID()).effectiveRank();
        SimpleContainer box = filled(27);
        int[] slots = {10, 12, 14, 16};
        Item[] icons = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.NETHERITE_INGOT};
        Rank[] ranks = {Rank.DECKHAND, Rank.NAVIGATOR, Rank.CAPTAIN, Rank.ADMIRAL};
        for (int i = 0; i < 4; i++) {
            Rank r = ranks[i];
            List<Component> lore = new ArrayList<>();
            for (String perk : perks(r)) lore.add(line("• " + perk, ChatFormatting.GRAY));
            lore.add(Component.empty());
            lore.add(line(mine == r ? "★ Your rank" : "Click: open the store page", mine == r ? ChatFormatting.GOLD : ChatFormatting.GREEN));
            box.setItem(slots[i], icon(icons[i], Component.literal(r.badge().replace("§", "§") + r.displayName())
                .withStyle(s -> s.withItalic(false)), lore, mine == r));
        }
        box.setItem(22, icon(Items.ARROW, title("Back", ChatFormatting.WHITE), List.of(), false));
        open(p, 3, "Shop · Ranks", box, (slot, click) -> {
            if (slot == 22) { openClassic(p); return; }
            for (int s : slots) if (s == slot) { p.closeContainer(); storeLink(p); return; }
        });
    }

    /** What each tier adds, in words. Cumulative: each includes everything below it. */
    static List<String> perks(Rank r) {
        List<String> out = new ArrayList<>();
        switch (r) {
            case DECKHAND -> { out.add("Rank badge ⚓"); out.add("8 name colours"); out.add("Cloud Walker trail"); }
            case NAVIGATOR -> { out.add("Everything in Deckhand"); out.add("Every name colour, bold"); out.add("All rank trails"); }
            case CAPTAIN -> { out.add("Everything in Navigator"); out.add("Two-colour gradient names"); out.add("Join and leave messages"); out.add("Airship contrails"); }
            case ADMIRAL -> { out.add("Everything in Captain"); out.add("Three-colour gradients"); out.add("Custom chat title"); out.add("Aurora Ribbon contrail (Admiral only)"); }
            default -> { }
        }
        return out;
    }

    // ── model ─────────────────────────────────────────────────────────────────

    /** Everything in a category, with this player's status. Wardrobe = everything they can wear now. */
    static List<Entry> entries(ServerPlayer p, Category cat) {
        var snap = StoreState.get(p.getUUID());
        Rank rank = snap.effectiveRank();
        Set<String> owned = snap.owned();
        boolean preview = CosmeticCommands.preview(p);
        var sel = StoreState.selectionFor(p.getUUID());
        List<String> ids = new ArrayList<>();
        switch (cat) {
            case TRAILS -> CosmeticCatalog.all(CosmeticCatalog.Type.TRAIL).forEach(c -> ids.add(c.id()));
            case CONTRAILS -> CosmeticCatalog.all(CosmeticCatalog.Type.CONTRAIL).forEach(c -> ids.add(c.id()));
            case COLOURS -> Palette.allColours().forEach(c -> { if (!Palette.isStaffReserved(c)) ids.add(Entitlements.COLOUR_PREFIX + c); });
            case GRADIENTS -> Palette.allGradients().forEach(g -> ids.add(Entitlements.GRADIENT_PREFIX + g));
            case EXTRAS -> { ids.add(Entitlements.BOLD_ID); ids.add(Entitlements.JOIN_MESSAGE_ID); }
            case WARDROBE -> {
                for (Category c : new Category[]{Category.TRAILS, Category.CONTRAILS, Category.COLOURS, Category.GRADIENTS, Category.EXTRAS}) {
                    for (Entry e : entries(p, c)) if (e.available()) ids.add(e.id());
                }
            }
        }
        List<Entry> out = new ArrayList<>();
        for (String id : ids) {
            boolean own = owned.contains(id);
            boolean avail = Entitlements.mayUse(id, rank, owned, preview);
            boolean eq = id.equals(sel.trail()) || id.equals(sel.contrail()) || id.equals(sel.nameStyle())
                || (id.equals(Entitlements.BOLD_ID) && sel.bold())
                || (id.equals(Entitlements.JOIN_MESSAGE_ID) && (sel.joinMessage() != null || sel.leaveMessage() != null));
            out.add(new Entry(id, label(id), blurb(id), categoryOf(id), BeanPrices.price(id), rankNeeded(id), own, avail, eq));
        }
        return out;
    }

    static Category categoryOf(String id) {
        if (id.startsWith(Entitlements.TRAIL_PREFIX)) return Category.TRAILS;
        if (id.startsWith(Entitlements.CONTRAIL_PREFIX)) return Category.CONTRAILS;
        if (id.startsWith(Entitlements.COLOUR_PREFIX)) return Category.COLOURS;
        if (id.startsWith(Entitlements.GRADIENT_PREFIX)) return Category.GRADIENTS;
        return Category.EXTRAS;
    }

    /** The lowest rank that unlocks this, or null if no rank does (Beans or event only). */
    static Rank rankNeeded(String id) {
        for (Rank r : new Rank[]{Rank.DECKHAND, Rank.NAVIGATOR, Rank.CAPTAIN, Rank.ADMIRAL}) {
            if (Entitlements.mayUse(id, r, Set.of(), false)) return r;
        }
        return null;
    }

    static String label(String id) {
        var c = CosmeticCatalog.get(id);
        if (c.isPresent()) return c.get().label();
        if (id.equals(Entitlements.BOLD_ID)) return "Bold name";
        if (id.equals(Entitlements.JOIN_MESSAGE_ID)) return "Join & leave messages";
        if (id.startsWith(Entitlements.COLOUR_PREFIX)) return pretty(id.substring(7)) + " name";
        if (id.startsWith(Entitlements.GRADIENT_PREFIX)) return pretty(id.substring(9)) + " gradient";
        return id;
    }

    private static String blurb(String id) {
        var c = CosmeticCatalog.get(id);
        if (c.isPresent()) return c.get().blurb();
        if (id.equals(Entitlements.BOLD_ID)) return "Your name in bold, with any colour.";
        if (id.equals(Entitlements.JOIN_MESSAGE_ID)) return "Your own line when you join and leave. Staff approve it first.";
        return null;
    }

    private static String pretty(String raw) {
        String s = raw.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── icons ─────────────────────────────────────────────────────────────────

    private static ItemStack entryIcon(Entry e, String playerName, int bal) {
        Component name;
        if (e.cat() == Category.COLOURS || e.cat() == Category.GRADIENTS) {
            // The title IS the preview: your own name in this style.
            name = NameRender.preview(playerName, e.id(), false).copy().withStyle(s -> s.withItalic(false));
        } else if (e.id().equals(Entitlements.BOLD_ID)) {
            name = Component.literal(playerName).withStyle(s -> s.withBold(true).withItalic(false).withColor(ChatFormatting.WHITE));
        } else {
            name = title(e.label(), ChatFormatting.WHITE);
        }
        List<Component> lore = new ArrayList<>();
        if (e.cat() == Category.COLOURS || e.cat() == Category.GRADIENTS) lore.add(line(e.label(), ChatFormatting.DARK_GRAY));
        if (e.blurb() != null) lore.add(line(e.blurb(), ChatFormatting.GRAY));
        lore.add(Component.empty());
        boolean particle = e.cat() == Category.TRAILS || e.cat() == Category.CONTRAILS;
        if (e.equipped()) lore.add(line("★ Wearing it", ChatFormatting.GOLD));
        else if (e.owned()) lore.add(line("✔ Yours", ChatFormatting.GREEN));
        else if (e.available()) lore.add(line("✔ Included with your rank", ChatFormatting.GREEN));
        else if (e.price().isPresent()) lore.add(line(e.price().getAsInt() + " Coffee Beans" + (bal >= 0 && bal < e.price().getAsInt() ? "  (you have " + bal + ")" : ""), ChatFormatting.GOLD));
        else if (e.rankNeeded() != null) lore.add(line("Comes with " + e.rankNeeded().displayName(), ChatFormatting.LIGHT_PURPLE));
        else lore.add(line("Event only", ChatFormatting.LIGHT_PURPLE));
        lore.add(line("Left-click: preview it on your mannequin", ChatFormatting.YELLOW));
        if (e.available()) lore.add(line(e.equipped() ? "Right-click: take it off" : "Right-click: wear it", ChatFormatting.YELLOW));
        else if (e.price().isPresent()) lore.add(line("Right-click: buy", ChatFormatting.YELLOW));
        if (particle) lore.add(line("Shift-click: try it in the world (30 s)", ChatFormatting.YELLOW));
        return icon(iconFor(e.id()), name, lore, e.equipped());
    }

    static Item iconFor(String id) {
        String path = switch (id) {
            case "trail:cloudwalk" -> "feather";
            case "trail:brass" -> "gold_nugget";
            case "trail:clockwork" -> "clock";
            case "trail:espresso" -> "cocoa_beans";
            case "trail:kettle" -> "cauldron";
            case "trail:petal" -> "pink_petals";
            case "trail:ember" -> "blaze_powder";
            case "trail:snow" -> "snowball";
            case "contrail:vapour" -> "phantom_membrane";
            case "contrail:steam" -> "campfire";
            case "contrail:espresso" -> "brown_dye";
            case "contrail:sakura" -> "cherry_sapling";
            case "contrail:aurora" -> "amethyst_shard";
            case "bold" -> "book";
            case "join_message" -> "oak_sign";
            default -> null;
        };
        if (path == null && id.startsWith(Entitlements.COLOUR_PREFIX)) path = dyeFor(id.substring(7));
        if (path == null && id.startsWith(Entitlements.GRADIENT_PREFIX)) path = "prismarine_crystals";
        if (path == null) path = "paper";
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(path));
        return item == Items.AIR ? Items.PAPER : item;
    }

    private static String dyeFor(String colour) {
        return switch (colour) {
            case "white" -> "white_dye"; case "gray" -> "gray_dye"; case "gold" -> "orange_dye";
            case "yellow" -> "yellow_dye"; case "green" -> "lime_dye"; case "dark_green" -> "green_dye";
            case "aqua" -> "light_blue_dye"; case "dark_aqua" -> "cyan_dye"; case "blue" -> "blue_dye";
            case "dark_blue" -> "lapis_lazuli"; case "light_purple" -> "magenta_dye"; case "dark_purple" -> "purple_dye";
            default -> "white_dye";
        };
    }

    private static ItemStack head(ServerPlayer p, Rank rank, int bal) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, new ResolvableProfile(p.getGameProfile()));
        Component styled = NameRender.styledName(p.getUUID(), p.getGameProfile().getName());
        if (styled == null) styled = Component.literal(p.getGameProfile().getName()).withStyle(ChatFormatting.WHITE);
        s.set(DataComponents.CUSTOM_NAME, styled.copy().withStyle(st -> st.withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(List.of(
            line("This is how your name looks now", ChatFormatting.DARK_GRAY),
            line("Rank: " + (rank == Rank.NONE ? "none" : rank.displayName()), ChatFormatting.GRAY),
            line("Coffee Beans: " + (bal < 0 ? "—" : String.valueOf(bal)), ChatFormatting.GOLD))));
        return s;
    }

    private static ItemStack beansIcon(int bal) {
        return icon(Items.COCOA_BEANS, title(bal < 0 ? "Coffee Beans: —" : "Coffee Beans: " + bal, ChatFormatting.GOLD), List.of(
            line("Spend them on anything in the shop.", ChatFormatting.GRAY),
            line("Never tradable, never turned into spurs.", ChatFormatting.DARK_GRAY)), false);
    }

    private static ItemStack icon(Item item, Component name, List<Component> lore, boolean glint) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) s.set(DataComponents.LORE, new ItemLore(lore));
        s.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    private static SimpleContainer filled(int size) {
        SimpleContainer box = new SimpleContainer(size);
        // With the resource pack the window skin IS the background; grey panes would hide it. Empty slots are
        // safe: ButtonMenu takes every click in the shop's own slots.
        if (com.coffeesaerosmp.auth.cosmetics.RpModels.enabled()) return box;
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        pane.set(DataComponents.HIDE_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        for (int i = 0; i < size; i++) box.setItem(i, pane.copy());
        return box;
    }

    private static MutableComponent title(String s, ChatFormatting colour) {
        return Component.literal(s).withStyle(st -> st.withItalic(false).withColor(colour));
    }

    private static Component line(String s, ChatFormatting colour) {
        return Component.literal(s).withStyle(st -> st.withItalic(false).withColor(colour));
    }

    private static void storeLink(ServerPlayer p) {
        String url;
        try { url = AuthConfig.STORE_URL.get(); } catch (Throwable t) { url = ""; }
        if (url == null || url.isBlank()) {
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7The store page isn't open yet. It'll be announced in Discord."));
            return;
        }
        p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Store: ").append(Component.literal("§b§n" + url)
            .setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))))
            .append(Component.literal(" §8(buy for exactly this account: " + p.getGameProfile().getName() + ")")));
        p.sendSystemMessage(Component.literal(
            "§8NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."));
    }

    /** Cleared on logout. */
    public static void forget(UUID id) {
        BALANCE.remove(id);
    }
}
