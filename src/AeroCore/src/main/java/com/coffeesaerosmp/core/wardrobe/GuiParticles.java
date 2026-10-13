package com.coffeesaerosmp.core.wardrobe;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A tiny 2D particle system for the Wardrobe previews, drawn with plain filled quads.
 *
 * <p>Built to be cheap: one fixed pool (no allocation per frame or per particle), a hard cap, time-based
 * updates so it looks the same at 30 or 240 FPS, and it only runs while the screen is open. Each cosmetic's
 * real particle is approximated by its look: soft rising puffs for cloud and smoke, twinkling sparks for brass
 * and copper, drifting petals for cherry leaves, colour-fading dust for transitions, flames that rise and
 * cool, flakes that fall and sway.
 */
public final class GuiParticles {

    static final int MAX = 220;

    private final float[] x = new float[MAX], y = new float[MAX], vx = new float[MAX], vy = new float[MAX];
    private final float[] age = new float[MAX], life = new float[MAX], size = new float[MAX], phase = new float[MAX];
    private final int[] c1 = new int[MAX], c2 = new int[MAX], kind = new int[MAX];
    private int count;
    private float carry;

    // Kinds: how a particle moves and fades.
    static final int PUFF = 0, SPARK = 1, DUST = 2, PETAL = 3, FLAME = 4, FLAKE = 5;

    public void clear() { count = 0; carry = 0; }

    public int alive() { return count; }

    /**
     * Emit for {@code dt} seconds of this cosmetic at (ex, ey).
     *
     * @param driftX horizontal drift in px/s (negative = trail streams to the left, as if walking right)
     * @param driftY vertical drift in px/s
     * @param px     pixels per block at this zoom, so "spread" and size match the real cosmetic's proportions
     */
    public void emit(WardrobeData.Fx fx, float ex, float ey, float dt, float driftX, float driftY, float px) {
        emit(fx, ex, ey, dt, driftX, driftY, px, 1f, 1f);
    }

    public void emit(WardrobeData.Fx fx, float ex, float ey, float dt, float driftX, float driftY, float px, float lifeMul) {
        emit(fx, ex, ey, dt, driftX, driftY, px, lifeMul, 1f);
    }

    /** As above; {@code lifeMul} stretches lifetimes (contrails trail further than footsteps). */
    public void emit(WardrobeData.Fx fx, float ex, float ey, float dt, float driftX, float driftY, float px, float lifeMul,
                     float density) {
        if (fx == null) return;
        float perSecond = fx.count() * 20f / fx.every() * 2.4f * density; // the preview is zoomed in: denser reads right
        carry += perSecond * dt;
        int n = (int) carry;
        carry -= n;
        Look look = look(fx);
        for (int i = 0; i < n && count < MAX; i++) {
            int k = count++;
            float sp = Math.max(0.05f, fx.spread()) * px * 1.8f;           // a touch wider than in-world: it's a showcase
            x[k] = ex + (float) ((Math.random() * 2 - 1) * sp);
            y[k] = ey + (float) ((Math.random() * 2 - 1) * sp * 0.5f);
            vx[k] = driftX + (float) ((Math.random() * 2 - 1) * look.jitter);
            vy[k] = driftY + look.rise + (float) ((Math.random() * 2 - 1) * look.jitter * 0.5f);
            age[k] = 0;
            life[k] = look.life * lifeMul * (0.7f + (float) Math.random() * 0.6f);
            // Real proportions (size is in 1/16 block), clamped so a big zoom doesn't turn particles into tiles.
            float s = look.size * (0.7f + (float) Math.random() * 0.6f) * Math.max(0.6f, fx.scale()) * px / 16f;
            size[k] = Math.max(2f, Math.min(14f, s));
            phase[k] = (float) (Math.random() * Math.PI * 2);
            c1[k] = look.from;
            c2[k] = look.to;
            kind[k] = look.kind;
        }
    }

    public void update(float dt) {
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            if (age[i] >= life[i]) {                 // swap-remove: keeps the pool dense, no allocation
                int last = --count;
                x[i] = x[last]; y[i] = y[last]; vx[i] = vx[last]; vy[i] = vy[last];
                age[i] = age[last]; life[i] = life[last]; size[i] = size[last]; phase[i] = phase[last];
                c1[i] = c1[last]; c2[i] = c2[last]; kind[i] = kind[last];
                i--;
                continue;
            }
            float sway = 0;
            if (kind[i] == PETAL || kind[i] == FLAKE) sway = (float) Math.sin(age[i] * 4 + phase[i]) * 10f;
            x[i] += (vx[i] + sway) * dt;
            y[i] += vy[i] * dt;
            if (kind[i] == PUFF) vx[i] *= (1 - 0.8f * dt);
        }
    }

    public void render(GuiGraphics g) {
        for (int i = 0; i < count; i++) {
            float t = age[i] / life[i];
            float a = kind[i] == SPARK
                ? (float) (0.5 + 0.5 * Math.sin(age[i] * 18 + phase[i])) * (1 - t)       // twinkle
                : (t < 0.15f ? t / 0.15f : 1 - (t - 0.15f) / 0.85f);                       // fade in, fade out
            int rgb = lerp(c1[i], c2[i], t);
            int alpha = Math.max(0, Math.min(255, (int) (a * 230)));
            if (alpha < 8) continue;
            float s = size[i] * (kind[i] == PUFF ? 0.7f + t * 0.8f : 1f);                 // puffs grow as they rise
            int x0 = Math.round(x[i] - s / 2), y0 = Math.round(y[i] - s / 2);
            int w = Math.max(1, Math.round(s)), h = kind[i] == PETAL ? Math.max(1, Math.round(s * 0.6f)) : w;
            int col = (alpha << 24) | (rgb & 0xFFFFFF);
            g.fill(x0, y0, x0 + w, y0 + h, col);
            if (kind[i] == PUFF && w >= 3) {                                               // soften puffs: a dimmer halo
                int halo = ((alpha / 3) << 24) | (rgb & 0xFFFFFF);
                g.fill(x0 - 1, y0 + 1, x0 + w + 1, y0 + h - 1, halo);
                g.fill(x0 + 1, y0 - 1, x0 + w - 1, y0 + h + 1, halo);
            }
        }
    }

    private record Look(int kind, int from, int to, float size, float life, float rise, float jitter) {}

    /** How each vanilla particle looks, in GUI terms. Sizes are in 1/16 block. */
    static Look look(WardrobeData.Fx fx) {
        String p = fx.particle();
        return switch (p) {
            case "minecraft:cloud" -> new Look(PUFF, 0xFFFFFF, 0xDDE6EE, 3.2f, 1.3f, -14, 10);
            case "minecraft:white_smoke" -> new Look(PUFF, 0xEDEDED, 0xB8B8B8, 2.8f, 1.6f, -22, 8);
            case "minecraft:wax_off" -> new Look(SPARK, 0xFFE38A, 0xF0B43C, 1.0f, 0.9f, -8, 22);
            case "minecraft:scrape" -> new Look(SPARK, 0x8FF0DF, 0x3FB7A8, 1.0f, 0.9f, -8, 22);
            case "minecraft:cherry_leaves" -> new Look(PETAL, 0xFFC4DD, 0xE88AB0, 1.6f, 2.2f, 18, 10);
            case "minecraft:small_flame" -> new Look(FLAME, 0xFFE07A, 0xFF5A1F, 1.1f, 0.7f, -30, 6);
            case "minecraft:snowflake" -> new Look(FLAKE, 0xFFFFFF, 0xDDEEFF, 1.0f, 2.0f, 20, 6);
            case "minecraft:end_rod" -> new Look(SPARK, 0xFFFFFF, 0xFFF3C8, 1.0f, 1.4f, 10, 6);
            case "minecraft:electric_spark" -> new Look(SPARK, 0xBDF6FF, 0x5FD8FF, 0.9f, 0.5f, -4, 30);
            case "minecraft:soul_fire_flame" -> new Look(FLAME, 0x9FF3FF, 0x2FA6D8, 1.1f, 0.7f, -30, 6);
            case "minecraft:flame" -> new Look(FLAME, 0xFFE07A, 0xFF5A1F, 1.4f, 0.8f, -30, 6);
            case "minecraft:note" -> new Look(SPARK, 0x7BE07B, 0x3FB0FF, 1.6f, 1.2f, -20, 8);
            case "minecraft:glow" -> new Look(SPARK, 0xE6FF8A, 0x9BE07A, 1.0f, 2.2f, -4, 6);
            case "minecraft:bubble_pop" -> new Look(PUFF, 0xDFF6FF, 0xA9DCF5, 1.4f, 0.9f, -18, 6);
            case "minecraft:enchant" -> new Look(SPARK, 0xE8E0FF, 0x9A7BFF, 1.0f, 1.0f, -16, 18);
            case "minecraft:smoke", "minecraft:large_smoke" -> new Look(PUFF, 0x8A8A8A, 0x4A4A4A, 3.0f, 1.6f, -16, 8);
            case "minecraft:dripping_honey" -> new Look(FLAKE, 0xF5B731, 0xE08A12, 1.2f, 1.4f, 40, 2);
            case "minecraft:dripping_water" -> new Look(FLAKE, 0x7FC8FF, 0x3F8FE0, 0.9f, 1.2f, 40, 2);
            case "minecraft:wax_on" -> new Look(SPARK, 0xFFC27A, 0xF08A3C, 1.0f, 0.9f, -8, 22);
            case "minecraft:dust" -> new Look(DUST, fx.rgb() >= 0 ? fx.rgb() : 0xFFFFFF, fx.rgb() >= 0 ? fx.rgb() : 0xFFFFFF, 1.4f, 1.0f, 6, 8);
            case "minecraft:dust_color_transition" -> new Look(DUST, fx.rgb() >= 0 ? fx.rgb() : 0xFFFFFF,
                fx.rgb2() >= 0 ? fx.rgb2() : 0xFFFFFF, 2.0f, 1.4f, 3, 8);
            default -> new Look(DUST, 0xFFFFFF, 0xCCCCCC, 1.0f, 1.0f, 0, 8);
        };
    }

    /**
     * The fx to emit for a cosmetic right now. Most are their own fx; the rainbow ones are re-coloured as time
     * passes, so the stream comes out in bands of every colour (a single fixed fade can't show a rainbow).
     */
    public static WardrobeData.Fx styled(String id, WardrobeData.Fx fx, float time) {
        if (fx == null || id == null) return fx;
        boolean rainbow = id.equals("trail:rainbow") || id.equals("contrail:rainbow") || id.equals("aura:spectrum")
            || id.equals("wings:prism");
        if (!rainbow) return fx;
        int c = com.coffeesaerosmp.core.cosmetics.ParticleFx.hsv(time * 0.35f, 0.85f, 1f);
        return new WardrobeData.Fx("minecraft:dust", c, -1, Math.max(1f, fx.scale()), fx.count(), fx.spread(), fx.every());
    }

    /** Auras whose look is mostly particles (the others are 3D objects the doll already wears). */
    public static boolean auraUsesParticles(String id) {
        return switch (id) {
            case "aura:updraft", "aura:spectrum", "aura:enchanted", "aura:snowglobe", "aura:raincloud" -> true;
            default -> false;
        };
    }

    static int lerp(int a, int b, float t) {
        if (a == b) return a;
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
