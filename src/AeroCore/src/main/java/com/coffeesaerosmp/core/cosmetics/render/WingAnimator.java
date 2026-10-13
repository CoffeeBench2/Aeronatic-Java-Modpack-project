package com.coffeesaerosmp.core.cosmetics.render;

import com.coffeesaerosmp.core.cosmetics.WingPose;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Smooth, continuous wing motion for one player, advanced once per rendered frame (owner 2026-10-13: "the absolute
 * best animation"). {@link WingPose} says what the wings SHOULD be doing for what the player does right now; this
 * eases into it, so nothing ever snaps:
 *
 * <ul>
 *   <li><b>Spring-damped state</b>: fold, lift, beat amplitude and speed each chase their target with a critically
 *       damped spring, so going from standing to jumping to gliding blends, and landing has a little settle.</li>
 *   <li><b>A continuous beat phase</b>: the phase accumulates frequency × time, so changing speed never jumps the
 *       wings to a different point in the stroke.</li>
 *   <li><b>Follow-through</b>: the elbow and wrist trail the shoulder by a phase lag (a wave travelling out the
 *       wing), with more lag when the stroke is hard.</li>
 *   <li><b>Banking</b>: turning rolls the wings into the turn, from the body's yaw rate.</li>
 *   <li><b>Spin</b>: a separate inertial angle for rotors and windmills: spins up with speed, coasts down.</li>
 * </ul>
 */
public final class WingAnimator {

    /** What the renderer reads. Angles in degrees. */
    public static final class State {
        public float fold = 40, lift = 8, amp = 4, freq = 0.07f, power = 0.1f, bank, elbow, wrist, phase, spin, spinRate;
        float vFold, vLift, vAmp, vBank;
        float lastT = Float.NaN, lastYaw;
        /** Shoulder, elbow and wrist angles of the current stroke. */
        public float shoulder() { return lift + amp * (float) Math.sin(phase); }
        public float elbowBeat() { return amp * 0.7f * (float) Math.sin(phase - 0.55f - 0.25f * power); }
        public float wristBeat() { return amp * 0.55f * (float) Math.sin(phase - 1.1f - 0.4f * power); }
    }

    private static final Map<Player, State> STATES = new WeakHashMap<>();

    private WingAnimator() {}

    /** Advance this player's wings to render time {@code age + partial} and return the state. */
    public static State update(Player p, float partial) {
        State s = STATES.computeIfAbsent(p, k -> new State());
        float t = p.tickCount + partial;
        float yaw = p.yBodyRotO + (p.yBodyRot - p.yBodyRotO) * partial;
        if (Float.isNaN(s.lastT) || t < s.lastT || t - s.lastT > 40) {     // first frame, or after a gap: start settled
            s.lastT = t;
            s.lastYaw = yaw;
            WingPose target = WingPose.of(p, partial);
            s.fold = target.fold();
            s.lift = target.lift();
            return s;
        }
        float dt = Math.min(2f, t - s.lastT);                            // ticks since the last frame
        s.lastT = t;
        if (dt <= 0) return s;

        Target g = target(p);
        float k = 0.16f;                                                 // spring stiffness per tick²
        s.vFold = spring(s.fold, g.fold, s.vFold, k, dt);   s.fold += s.vFold * dt;
        s.vLift = spring(s.lift, g.lift, s.vLift, k, dt);   s.lift += s.vLift * dt;
        s.vAmp  = spring(s.amp, g.amp, s.vAmp, k * 0.6f, dt); s.amp += s.vAmp * dt;
        s.freq += (g.freq - s.freq) * (1 - (float) Math.exp(-0.15 * dt));
        s.power += (g.power - s.power) * (1 - (float) Math.exp(-0.12 * dt));
        s.phase = (float) ((s.phase + s.freq * dt) % (Math.PI * 2000));

        // banking from yaw rate (degrees per tick), wrapped to [-180,180]
        float dYaw = ((yaw - s.lastYaw) % 360 + 540) % 360 - 180;
        s.lastYaw = yaw;
        float bankTarget = Math.max(-28, Math.min(28, -dYaw / dt * 2.2f));
        s.vBank = spring(s.bank, bankTarget, s.vBank, 0.1f, dt);
        s.bank += s.vBank * dt;

        // inertial spin for rotors / windmills: target from speed, eased in and out
        double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo) + Math.abs(p.getY() - p.yo) * 0.5;
        float spinTarget = 4f + (float) Math.min(1.0, speed * 3.5) * 30f + (p.onGround() ? 0 : 10);
        s.spinRate += (spinTarget - s.spinRate) * (1 - (float) Math.exp(-0.05 * dt));
        s.spin = (s.spin + s.spinRate * dt) % 360f;

        s.elbow = s.elbowBeat();
        s.wrist = s.wristBeat();
        return s;
    }

    private record Target(float fold, float lift, float amp, float freq, float power) {}

    private static Target target(Player p) {
        double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
        boolean air = !p.onGround() && !p.isPassenger();
        if (p.isFallFlying()) return new Target(4, 2, 20, 0.22f, 1f);
        if (air || p.getAbilities().flying) return new Target(12, 10, 34, 0.55f, 0.85f);
        if (p.isCrouching()) return new Target(66, 4, 2, 0.1f, 0.05f);
        if (speed > 0.2) return new Target(26, 8, 12, 0.38f, 0.5f);
        if (speed > 0.02) return new Target(32, 8, 7, 0.22f, 0.25f);
        return new Target(38, 10, 4, 0.07f, 0.08f);
    }

    /** Critically damped spring: new velocity toward {@code target}. */
    private static float spring(float x, float target, float v, float k, float dt) {
        float damping = 2f * (float) Math.sqrt(k);
        float a = k * (target - x) - damping * v;
        return v + a * dt;
    }
}
