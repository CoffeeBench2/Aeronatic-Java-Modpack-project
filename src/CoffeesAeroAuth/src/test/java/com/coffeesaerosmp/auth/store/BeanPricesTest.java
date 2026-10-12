package com.coffeesaerosmp.auth.store;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BeanPricesTest {

    @Test
    void rankItemsAreNeverForSale() {
        // The subscription must keep a reason to exist: nothing a rank grants can be bought with Beans.
        for (var c : CosmeticCatalog.all()) {
            if (c.requires() != null) assertFalse(BeanPrices.forSale(c.id()), c.id() + " is a rank item but has a price");
        }
        assertFalse(BeanPrices.forSale("contrail:aurora"), "Admiral exclusive");
    }

    @Test
    void eventAndUnknownItemsAreNotForSale() {
        assertFalse(BeanPrices.forSale("trail:snow"), "event item");
        assertFalse(BeanPrices.forSale("trail:nope"));
        assertFalse(BeanPrices.forSale(null));
        assertFalse(BeanPrices.forSale("chat_title"), "Admiral only");
    }

    @Test
    void staffColoursAndThreeStopGradientsAreNeverForSale() {
        for (String s : Palette.STAFF_RESERVED) assertFalse(BeanPrices.forSale(Entitlements.COLOUR_PREFIX + s), s);
        for (String g : Palette.allGradients()) {
            if (Palette.gradientStops(g) != 2) assertFalse(BeanPrices.forSale(Entitlements.GRADIENT_PREFIX + g), g);
        }
    }

    @Test
    void specPrices() {
        assertEquals(100, BeanPrices.price("bold").getAsInt());
        assertEquals(200, BeanPrices.price("join_message").getAsInt());
        assertEquals(150, BeanPrices.price("trail:kettle").getAsInt());
        assertEquals(500, BeanPrices.price("contrail:espresso").getAsInt());
        assertEquals(150, BeanPrices.price("  TRAIL:Kettle ").getAsInt(), "case and whitespace insensitive");
    }

    @Test
    void catalogueIsConsistentAndUnique() {
        Set<String> seen = new HashSet<>();
        for (var i : BeanPrices.catalogue()) {
            assertTrue(seen.add(i.id()), "duplicate " + i.id());
            assertEquals(BeanPrices.price(i.id()).getAsInt(), i.price(), i.id());
            assertTrue(i.price() > 0);
            // Everything sold must actually be wearable once owned.
            assertTrue(Entitlements.mayUse(i.id(), Rank.NONE, Set.of(i.id())), i.id() + " can't be worn after buying");
        }
        assertTrue(seen.contains("trail:petal") && seen.contains("contrail:sakura") && seen.contains("bold"));
    }
}
