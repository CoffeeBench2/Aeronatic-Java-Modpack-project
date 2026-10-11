package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.CosmeticCatalog.Cosmetic;
import com.coffeesaerosmp.auth.store.CosmeticCatalog.Type;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The catalog is hand-edited: these limits stop a laggy, blinding or misleading entry from shipping. */
class CosmeticCatalogTest {

    @Test
    void everyEntryIsWithinTheLimits() {
        for (Cosmetic c : CosmeticCatalog.all()) {
            var fx = c.fx();
            assertTrue(fx.count() >= 1 && fx.count() <= CosmeticCatalog.MAX_COUNT, c.id() + " count " + fx.count());
            int min = c.type() == Type.TRAIL ? CosmeticCatalog.MIN_TRAIL_TICKS : CosmeticCatalog.MIN_CONTRAIL_TICKS;
            assertTrue(fx.everyTicks() >= min, c.id() + " fires every " + fx.everyTicks() + " ticks, min " + min);
            assertTrue(fx.spread() <= 0.5f, c.id() + " is too wide: contrails must stay thin");
            assertFalse(CosmeticCatalog.BANNED.contains(fx.particleId()), c.id() + " uses banned " + fx.particleId());
            assertTrue(fx.particleId().startsWith("minecraft:"), c.id() + " must be a vanilla particle");
        }
    }

    @Test
    void idsCarryTheirTypePrefixAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Cosmetic c : CosmeticCatalog.all()) {
            assertTrue(seen.add(c.id()), "duplicate " + c.id());
            String prefix = c.type() == Type.TRAIL ? Entitlements.TRAIL_PREFIX : Entitlements.CONTRAIL_PREFIX;
            assertTrue(c.id().startsWith(prefix), c.id() + " lacks " + prefix);
            assertEquals(c.id(), c.id().toLowerCase(), "ids are lower-case data");
            assertFalse(c.label().isBlank());
            assertFalse(c.blurb().isBlank());
        }
    }

    @Test
    void colouredDustHasAColourAndOthersDoNot() {
        for (Cosmetic c : CosmeticCatalog.all()) {
            var fx = c.fx();
            boolean dust = fx.particleId().equals("minecraft:dust") || fx.particleId().equals("minecraft:dust_color_transition");
            assertEquals(dust, fx.rgb() >= 0, c.id() + ": colour only on dust");
            assertEquals(fx.particleId().equals("minecraft:dust_color_transition"), fx.rgb2() >= 0, c.id());
        }
    }

    @Test
    void everyRankRungHasSomethingToShowFor() {
        for (var cap : new Entitlements.Capability[]{Entitlements.Capability.TRAIL_BASIC, Entitlements.Capability.TRAIL,
                Entitlements.Capability.CONTRAIL, Entitlements.Capability.CONTRAIL_EXCLUSIVE}) {
            assertTrue(CosmeticCatalog.all().stream().anyMatch(c -> c.requires() == cap), "nothing unlocks with " + cap);
        }
        assertTrue(CosmeticCatalog.all().stream().anyMatch(c -> c.requires() == null), "there should be Beans/event items");
    }

    @Test
    void lookupIsCaseInsensitiveAndRejectsUnknown() {
        assertTrue(CosmeticCatalog.get("TRAIL:CloudWalk").isPresent());
        assertTrue(CosmeticCatalog.get("trail:nope").isEmpty());
        assertTrue(CosmeticCatalog.get(null).isEmpty());
        assertFalse(CosmeticCatalog.all(Type.CONTRAIL).isEmpty());
    }
}
