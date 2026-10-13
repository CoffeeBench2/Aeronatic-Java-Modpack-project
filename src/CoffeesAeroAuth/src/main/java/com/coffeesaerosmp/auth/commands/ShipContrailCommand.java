package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.cosmetics.ShipContrails;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.Entitlements;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * {@code /shipcontrail [id|off]}: fit a contrail you own to the ship you're standing on (owner 2026-10-13). Only the
 * ship's AeroClaims owner may, and the ship must be claimed: an unclaimed ship can't be fitted, so nobody can paint
 * somebody else's ship. With no argument it says what the ship has.
 */
public final class ShipContrailCommand {

    private ShipContrailCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("shipcontrail")
            .then(Commands.argument("id", StringArgumentType.greedyString())
                .suggests((c, b) -> {
                    b.suggest("off");
                    CosmeticCatalog.all(CosmeticCatalog.Type.CONTRAIL).forEach(x -> b.suggest(x.id().substring(9)));
                    return b.buildFuture();
                })
                .executes(ctx -> fit(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"))))
            .executes(ctx -> info(ctx.getSource().getPlayerOrException())));
    }

    private static int info(ServerPlayer p) {
        UUID ship = shipUnder(p);
        if (ship == null) { msg(p, "§cStand on your ship first."); return 0; }
        var fit = ShipContrails.get(ship);
        msg(p, fit == null
            ? "§7This ship has no contrail fitted. It shows the contrail of whoever flies it. §f/shipcontrail <name>§7 to fit one."
            : "§7This ship flies §f" + label(fit.contrail()) + "§7, fitted by §f" + ShipNameCommand.ownerNameOf(fit.owner()) + "§7.");
        return 1;
    }

    private static int fit(ServerPlayer p, String raw) {
        if (!com.coffeesaerosmp.auth.store.PremiumGate.check(p)) return 0;
        UUID ship = shipUnder(p);
        if (ship == null) { msg(p, "§cStand on the ship you want to fit it to."); return 0; }
        UUID owner = ShipNameCommand.claimOwner(p, ship.toString());
        if (owner == null) {
            msg(p, "§cClaim this ship with AeroClaims first. Only a ship's owner can fit it with a contrail.");
            return 0;
        }
        if (!owner.equals(p.getUUID())) {
            msg(p, "§cThis ship belongs to §f" + ShipNameCommand.ownerNameOf(owner) + "§c. Only they can fit its contrail.");
            return 0;
        }
        String arg = raw.trim().toLowerCase(Locale.ROOT);
        if (arg.equals("off") || arg.equals("none")) {
            msg(p, ShipContrails.clear(ship) ? "§7Contrail removed. The ship shows its riders' contrails again."
                : "§7This ship had no contrail fitted.");
            return 1;
        }
        String id = arg.startsWith(Entitlements.CONTRAIL_PREFIX) ? arg : Entitlements.CONTRAIL_PREFIX + arg;
        var c = CosmeticCatalog.get(id);
        if (c.isEmpty() || c.get().type() != CosmeticCatalog.Type.CONTRAIL) {
            msg(p, "§cNo such contrail. §7Try §f/cosmetics§7 for the list.");
            return 0;
        }
        var snap = StoreState.get(p.getUUID());
        if (!Entitlements.mayUse(id, snap.effectiveRank(), snap.owned(), CosmeticCommands.preview(p))) {
            msg(p, "§cYou don't have that contrail yet.");
            return 0;
        }
        ShipContrails.fit(ship, id, p.getUUID());
        CoffeesAeroAuth.LOGGER.info("[ShipContrails] {} fitted {} to ship {}", p.getGameProfile().getName(), id, ship);
        msg(p, "§aFitted §f" + c.get().label() + "§a to this ship. §7It streams whenever the ship flies faster than a run, whoever is aboard.");
        return 1;
    }

    private static UUID shipUnder(ServerPlayer p) {
        String id = ShipNameCommand.shipIdUnder(p);
        try { return id == null ? null : UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
    }

    private static String label(String id) {
        return CosmeticCatalog.get(id).map(CosmeticCatalog.Cosmetic::label).orElse(id);
    }

    private static void msg(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(TextUtil.PREFIX + s));
    }
}
