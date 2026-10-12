package com.coffeesaerosmp.auth.cosmetics.fx;

import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpriteChoreoTest {

    @Test
    void everyCosmeticHasAChoreographyMatchingItsCount() {
        for (var c : CosmeticCatalog.all()) {
            int n = SpriteChoreo.count(c.id());
            assertTrue(n > 0 && n <= 12, c.id() + " count " + n);
            for (double t = 0; t < 10; t += 0.15) {
                var f = SpriteChoreo.frames(c.id(), t, 0.15, 1, 0, 1.8);
                assertEquals(n, f.size(), c.id() + " at " + t);
            }
        }
    }

    @Test
    void spritesStayNearTheMannequinAndAreVisible() {
        for (var c : CosmeticCatalog.all()) {
            for (double t = 0; t < 20; t += 0.1) {
                for (var f : SpriteChoreo.frames(c.id(), t, 0.1, 0, 1, 1.8)) {
                    double r = Math.sqrt(f.x() * f.x() + f.z() * f.z());
                    assertTrue(r < 4.5, c.id() + " drifted " + r);
                    assertTrue(f.y() > -1 && f.y() < 3.2, c.id() + " y " + f.y());
                    assertTrue(f.scale() > 0 && f.scale() < 2, c.id() + " scale " + f.scale());
                    assertTrue(Double.isFinite(f.x()) && Double.isFinite(f.z()));
                }
            }
        }
    }

    @Test
    void motionIsContinuousExceptAtWraps() {
        // Keyframes 3 ticks apart: a sprite may only jump when it reports a wrap (then it's teleported, not slid).
        for (var c : CosmeticCatalog.all()) {
            double dt = 0.15;
            var prev = SpriteChoreo.frames(c.id(), 1.0, dt, 1, 0, 1.8);
            for (double t = 1.0 + dt; t < 8; t += dt) {
                var cur = SpriteChoreo.frames(c.id(), t, dt, 1, 0, 1.8);
                for (int k = 0; k < cur.size(); k++) {
                    var a = prev.get(k);
                    var b = cur.get(k);
                    double jump = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
                    if (!b.wrap()) assertTrue(jump < 1.2, c.id() + " sprite " + k + " jumped " + jump + " at t=" + t);
                }
                prev = cur;
            }
        }
    }

    @Test
    void trailsStreamBehindTheDirectionOfTravel() {
        // Walking towards +x: cloud puffs must end up behind (negative x), never ahead.
        double sumX = 0;
        for (double t = 0; t < 5; t += 0.2) for (var f : SpriteChoreo.frames("trail:cloudwalk", t, 0.2, 1, 0, 1.8)) sumX += f.x();
        assertTrue(sumX < 0, "puffs should trail behind, sum x " + sumX);
    }
}
