package com.coffeesaerosmp.core.cosmetics;

import net.minecraft.world.entity.player.Player;

/**
 * How a pair of wings sits right now, from what the player is doing. Shared by the wing renderer and the wingtip
 * particles so embers fall from where the tips actually are.
 *
 * <ul>
 *   <li>standing: folded back, a slow breath;</li>
 *   <li>walking / sprinting: half open, a light beat in time with the steps;</li>
 *   <li>in the air (jumping, falling, creative flight): open, beating fast;</li>
 *   <li>gliding with an elytra-style fall flight: spread wide, slow strong strokes.</li>
 * </ul>
 *
 * @param span  wing length in blocks
 * @param fold  degrees swept back from straight out sideways (0 = spread, 90 = flat against the back)
 * @param lift  degrees the wing is raised (negative = lowered); the beat swings this
 * @param power 0..1 how hard the wings work (particle rate, outer-segment bend)
 */
public record WingPose(float span, float fold, float lift, float power) {

    public static WingPose of(Player p, float partial) {
        float t = p.tickCount + partial;
        double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
        boolean air = !p.onGround() && !p.isPassenger();
        float fold, base, amp, freq, power;
        if (p.isFallFlying()) {
            fold = 4; base = 2; amp = 20; freq = 0.22f; power = 1f;
        } else if (air || p.getAbilities().flying) {
            fold = 12; base = 10; amp = 34; freq = 0.55f; power = 0.85f;
        } else if (speed > 0.2) {
            fold = 26; base = 8; amp = 12; freq = 0.38f; power = 0.5f;
        } else if (speed > 0.02) {
            fold = 32; base = 8; amp = 7; freq = 0.22f; power = 0.25f;
        } else {
            fold = 38; base = 10; amp = 4; freq = 0.07f; power = 0.08f;   // at rest: open, so they read from the front
        }
        if (p.isCrouching()) { fold = Math.max(fold, 60); amp *= 0.5f; }
        float beat = (float) Math.sin(t * freq + (p.getId() % 7));
        return new WingPose(1.35f, fold + beat * amp * 0.25f, base + beat * amp, power);
    }
}
