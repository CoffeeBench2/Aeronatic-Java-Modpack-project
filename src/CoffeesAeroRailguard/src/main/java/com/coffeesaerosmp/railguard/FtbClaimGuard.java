package com.coffeesaerosmp.railguard;

import net.neoforged.fml.ModList;

/**
 * Stops players from FTB-claiming railway chunks — the railway is SERVER property, and an FTB claim
 * on top of it would both look like ownership and fight the guard's rules. Registered only when FTB
 * Chunks is present; all FTB/architectury classes live in {@link Impl} so this mod loads fine
 * without them.
 */
public final class FtbClaimGuard {

    private FtbClaimGuard() {}

    public static void install() {
        if (!ModList.get().isLoaded("ftbchunks")) {
            CoffeesAeroRailguard.LOGGER.info("[Railguard] FTB Chunks not present — claim guard skipped.");
            return;
        }
        try {
            Impl.install();
            CoffeesAeroRailguard.LOGGER.info("[Railguard] FTB Chunks detected — railway chunks are unclaimable.");
        } catch (Throwable t) {
            CoffeesAeroRailguard.LOGGER.warn("[Railguard] FTB claim guard failed to install (non-fatal): {}", t.toString());
        }
    }

    private static final class Impl {

        static void install() {
            dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent.BEFORE_CLAIM.register((source, chunk) -> {
                try {
                    var dimPos = chunk.getPos();
                    var level = source.getServer().getLevel(dimPos.dimension());
                    if (level != null) {
                        RailguardData data = RailguardData.get(level);
                        long key = net.minecraft.world.level.ChunkPos.asLong(dimPos.x(), dimPos.z());
                        if (data.isChunkKeyProtected(key)) {
                            if (ProtectionEvents.DEBUG) {
                                CoffeesAeroRailguard.LOGGER.info(
                                    "[Railguard] DENY ftb-claim of chunk [{}, {}] in {} by {}.",
                                    dimPos.x(), dimPos.z(), dimPos.dimension().location(), source.getTextName());
                            }
                            return dev.architectury.event.CompoundEventResult.interruptFalse(
                                dev.ftb.mods.ftbchunks.api.ClaimResult.customProblem(
                                    "coffeesaerorailguard.railway_chunk"));
                        }
                    }
                } catch (Throwable t) {
                    CoffeesAeroRailguard.LOGGER.warn("[Railguard] claim-guard check failed (allowing claim): {}", t.toString());
                }
                return dev.architectury.event.CompoundEventResult.pass();
            });
        }
    }
}
