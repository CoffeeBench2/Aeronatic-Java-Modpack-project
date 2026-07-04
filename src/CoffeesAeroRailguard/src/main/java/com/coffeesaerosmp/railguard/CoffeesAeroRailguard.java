package com.coffeesaerosmp.railguard;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * Coffees Aero Railguard — server-side protection for the worldgen railway.
 *
 * <p>Provenance-exact: a mixin brackets Railways Untold's tick and every block it places is
 * recorded to per-dimension SavedData; ONLY those positions are protected (break/explosion/piston).
 * Player-placed blocks — even identical Create track laid right next to the generated line — are
 * never touched. Ops bypass; {@code /railguard mark} covers sections generated before install.</p>
 */
@Mod("coffeesaerorailguard")
public class CoffeesAeroRailguard {

    public static final Logger LOGGER = LogUtils.getLogger();

    public CoffeesAeroRailguard(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(ProtectionEvents::onBreak);
        NeoForge.EVENT_BUS.addListener(ProtectionEvents::onExplosion);
        NeoForge.EVENT_BUS.addListener(ProtectionEvents::onPiston);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
            RailguardCommands.register(event.getDispatcher()));

        if (ModList.get().isLoaded("railwaysuntold")) {
            LOGGER.info("[Railguard] initialized — live-tracking Railways Untold placements.");
        } else {
            LOGGER.warn("[Railguard] railwaysuntold not present — protecting previously recorded/marked positions only.");
        }
    }
}
