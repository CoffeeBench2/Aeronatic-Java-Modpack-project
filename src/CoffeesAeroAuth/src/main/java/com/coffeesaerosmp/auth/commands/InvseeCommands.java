package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.invsee.InvseeManager;
import com.coffeesaerosmp.auth.invsee.InvseeMenu;
import com.coffeesaerosmp.auth.invsee.PlayerInventoryView;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.UUID;

/**
 * {@code /invsee <player>} and {@code /invsee_echest <player>}.
 *
 * <p>Live and editable when the target is online; a read-only snapshot when they are not.
 *
 * <p>Both take a NAME rather than an {@code EntityArgument}, because the offline case is half the
 * point and an online-only argument type would reject it outright.
 */
public final class InvseeCommands {

    private InvseeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsee")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> open(ctx.getSource(),
                    StringArgumentType.getString(ctx, "player"), false))));

        dispatcher.register(Commands.literal("invsee_echest")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> open(ctx.getSource(),
                    StringArgumentType.getString(ctx, "player"), true))));
    }

    private static int open(CommandSourceStack src, String name, boolean enderChest) {
        ServerPlayer viewer;
        try {
            viewer = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("§cOnly a player can open an inventory view."));
            return 0;
        }

        // An admin looking inside someone's belongings belongs on the record, the same as any other
        // moderation action. Logged BEFORE the lookup, so an attempt at a bad name is recorded too.
        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.recordAdminCommand(viewer,
                (enderChest ? "/invsee_echest " : "/invsee ") + name);
        }

        ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(name);
        if (target != null) {
            if (target == viewer) {
                src.sendFailure(Component.literal(
                    "§cThat is your own inventory — open it normally."));
                return 0;
            }
            Container backing = enderChest
                ? target.getEnderChestInventory()
                : new PlayerInventoryView(target);
            openMenu(viewer, backing,
                "§a" + target.getGameProfile().getName()
                    + (enderChest ? " — Ender Chest" : " — Inventory"),
                enderChest, false);
            InvseeManager.trackOpen(target.getUUID(), viewer.getUUID());
            return 1;
        }

        // Offline. Resolve through the profile store rather than deriving a uuid from the name:
        // premium accounts here carry their real Mojang uuid, so derivation would silently target
        // an account that does not exist and report an empty inventory as fact.
        var store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile profile = store == null ? null : store.findByAnyName(name);
        if (profile == null) {
            src.sendFailure(Component.literal("§cNo profile matches §f" + name
                + "§c — check the spelling."));
            return 0;
        }
        UUID uuid = profile.getUUID();
        Container snap = InvseeManager.offlineSnapshot(src.getServer(), uuid, enderChest);
        if (snap == null) {
            src.sendFailure(Component.literal("§f" + profile.username
                + "§c has a profile but no saved playerdata yet — nothing to show."));
            return 0;
        }
        openMenu(viewer, snap,
            "§c[READ-ONLY] §7" + profile.username + (enderChest ? " — Ender Chest" : " — Inventory"),
            enderChest, true);
        viewer.sendSystemMessage(Component.literal(
            "§7Offline snapshot of §f" + profile.username + "§7 — edits are disabled. Writing an "
            + "offline .dat is how inventories get duped or lost."));
        return 1;
    }

    private static void openMenu(ServerPlayer viewer, Container backing, String title,
                                 boolean threeRows, boolean readOnly) {
        viewer.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal(title);
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return threeRows
                    ? InvseeMenu.threeRows(id, inv, backing, readOnly)
                    : InvseeMenu.fiveRows(id, inv, backing, readOnly);
            }
        });
    }
}
