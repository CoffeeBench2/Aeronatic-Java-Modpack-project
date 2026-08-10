package com.coffeesaerosmp.guard;

import com.coffeesaerosmp.guard.config.GuardConfig;
import com.coffeesaerosmp.guard.protect.AdminBypass;
import com.coffeesaerosmp.guard.protect.DimensionLock;
import com.coffeesaerosmp.guard.protect.PublicInteract;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Claim and interaction rules — "who may interact with what", and nothing else.
 *
 * <p>Split out of CoffeesAeroAuth on 2026-08-09. Auth had reached 15,550 lines across 82 files, and
 * a protection change (a seat tag, a claim override) should not require restarting the mod that
 * owns login, MySQL and Discord. The coupling was already thin — six imports — which is what made
 * the split cheap.
 *
 * <p><b>Deliberately left in auth:</b> {@code SaveGuard} (it banks playtime and needs the profile
 * store — profile machinery, not protection) and {@code PlotGuard} (crash *prevention* wired into
 * auth's join path and mixins; moving it means moving mixins for no benefit).
 *
 * <p>Registers no blocks or items, so unlike a content mod this is safe to add to — and remove
 * from — a live world without voiding anything.
 */
@Mod(CoffeesAeroGuard.MOD_ID)
public class CoffeesAeroGuard {

    public static final String MOD_ID = "coffees_aero_guard";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public CoffeesAeroGuard(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, GuardConfig.SERVER_SPEC);

        NeoForge.EVENT_BUS.addListener(CoffeesAeroGuard::onRegisterCommands);

        // Dimension gate.
        NeoForge.EVENT_BUS.addListener(DimensionLock::onTravelToDimension);


        // ── Un-cancelling handlers ────────────────────────────────────────────────
        // LOWEST priority + receiveCanceled=true is the whole mechanism: these run AFTER a claim
        // mod's setCanceled(true) and reverse it. Registering them any other way silently does
        // nothing, because the event never reaches a listener that doesn't opt into cancelled ones.

        // /aerobypass — per-op, per-session.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
            PlayerInteractEvent.RightClickBlock.class, AdminBypass::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
            PlayerInteractEvent.RightClickItem.class, AdminBypass::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
            BlockEvent.BreakEvent.class, AdminBypass::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
            BlockEvent.EntityPlaceEvent.class, AdminBypass::onBlockPlace);

        // Public-use blocks — keyed on a BLOCK TAG, so it is a property of the block rather than of
        // the player, and the list is datapack-driven (/reload, no restart).
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
            PlayerInteractEvent.RightClickBlock.class, PublicInteract::onRightClickBlock);

        // Bypass is per-session by design — clear it on logout so it can never persist silently.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) ->
            AdminBypass.clear(e.getEntity().getUUID()));

        LOGGER.info("Coffees Aero Guard loaded — claim/interaction rules.");
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        // /aerobypass — admin only: toggle protection bypass so you can open/break/place on any
        // claimed ship or protected block without owning it (aeroclaims has no bypass of its own).
        // Also covers aeroclaims' PACKET-level protection (assemble, glue, toolbox, clipboard,
        // wrench, throttle, kinetic placer, block-breaking) via AeroClaimsProtectionBypassMixin —
        // those route through CreateProtectionHelper rather than NeoForge events, so the event
        // handlers above never see them.
        event.getDispatcher().register(Commands.literal("aerobypass")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                boolean on = AdminBypass.toggle(player.getUUID());
                player.sendSystemMessage(Component.literal(on
                    ? "§a[Bypass] ON §7— you can now open/break/place, and assemble, glue, wrench or throttle any claimed ship. §e/aerobypass§7 to turn off."
                    : "§e[Bypass] OFF §7— claim protection applies to you again."));
                return 1;
            })
        );
    }
}
