package com.coffeesaerosmp.guard.protect;

import com.coffeesaerosmp.guard.CoffeesAeroGuard;
import com.coffeesaerosmp.guard.config.GuardConfig;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Makes the dragon's island unclaimable while the rest of the End stays open for settlement.
 *
 * <h2>Why this exists</h2>
 * Opening the End for habitation meant taking {@code minecraft:the_end} back out of FTB Chunks'
 * {@code claiming.claim_dimension_blacklist}. That config is all-or-nothing per dimension: it can
 * close a whole dimension to claims or open all of it, and FTB Chunks has no notion of an
 * unclaimable <i>region</i>. The arena therefore has to be carved out in code.
 *
 * <p>The arena must stay unclaimed for two separate reasons. A claim would put protection over the
 * fight — the pillars, the crystals, the gateways and the exit portal all sit inside it, and a team
 * owning that ground could lock every other player out of the boss. And a claim there reads as
 * ownership of a server set piece that the End datapack and EDF Remastered both write into.
 *
 * <h2>How the refusal is delivered</h2>
 * FTB Chunks' claim event is an <b>Architectury</b> event ({@code ClaimedChunkEvent.BEFORE_CLAIM}),
 * not a NeoForge bus event, so it is registered rather than subscribed. Its contract is unusual and
 * worth stating because it is not the usual cancel-an-event shape:
 * {@code ChunkTeamDataImpl} takes {@code result.object()} straight off the returned
 * {@code CompoundEventResult} and, if that object is non-null and {@code isSuccess()} is false,
 * returns it as the claim's outcome — so returning a problem object IS the refusal, and the object's
 * own message is what the player sees. {@code interruptsFurtherEvaluation()} is never consulted.
 *
 * <p><b>The message is a literal sentence, not a translation key.</b> {@code ClaimResult.getMessage()}
 * wraps whatever string you hand {@code customProblem} in {@code Component.translatable}, and this
 * mod is server-side only — it cannot ship a client lang file, so a real key would render to the
 * player as the raw {@code some.mod.key} text. An unresolved key falls back to itself, so passing the
 * sentence makes the fallback the message. No § colour codes: FTB renders this in its own style and
 * a raw code would show as a literal character in some contexts.
 *
 * <h2>Scope</h2>
 * Claim only. Force-loading is not hooked separately ({@code BEFORE_LOAD} exists) because
 * force-loading a chunk in FTB Chunks requires the team to have claimed it first, so refusing the
 * claim already closes it.
 *
 * <p>Nobody is exempt, ops included. The point is that the arena is never owned by anyone, and an op
 * who genuinely needs to claim in there can set the radius to 0 — the config is hot-reloadable, so
 * that is a file edit and not a restart. This is deliberately unlike {@link DimensionLock}, where an
 * op bypass exists so admins can go and inspect a locked dimension.
 *
 * <p>All FTB and Architectury classes are confined to {@link Impl}, so this mod still loads on a
 * server with no FTB Chunks — the same containment {@code CoffeesAeroRailguard.FtbClaimGuard} uses.
 */
public final class EndArenaClaims {

    private EndArenaClaims() {}

    /**
     * Registers the claim listener. Called from the mod constructor.
     *
     * <p>🔴 <b>Reads NO config values.</b> This runs during mod construction, where a SERVER config
     * is not loaded yet, and {@code ModConfigSpec.ConfigValue.get()} throws
     * {@code IllegalStateException: Cannot get config value before config is loaded}. The first
     * version of this method logged the radius and centre here "for confirmation" — the exception was
     * swallowed by the catch below and the listener was never registered at all, so the entire arena
     * rule silently did nothing while the log showed only a one-line non-fatal warning. Caught by a
     * boot test on 2026-09-27.
     *
     * <p>The listener body may read config freely: it only runs when a player attempts a claim, which
     * is long after config load. Confirmation of the effective values is logged separately from
     * {@link #onServerStarted}.
     */
    public static void install() {
        if (!ModList.get().isLoaded("ftbchunks")) {
            CoffeesAeroGuard.LOGGER.info("[EndArena] FTB Chunks not present — claim guard skipped.");
            return;
        }
        try {
            Impl.install();
            CoffeesAeroGuard.LOGGER.info("[EndArena] FTB Chunks detected — claim listener registered.");
        } catch (Throwable t) {
            CoffeesAeroGuard.LOGGER.warn("[EndArena] claim guard failed to install (non-fatal): {}", t.toString());
        }
    }

    /**
     * Logs the values actually in force, once the per-world SERVER config exists.
     *
     * <p>This is not decoration. The live file is {@code <world>/serverconfig/coffees_aero_guard-server.toml},
     * so a default changed in code does nothing to an existing world and editing {@code defaultconfigs/}
     * does nothing either. Printing the effective numbers at boot is the only cheap way to answer
     * "is the arena rule actually on, and how big is it" without trying to claim a chunk.
     */
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!ModList.get().isLoaded("ftbchunks")) return;
        boolean on = GuardConfig.END_ARENA_NO_CLAIM.get();
        int radius = GuardConfig.END_ARENA_RADIUS.get();
        if (on && radius > 0) {
            CoffeesAeroGuard.LOGGER.info(
                "[EndArena] ON — End chunks within {} blocks of {}, {} cannot be claimed; "
                + "the rest of the End is claimable.",
                radius, GuardConfig.END_ARENA_CENTER_X.get(), GuardConfig.END_ARENA_CENTER_Z.get());
        } else {
            CoffeesAeroGuard.LOGGER.warn(
                "[EndArena] OFF (endArenaNoClaim={}, endArenaRadius={}) — the dragon's island CAN be "
                + "claimed. If that is not intended, check <world>/serverconfig/coffees_aero_guard-server.toml.",
                on, radius);
        }
    }

    /**
     * True when any part of chunk ({@code chunkX}, {@code chunkZ}) lies within {@code radius} blocks
     * of the centre.
     *
     * <p>Tested against the chunk's NEAREST point, not its centre. A 256-block radius with a
     * centre-point test would leave claimable slivers overlapping the arena at the boundary, because
     * a chunk is 16 blocks wide and its centre can sit outside the circle while two of its corners
     * sit inside. "Nobody can claim that area" has to mean no claim may cover any of it, so the whole
     * chunk is refused as soon as it touches the circle. The zone is therefore always slightly
     * larger than the radius — up to 22 blocks (a chunk diagonal) in the corners.
     *
     * <p>Arithmetic is in {@code long} on purpose: at the End's coordinate limits
     * {@code dx * dx + dz * dz} overflows a 32-bit int long before the distance itself becomes
     * unreasonable, and an overflow would wrap to a negative and read as "inside the circle" — the
     * whole dimension would silently become unclaimable.
     */
    static boolean touchesArena(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
        long minX = (long) chunkX << 4;
        long minZ = (long) chunkZ << 4;
        long nearestX = Math.max(minX, Math.min(centerX, minX + 15));
        long nearestZ = Math.max(minZ, Math.min(centerZ, minZ + 15));
        long dx = nearestX - centerX;
        long dz = nearestZ - centerZ;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private static final class Impl {

        /**
         * Handed to {@code customProblem} and shown to the player verbatim — see the class note on
         * why this is a sentence rather than a translation key.
         */
        private static final String MESSAGE =
            "You cannot claim the Ender Dragon's island. The rest of the End is fair game.";

        static void install() {
            dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent.BEFORE_CLAIM.register((source, chunk) -> {
                try {
                    if (!GuardConfig.END_ARENA_NO_CLAIM.get()) {
                        return dev.architectury.event.CompoundEventResult.pass();
                    }
                    var dimPos = chunk.getPos();
                    if (!Level.END.equals(dimPos.dimension())) {
                        return dev.architectury.event.CompoundEventResult.pass();
                    }
                    int radius = GuardConfig.END_ARENA_RADIUS.get();
                    if (radius <= 0) {                      // explicitly disabled
                        return dev.architectury.event.CompoundEventResult.pass();
                    }
                    if (!touchesArena(dimPos.x(), dimPos.z(),
                                      GuardConfig.END_ARENA_CENTER_X.get(),
                                      GuardConfig.END_ARENA_CENTER_Z.get(),
                                      radius)) {
                        return dev.architectury.event.CompoundEventResult.pass();
                    }
                    CoffeesAeroGuard.LOGGER.info(
                        "[EndArena] DENY claim of chunk [{}, {}] in the End by {}.",
                        dimPos.x(), dimPos.z(), source.getTextName());
                    return dev.architectury.event.CompoundEventResult.interruptFalse(
                        dev.ftb.mods.ftbchunks.api.ClaimResult.customProblem(MESSAGE));
                } catch (Throwable t) {
                    // Fail OPEN, matching FtbClaimGuard: a broken check must not make the whole
                    // dimension unclaimable. The arena being claimable is a moderation problem; the
                    // End being silently closed to claims is a support ticket from every player.
                    CoffeesAeroGuard.LOGGER.warn(
                        "[EndArena] claim check failed (allowing claim): {}", t.toString());
                }
                return dev.architectury.event.CompoundEventResult.pass();
            });
        }
    }
}
