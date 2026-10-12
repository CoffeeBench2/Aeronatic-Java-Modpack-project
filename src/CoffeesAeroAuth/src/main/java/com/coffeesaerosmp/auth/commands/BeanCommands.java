package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.store.BeanLedger;
import com.coffeesaerosmp.auth.store.BeanPrices;
import com.coffeesaerosmp.auth.store.MojangIds;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /beans} (balance + history), {@code /shop} (spend Beans on cosmetics) and {@code /aerostore beans}
 * (staff grants). 1.13.31, Phase 2 of the store spec.
 *
 * <p>🔴 Gated exactly like {@code /cosmetics}: players see none of it until {@code storeEnabled}; ops can use it
 * now to test (owner 2026-10-12: "1st ops test the cosmetics, then the shop"). No money is involved yet — Beans
 * only come from {@code /aerostore beans give}. Taking money stays blocked until a premium-only date is set.
 *
 * <p>Buying is two clicks on purpose: {@code /shop buy} only shows the price and a confirm button, and
 * {@code /shop confirm} spends only if that same item was chosen in the last 60 s.
 */
public final class BeanCommands {

    private static final int OP = 2;
    private static final long CONFIRM_WINDOW_MS = 60_000L;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC);

    private record Pending(String id, long atMs) {}

    private BeanCommands() {}

    private static boolean visible(CommandSourceStack src) {
        boolean store;
        try { store = AuthConfig.STORE_ENABLED.get(); } catch (Throwable t) { store = false; }
        return store || src.hasPermission(OP);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("beans")
            .requires(BeanCommands::visible)
            .executes(ctx -> beans(ctx.getSource())));

        dispatcher.register(Commands.literal("shop")
            .requires(BeanCommands::visible)
            .executes(ctx -> shop(ctx.getSource(), null))
            .then(Commands.literal("trails").executes(ctx -> shop(ctx.getSource(), BeanPrices.Group.TRAIL)))
            .then(Commands.literal("contrails").executes(ctx -> shop(ctx.getSource(), BeanPrices.Group.CONTRAIL)))
            .then(Commands.literal("name").executes(ctx -> shop(ctx.getSource(), BeanPrices.Group.NAME)))
            .then(Commands.literal("classic").executes(ctx -> withPlayer(ctx.getSource(), com.coffeesaerosmp.auth.store.gui.ShopGui::openClassic)))
            .then(Commands.literal("tryon").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> withPlayer(ctx.getSource(), p -> com.coffeesaerosmp.auth.store.gui.ShopGui.tryOnById(p,
                    StringArgumentType.getString(ctx, "id"))))))
            .then(Commands.literal("purchase").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> withPlayer(ctx.getSource(), p -> com.coffeesaerosmp.auth.store.gui.ShopGui.purchaseById(p,
                    StringArgumentType.getString(ctx, "id"))))))
            .then(Commands.literal("list").executes(ctx -> shopList(ctx.getSource(), null))
                .then(Commands.literal("trails").executes(ctx -> shopList(ctx.getSource(), BeanPrices.Group.TRAIL)))
                .then(Commands.literal("contrails").executes(ctx -> shopList(ctx.getSource(), BeanPrices.Group.CONTRAIL)))
                .then(Commands.literal("name").executes(ctx -> shopList(ctx.getSource(), BeanPrices.Group.NAME))))
            .then(Commands.literal("buy")
                .then(Commands.argument("id", StringArgumentType.greedyString())
                    .suggests((c, b) -> { BeanPrices.catalogue().forEach(i -> b.suggest(i.id())); return b.buildFuture(); })
                    .executes(ctx -> askConfirm(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("confirm")
                .then(Commands.argument("id", StringArgumentType.greedyString())
                    .executes(ctx -> confirm(ctx.getSource(), StringArgumentType.getString(ctx, "id"))))));

        dispatcher.register(Commands.literal("aerostore")
            .requires(src -> src.hasPermission(OP))
            .then(Commands.literal("beans")
                .then(Commands.literal("give")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100_000))
                            .executes(ctx -> adminCredit(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                IntegerArgumentType.getInteger(ctx, "amount"), "staff grant"))
                            .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> adminCredit(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                    IntegerArgumentType.getInteger(ctx, "amount"), StringArgumentType.getString(ctx, "note")))))))
                .then(Commands.literal("take")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100_000))
                            .executes(ctx -> adminCredit(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                -IntegerArgumentType.getInteger(ctx, "amount"), "staff removal"))
                            .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> adminCredit(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                    -IntegerArgumentType.getInteger(ctx, "amount"), StringArgumentType.getString(ctx, "note")))))))
                .then(Commands.literal("show")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> adminShow(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))));
    }

    // ── /beans ────────────────────────────────────────────────────────────────

    private static int beans(CommandSourceStack src) {
        ServerPlayer p = player(src);
        if (p == null) return 0;
        if (!com.coffeesaerosmp.auth.store.PremiumGate.check(p)) return 0;
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) { reply(p, notLinked()); return; }
            int bal = BeanLedger.balance(mojang);
            List<BeanLedger.Entry> hist = BeanLedger.history(mojang, 5);
            run(p, () -> {
                if (bal < 0) { p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cBeans are unavailable right now.")); return; }
                p.sendSystemMessage(Component.literal(" §6☕ §eCoffee Beans: §f" + bal));
                for (BeanLedger.Entry e : hist) {
                    String sign = e.delta() > 0 ? "§a+" : "§c";
                    p.sendSystemMessage(Component.literal("  §8" + DAY.format(Instant.ofEpochMilli(e.atMs())) + " "
                        + sign + e.delta() + " §7" + describe(e)));
                }
                p.sendSystemMessage(Component.literal(" §7Spend them in ").append(link("§f/shop", "/shop", "Open the shop")));
            });
        });
        return 1;
    }

    private static String describe(BeanLedger.Entry e) {
        if ("SPEND".equals(e.kind())) {
            return "bought " + BeanPrices.catalogue().stream().filter(i -> i.id().equals(e.note()))
                .map(BeanPrices.Item::label).findFirst().orElse(String.valueOf(e.note()));
        }
        return e.note() == null ? e.kind().toLowerCase() : e.note();
    }

    // ── /shop ─────────────────────────────────────────────────────────────────

    private static int shop(CommandSourceStack src, BeanPrices.Group group) {
        ServerPlayer p = player(src);
        if (p == null) return 0;
        // 1.13.34: the shop is a GUI now (premium only, checked inside). The chat list below stays for /shop list.
        if (group == null) { com.coffeesaerosmp.auth.store.gui.ShopGui.openMain(p); return 1; }
        com.coffeesaerosmp.auth.store.gui.ShopGui.openCategory(p, switch (group) {
            case TRAIL -> com.coffeesaerosmp.auth.store.gui.ShopGui.Category.TRAILS;
            case CONTRAIL -> com.coffeesaerosmp.auth.store.gui.ShopGui.Category.CONTRAILS;
            case NAME -> com.coffeesaerosmp.auth.store.gui.ShopGui.Category.COLOURS;
        }, 0);
        return 1;
    }

    private static int shopList(CommandSourceStack src, BeanPrices.Group group) {
        ServerPlayer p = player(src);
        if (p == null) return 0;
        if (!com.coffeesaerosmp.auth.store.PremiumGate.check(p)) return 0;
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) { reply(p, notLinked()); return; }
            int bal = BeanLedger.balance(mojang);
            Set<String> owned = StoreState.get(local).owned();
            run(p, () -> render(p, group, bal, owned));
        });
        return 1;
    }

    private static void render(ServerPlayer p, BeanPrices.Group group, int bal, Set<String> owned) {
        p.sendSystemMessage(Component.literal("§8§m                                        "));
        p.sendSystemMessage(Component.literal(" §6☕ §eCoffee Bean Shop §7— you have §f" + (bal < 0 ? "?" : bal) + " Beans"));
        if (group == null) {
            p.sendSystemMessage(Component.literal(" ")
                .append(link("§b[Trails]", "/shop trails", "Walking trails"))
                .append(Component.literal(" "))
                .append(link("§b[Contrails]", "/shop contrails", "Airship contrails"))
                .append(Component.literal(" "))
                .append(link("§b[Name]", "/shop name", "Name colours, gradients, bold, join & leave messages")));
            p.sendSystemMessage(Component.literal(" §7Everything here is cosmetic and yours to keep. Try them first with ")
                .append(link("§f/cosmetics", "/cosmetics", "Your cosmetics")).append(Component.literal("§7.")));
        } else {
            for (BeanPrices.Item i : BeanPrices.catalogue()) {
                if (i.group() != group) continue;
                MutableComponent line = Component.literal("  §f" + i.label() + " §8— §6" + i.price() + " Beans ");
                if (owned.contains(i.id())) line.append(Component.literal("§a✔ owned"));
                else line.append(link("§a[Buy]", "/shop buy " + i.id(), "Buy " + i.label() + " for " + i.price() + " Beans"));
                p.sendSystemMessage(line);
            }
            p.sendSystemMessage(Component.literal(" ").append(link("§7« back", "/shop", "All categories")));
        }
        p.sendSystemMessage(Component.literal("§8§m                                        "));
    }

    private static int askConfirm(CommandSourceStack src, String rawId) {
        ServerPlayer p = player(src);
        if (p == null) return 0;
        if (!com.coffeesaerosmp.auth.store.PremiumGate.check(p)) return 0;
        String id = rawId.trim().toLowerCase(java.util.Locale.ROOT);
        var item = BeanPrices.catalogue().stream().filter(i -> i.id().equals(id)).findFirst();
        if (item.isEmpty()) { src.sendFailure(Component.literal("§cThat isn't for sale.")); return 0; }
        PENDING.put(p.getUUID(), new Pending(id, System.currentTimeMillis()));
        p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7Buy §f" + item.get().label() + "§7 for §6"
            + item.get().price() + " Beans§7? ")
            .append(link("§a[Confirm]", "/shop confirm " + id, "Spend " + item.get().price() + " Beans")));
        return 1;
    }

    private static int confirm(CommandSourceStack src, String rawId) {
        ServerPlayer p = player(src);
        if (p == null) return 0;
        if (!com.coffeesaerosmp.auth.store.PremiumGate.check(p)) return 0;
        String id = rawId.trim().toLowerCase(java.util.Locale.ROOT);
        Pending pend = PENDING.remove(p.getUUID());
        if (pend == null || !pend.id().equals(id) || System.currentTimeMillis() - pend.atMs() > CONFIRM_WINDOW_MS) {
            src.sendFailure(Component.literal("§cPick the item in §f/shop§c first, then confirm within a minute."));
            return 0;
        }
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) { reply(p, notLinked()); return; }
            BeanLedger.Outcome o = BeanLedger.buy(mojang, id);
            String label = BeanPrices.catalogue().stream().filter(i -> i.id().equals(id))
                .map(BeanPrices.Item::label).findFirst().orElse(id);
            reply(p, switch (o.result()) {
                case APPLIED -> "§aBought §f" + label + "§a. §7You have §f" + o.balance()
                    + " Beans§7 left. Wear it from §f/cosmetics§7.";
                case ALREADY_OWNED -> "§eYou already own " + label + ". Nothing was spent.";
                case INSUFFICIENT -> "§cNot enough Beans: it costs " + o.price() + ", you have " + o.balance() + ".";
                case NOT_FOR_SALE -> "§cThat isn't for sale.";
                default -> "§cThe purchase didn't go through and nothing was spent. Try again in a moment.";
            });
        });
        return 1;
    }

    // ── /aerostore beans ──────────────────────────────────────────────────────

    private static int adminCredit(CommandSourceStack src, String who, int amount, String note) {
        AsyncIo.submit(() -> {
            UUID mojang = resolve(src, who);
            if (mojang == null) { tell(src, "§cNo linked premium account for '" + who + "'. Use their Mojang uuid or have them online."); return; }
            String by = src.getTextName();
            BeanLedger.Outcome o = BeanLedger.credit(mojang, amount, amount > 0 ? "GRANT" : "REVERSAL", null,
                note + " (by " + by + ")");
            tell(src, switch (o.result()) {
                case APPLIED -> "§a" + (amount > 0 ? "Gave " : "Took ") + Math.abs(amount) + " Beans " + (amount > 0 ? "to " : "from ")
                    + who + ". §7Balance now §f" + o.balance();
                case INSUFFICIENT -> "§c" + who + " only has " + o.balance() + " Beans. Nothing changed.";
                default -> "§cFailed. Nothing changed (database down?).";
            });
        });
        return 1;
    }

    private static int adminShow(CommandSourceStack src, String who) {
        AsyncIo.submit(() -> {
            UUID mojang = resolve(src, who);
            if (mojang == null) { tell(src, "§cNo linked premium account for '" + who + "'."); return; }
            int bal = BeanLedger.balance(mojang);
            StringBuilder sb = new StringBuilder("§e" + who + " §7(" + mojang + ")§e: §f" + bal + " Beans");
            for (BeanLedger.Entry e : BeanLedger.history(mojang, 10)) {
                sb.append("\n  §8").append(DAY.format(Instant.ofEpochMilli(e.atMs()))).append(' ')
                  .append(e.delta() > 0 ? "§a+" : "§c").append(e.delta()).append(" §7").append(e.kind())
                  .append(e.note() == null ? "" : " " + e.note()).append(" §8→ ").append(e.balanceAfter());
            }
            tell(src, sb.toString());
        });
        return 1;
    }

    /** A Mojang uuid, or an online player's name mapped through MojangIds. Runs on AsyncIo. */
    private static UUID resolve(CommandSourceStack src, String who) {
        try { return UUID.fromString(who); } catch (IllegalArgumentException ignored) { }
        var server = src.getServer();
        if (server == null) return null;
        ServerPlayer online = server.getPlayerList().getPlayerByName(who);
        if (online == null) return null;
        return MojangIds.toMojang(online.getUUID()).orElse(null);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static int withPlayer(CommandSourceStack src, java.util.function.Consumer<ServerPlayer> action) {
        ServerPlayer p = player(src);
        if (p == null) return 0;
        action.accept(p);
        return 1;
    }

    private static String notLinked() {
        return "§cBeans need a premium (Mojang) account that has joined through the gate.";
    }

    private static ServerPlayer player(CommandSourceStack src) {
        try { return src.getPlayerOrException(); } catch (Exception e) { return null; }
    }

    private static MutableComponent link(String text, String command, String hover) {
        return Component.literal(text).setStyle(Style.EMPTY
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static void run(ServerPlayer p, Runnable r) {
        var server = p.getServer();
        if (server == null) return;
        server.execute(() -> { if (!p.hasDisconnected()) r.run(); });
    }

    private static void reply(ServerPlayer p, String msg) {
        run(p, () -> p.sendSystemMessage(Component.literal(TextUtil.PREFIX + msg)));
    }

    private static void tell(CommandSourceStack src, String msg) {
        var server = src.getServer();
        if (server != null) server.execute(() -> src.sendSystemMessage(Component.literal(TextUtil.PREFIX + msg)));
    }
}
