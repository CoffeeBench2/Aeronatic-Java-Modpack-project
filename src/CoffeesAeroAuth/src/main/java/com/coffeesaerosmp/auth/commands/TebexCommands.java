package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.store.BeanLedger;
import com.coffeesaerosmp.auth.store.MojangIds;
import com.coffeesaerosmp.auth.store.Rank;
import com.coffeesaerosmp.auth.store.StoreGrants;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /aerostore tebex …} — the commands the Tebex plugin runs when someone pays (or charges back).
 * Configure each Tebex package with one of these; the full list is in Obaa {@code planning/tebex-setup.md}.
 *
 * <pre>
 *   aerostore tebex rank       {id} NAVIGATOR 30 {transaction}
 *   aerostore tebex beans      {id} 550 {transaction}
 *   aerostore tebex cosmetic   {id} trail:petal {transaction}
 *   aerostore tebex chargeback rank     {id} {transaction}
 *   aerostore tebex chargeback beans    {id} 550 {transaction}
 *   aerostore tebex chargeback cosmetic {id} trail:petal {transaction}
 * </pre>
 *
 * <p>🔴 Permission 4 (console) only: Tebex runs commands as the console, and an op at level 2 or 3 must not be able
 * to mint paid Beans or ranks by typing these. Staff grants use {@code /aerostore grant} / {@code beans give}.
 *
 * <p>Every command is safe to run twice with the same {@code {transaction}} (Tebex retries): ranks are claimed once
 * per transaction in {@code store_deliveries}, Beans are unique per (player, transaction) in the ledger, cosmetics
 * are an insert-or-ignore.
 */
public final class TebexCommands {

    private TebexCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("aerostore")
            .then(Commands.literal("tebex")
                .requires(src -> src.hasPermission(4))
                .then(Commands.literal("rank")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("rank", StringArgumentType.word())
                            .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .then(Commands.argument("txn", StringArgumentType.word())
                                    .executes(ctx -> rank(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "rank"), IntegerArgumentType.getInteger(ctx, "days"),
                                        StringArgumentType.getString(ctx, "txn"))))))))
                .then(Commands.literal("beans")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                            .then(Commands.argument("txn", StringArgumentType.word())
                                .executes(ctx -> beans(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                    IntegerArgumentType.getInteger(ctx, "amount"), StringArgumentType.getString(ctx, "txn")))))))
                .then(Commands.literal("cosmetic")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("cosmeticId", StringArgumentType.word())
                            .then(Commands.argument("txn", StringArgumentType.word())
                                .executes(ctx -> cosmetic(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "cosmeticId"), StringArgumentType.getString(ctx, "txn")))))))
                .then(Commands.literal("chargeback")
                    .then(Commands.literal("rank")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("txn", StringArgumentType.word())
                                .executes(ctx -> chargebackRank(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "txn"))))))
                    .then(Commands.literal("beans")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                                .then(Commands.argument("txn", StringArgumentType.word())
                                    .executes(ctx -> chargebackBeans(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                        IntegerArgumentType.getInteger(ctx, "amount"), StringArgumentType.getString(ctx, "txn")))))))
                    .then(Commands.literal("cosmetic")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("cosmeticId", StringArgumentType.word())
                                .then(Commands.argument("txn", StringArgumentType.word())
                                    .executes(ctx -> chargebackCosmetic(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "cosmeticId"), StringArgumentType.getString(ctx, "txn"))))))))));
    }

    static UUID parseId(String raw) {
        return com.coffeesaerosmp.auth.store.TebexIds.parse(raw);
    }

    private static UUID idOrFail(CommandSourceStack src, String raw) {
        UUID id = parseId(raw);
        if (id == null) {
            src.sendFailure(Component.literal("Not a UUID: '" + raw + "'. Tebex packages must use {id}, never {username}."));
            CoffeesAeroAuth.LOGGER.error("[Tebex] refused a delivery with id '{}': packages must use {id}", raw);
        }
        return id;
    }

    private static int rank(CommandSourceStack src, String rawId, String rawRank, int days, String txn) {
        UUID id = idOrFail(src, rawId);
        if (id == null) return 0;
        Rank r = Rank.parse(rawRank);
        if (r == Rank.NONE) { src.sendFailure(Component.literal("Unknown rank " + rawRank)); return 0; }
        AsyncIo.submit(() -> {
            var o = StoreGrants.grantRank(id, r, days, txn);
            CoffeesAeroAuth.LOGGER.info("[Tebex] rank {} x{}d for {} (txn {}) -> {}", r, days, id, txn, o);
            if (o == StoreGrants.Outcome.APPLIED) thank(src.getServer(), id, "your §f" + r.badge() + r.displayName() + "§a rank is active for "
                + days + " days. Set it up with §f/cosmetics§a.");
        });
        return 1;
    }

    private static int beans(CommandSourceStack src, String rawId, int amount, String txn) {
        UUID id = idOrFail(src, rawId);
        if (id == null) return 0;
        AsyncIo.submit(() -> {
            var o = BeanLedger.credit(id, amount, "PURCHASE", "tebex:" + txn, "Tebex " + txn);
            CoffeesAeroAuth.LOGGER.info("[Tebex] beans +{} for {} (txn {}) -> {} (balance {})", amount, id, txn, o.result(), o.balance());
            if (o.result() == BeanLedger.Result.APPLIED) thank(src.getServer(), id, "§f+" + amount + " Coffee Beans§a. You have §f"
                + o.balance() + "§a. Spend them in §f/shop§a.");
        });
        return 1;
    }

    private static int cosmetic(CommandSourceStack src, String rawId, String cosmeticId, String txn) {
        UUID id = idOrFail(src, rawId);
        if (id == null) return 0;
        AsyncIo.submit(() -> {
            var o = StoreGrants.grantCosmetic(id, cosmeticId, "BEANS", txn);
            CoffeesAeroAuth.LOGGER.info("[Tebex] cosmetic {} for {} (txn {}) -> {}", cosmeticId, id, txn, o);
        });
        return 1;
    }

    private static int chargebackRank(CommandSourceStack src, String rawId, String txn) {
        UUID id = idOrFail(src, rawId);
        if (id == null) return 0;
        AsyncIo.submit(() -> {
            var o = StoreGrants.expireRank(id);
            alert("Rank ended by chargeback/refund", id, txn, "Subscription expired now (" + o + ")");
        });
        return 1;
    }

    /**
     * Take back charged-back Beans. If some were already spent, take what's left and tell staff the shortfall:
     * cosmetics bought with them stay (Tebex handles the money side; whether to remove those or ban is a staff call).
     */
    private static int chargebackBeans(CommandSourceStack src, String rawId, int amount, String txn) {
        UUID id = idOrFail(src, rawId);
        if (id == null) return 0;
        AsyncIo.submit(() -> {
            int bal = Math.max(0, BeanLedger.balance(id));
            int take = Math.min(amount, bal);
            String result = "nothing left to take";
            if (take > 0) {
                var o = BeanLedger.credit(id, -take, "REVERSAL", "chargeback:" + txn, "Tebex chargeback " + txn);
                result = "took " + take + " (" + o.result() + ")";
            }
            alert("Beans chargeback", id, txn, result + (take < amount ? "; " + (amount - take) + " were already spent" : ""));
        });
        return 1;
    }

    private static int chargebackCosmetic(CommandSourceStack src, String rawId, String cosmeticId, String txn) {
        UUID id = idOrFail(src, rawId);
        if (id == null) return 0;
        AsyncIo.submit(() -> {
            var o = StoreGrants.revokeCosmetic(id, cosmeticId);
            alert("Cosmetic revoked by chargeback/refund", id, txn, cosmeticId + " (" + o + ")");
        });
        return 1;
    }

    private static void alert(String title, UUID mojang, String txn, String what) {
        CoffeesAeroAuth.LOGGER.warn("[Tebex] {} for {} (txn {}): {}", title, mojang, txn, what);
        var w = CoffeesAeroAuth.WATCHDOG;
        if (w == null) return;
        try {
            w.alert(WatchdogEvent.of(Severity.HIGH, title, what,
                "Mojang UUID", mojang.toString(), "Transaction", txn,
                "Account", MojangIds.toLocal(mojang).map(UUID::toString).orElse("not linked yet")));
        } catch (Throwable ignored) { }
    }

    /** "Thanks for supporting the server" to the buyer, if they're online right now. */
    private static void thank(MinecraftServer server, UUID mojang, String what) {
        if (server == null) return;
        var local = MojangIds.toLocal(mojang).orElse(null);
        if (local == null) return;
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(local);
            if (p != null) p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§a☕ Thanks for supporting the server! " + what));
        });
    }
}
