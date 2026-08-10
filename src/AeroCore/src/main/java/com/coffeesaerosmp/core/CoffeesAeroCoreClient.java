package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.client.TitleReplacer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ScreenEvent;

public class CoffeesAeroCoreClient {

    /** One migration attempt per session — see the call site. */
    private static boolean xaeroMigrationChecked = false;

    public static void init(IEventBus modBus) {
        // Native title screen (replaced FancyMenu in 1.3.0) — panorama + pack logo + cogwheels,
        // Join button with version gating, admin corner buttons.
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening e) -> {
            TitleReplacer.onScreenOpening(e);
            // Fetch live News from GitHub (once, off-thread) so the Announcements scroll can be edited
            // on GitHub without a pack rebuild/version bump. Falls back to the bundled config offline.
            // MUST run here, not in init(): init() is the mod constructor, which runs BEFORE NeoForge
            // loads configs — reading NEWS_URL.get() there throws "config not loaded" and crashes the
            // client. First screen open is well after config load, and refreshFromGitHub() is idempotent.
            com.coffeesaerosmp.core.announce.AnnouncementData.refreshFromGitHub();
            // Carry Xaero map/waypoint data across the Join address change (raw IP -> hostname).
            // Same constraint as the News fetch above: it reads the config to work out the current
            // address, so it cannot run from init(). Guarded so it costs nothing after the first
            // screen of a session; the migration itself is additionally idempotent on disk.
            if (!xaeroMigrationChecked) {
                xaeroMigrationChecked = true;
                com.coffeesaerosmp.core.client.XaeroDataMigration.run(
                    net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath());
            }
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> {
            TitleReplacer.onScreenInit(e);
            // Settings tile on the pause menu, so the client-mod switches are reachable in-game
            // and not only from the title screen.
            com.coffeesaerosmp.core.client.PauseMenuButton.onScreenInit(e);
        });

        // (The 1.2.0-era aerosmp:self_skin channel + own-view skin fix moved to the CoffeesAeroSkins
        // mod, which owns skin sync for every player on its aerosmp:skin_sync channel.)
    }
}
