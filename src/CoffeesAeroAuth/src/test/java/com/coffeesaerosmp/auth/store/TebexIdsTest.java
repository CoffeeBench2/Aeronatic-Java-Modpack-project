package com.coffeesaerosmp.auth.store;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TebexIdsTest {

    @Test
    void acceptsDashedAndUndashedMojangUuids() {
        UUID u = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(u, TebexIds.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertEquals(u, TebexIds.parse("069a79f444e94726a5befca90e38aaf5"));
        assertEquals(u, TebexIds.parse("069A79F444E94726A5BEFCA90E38AAF5"));
    }

    @Test
    void refusesNamesSoAMisconfiguredPackageFailsLoudly() {
        // A package set up with {username} instead of {id} must not deliver to whoever owns that name later.
        assertNull(TebexIds.parse("MrCoffeeBench"));
        assertNull(TebexIds.parse(""));
        assertNull(TebexIds.parse(null));
        assertNull(TebexIds.parse("069a79f444e94726a5befca90e38aaf"));   // 31 hex
    }
}
