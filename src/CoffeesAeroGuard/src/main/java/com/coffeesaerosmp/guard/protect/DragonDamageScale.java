package com.coffeesaerosmp.guard.protect;

import com.coffeesaerosmp.guard.CoffeesAeroGuard;
import com.coffeesaerosmp.guard.config.GuardConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Scales damage dealt to the ender dragon <b>before it is applied</b>, which is what lets the End
 * fight have a pool far larger than 1024 on a mob whose real health can never exceed that.
 *
 * <p><b>2026-09-27:</b> the {@code coffees_aero_end} datapack is disabled, so the dragon's real max
 * health is now EDF Remastered's stock {@code DragonHealth} of <b>500</b> rather than the datapack's
 * 300, and the divisor came down to 4 — a 2,000-point pool behind the ordinary vanilla boss bar. The
 * divisor and whatever sets real health are a PAIR; see {@code GuardConfig} for the arithmetic.
 *
 * <h2>Why this is Java and not a datapack</h2>
 * {@code minecraft:max_health} is a {@code RangedAttribute} hard-capped at <b>1024.0</b>, so the
 * dragon's real health is 300 (set by the {@code coffees_aero_end} datapack) no matter what the boss
 * bar says. The first attempt scaled damage <i>reactively</i> from a datapack: each tick read
 * {@code Health}, work out how much it dropped, write back a smaller value. That is arithmetically
 * exact and still fundamentally broken — <b>a single hit larger than the dragon's real health kills it
 * outright</b>, because it is already dead and there is no next tick in which to correct anything.
 *
 * <p>Observed 2026-09-26: a 2048-attack-damage + Sharpness 255 sword (~2176 to the head) killed the
 * "1,000,000 HP" dragon with the boss bar still reading <b>999,100</b>. The bar was honest; the death
 * was premature. This is not only a cheat-weapon problem — a Create Big Cannons shell does hundreds of
 * damage per hit, so once real health falls below one shell the dragon dies to that shell in ordinary
 * play.
 *
 * <p>Scaling here, in {@link LivingIncomingDamageEvent}, happens before the damage is subtracted. Real
 * health therefore drops by at most {@code amount / divisor} per hit, and a genuine one-shot would need
 * 300,000 damage in a single blow.
 *
 * <h2>What is deliberately NOT handled</h2>
 * <b>Healing.</b> Vanilla's end-crystal heal calls {@code setHealth()} directly and fires no event, so
 * it cannot be intercepted from here at all. Left unscaled on purpose: on a pool this large it makes
 * destroying the pillar crystals first genuinely mandatory instead of optional, which is a better fight
 * than a dragon that quietly ignores them.
 *
 * <p><b>Vanilla's own part reduction.</b> {@code EnderDragon} already turns a hit on any body part
 * other than the head into {@code amount/4 + 1} before {@code LivingEntity#hurt} runs, so that has
 * happened by the time this event fires. Head hits stay worth four times a body hit, exactly as in
 * vanilla — scaling is applied on top of that, not instead of it.
 *
 * <h2>Scope note</h2>
 * Guard's remit is "who may interact with what". A boss damage rule is a stretch of that, and it lives
 * here anyway because Guard is the server-side mod that registers no blocks or items and is therefore
 * safe to add to and remove from a live world — the same property that makes the End lock live here.
 */
public final class DragonDamageScale {

    private DragonDamageScale() {}

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;

        // ── the finale withers are passive toward the dragon ──────────────────────
        // The datapack puts the dragon and its five withers on one team with friendly
        // fire off, which stops a wither ever TARGETING the dragon. That is not
        // sufficient on its own: a skull already in flight when the team forms, or a
        // wither's own explosion, still lands. Refusing the damage outright here closes
        // that gap. The withers exist to kill players, not to help them.
        Entity source = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        if (source instanceof WitherBoss || direct instanceof WitherBoss
                || direct instanceof WitherSkull) {
            event.setCanceled(true);
            return;
        }

        int divisor = GuardConfig.DRAGON_DAMAGE_DIVISOR.get();
        if (divisor <= 1) return;                       // explicitly disabled

        float raw = event.getAmount();
        if (raw <= 0.0f) return;

        float scaled = raw / divisor;
        event.setAmount(scaled);

        if (GuardConfig.DEBUG_DRAGON_SCALING.get()) {
            CoffeesAeroGuard.LOGGER.info(
                "[Dragon] {} -> {} (divisor {}), health {} of {}",
                raw, scaled, divisor, dragon.getHealth(), dragon.getMaxHealth());
        }
    }
}
