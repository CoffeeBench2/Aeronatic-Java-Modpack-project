package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.invsee.InvseeManager;
import com.coffeesaerosmp.auth.invsee.InvseeMenu;
import com.coffeesaerosmp.auth.invsee.PlayerInventoryView;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
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

    /**
     * Online players first, then every known profile name.
     *
     * <p>Deliberately not just online players: these commands read offline playerdata too, and a
     * completer that only offers online names implies the offline case is unsupported.
     */
    private static final SuggestionProvider<CommandSourceStack> KNOWN_NAMES = (ctx, builder) -> {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        ctx.getSource().getServer().getPlayerList().getPlayers()
            .forEach(p -> names.add(p.getGameProfile().getName()));
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store != null) {
            for (PlayerProfile p : store.getAll()) {
                if (p.username != null && !p.username.isBlank()) names.add(p.username);
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /**
     * 🔴 The argument is named {@code target}, NOT {@code player}, and that is load-bearing.
     *
     * <p>FTB Essentials also registers {@code /invsee}, with its argument named {@code player} and
     * typed as an ENTITY SELECTOR. Brigadier merges command trees by node name, so its node won and
     * ours was never added — but our executor still ran and called
     * {@code StringArgumentType.getString(ctx, "player")} against an argument parsed as a selector:
     *
     * <pre>IllegalArgumentException: Argument 'player' is defined as EntitySelector, not class java.lang.String</pre>
     *
     * which reaches the player as a bare "An unexpected error occurred". Reported live 2026-09-22.
     *
     * <p>A unique name means the two can never be confused again: the worst case becomes our branch
     * not firing, instead of a crash. ⚠️ For OUR {@code /invsee} to actually run, FTB Essentials'
     * must be off — {@code config/ftbessentials.snbt} → {@code invsee { enabled: false }}. Ours is a
     * superset: offline snapshots, curios, and a watchdog audit line per open.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsee")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(KNOWN_NAMES)
                .executes(ctx -> open(ctx.getSource(),
                    StringArgumentType.getString(ctx, "target"), false))));

        dispatcher.register(Commands.literal("invsee_echest")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(KNOWN_NAMES)
                .executes(ctx -> open(ctx.getSource(),
                    StringArgumentType.getString(ctx, "target"), true))));

        dispatcher.register(Commands.literal("invsee_curios")
            .requires(src -> src.hasPermission(3))
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(KNOWN_NAMES)
                .executes(ctx -> openCurios(ctx.getSource(),
                    StringArgumentType.getString(ctx, "target")))));
    }

    /**
     * Curios and Accessories, read-only.
     *
     * <p>Gear here is invisible to {@code /invsee}, which reads the vanilla inventory only — a
     * backpack in an accessory slot is exactly the thing an admin would otherwise miss.
     *
     * <p>Online players only: both mod APIs need a live entity, and their offline data sits in each
     * mod's own private NBT. Saying so is better than showing an empty box, which would read as
     * "nothing equipped".
     */
    private static int openCurios(CommandSourceStack src, String name) {
        ServerPlayer viewer;
        try {
            viewer = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("§cOnly a player can open an inventory view."));
            return 0;
        }
        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.recordAdminCommand(viewer, "/invsee_curios " + name);
        }

        ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            src.sendFailure(Component.literal("§f" + name
                + "§c is not online. Curios and Accessories can only be read from a live player — "
                + "their offline data lives inside each mod's own storage."));
            return 0;
        }

        var result = com.coffeesaerosmp.auth.invsee.CuriosSnapshot.of(target);
        if (result.total() == 0) {
            src.sendSuccess(() -> Component.literal("§7" + target.getGameProfile().getName()
                + " has nothing equipped in Curios or Accessories."), false);
            return 1;
        }

        String title = "§c[READ-ONLY] §7" + target.getGameProfile().getName()
            + " — Curios " + result.curios() + " / Accessories " + result.accessories();
        openMenu(viewer, result.container(), title, result.container().getContainerSize() <= 27, true);
        viewer.sendSystemMessage(Component.literal(
            "§7Read-only snapshot — these are copies. Remove items via the player's own screen; "
            + "editing third-party slots by reflection is how gear gets voided."));
        InvseeManager.trackOpen(target.getUUID(), viewer.getUUID());
        return 1;
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
