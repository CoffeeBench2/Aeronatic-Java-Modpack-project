package com.coffeesaerosmp.auth.world;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectileJanitorRulesTest {

    private static final long MAX = 60 * 20;

    @Test
    void typesAreNormalised() {
        assertEquals(Set.of("ntgl:projectile", "cgs:nail"), ProjectileJanitorRules.types(Arrays.asList(" NTGL:Projectile ", "cgs:nail", "", null)));
        assertTrue(ProjectileJanitorRules.types(null).isEmpty());
    }

    @Test
    void onlyListedBulletsFromDiskAreDroppedOnLoad() {
        assertTrue(ProjectileJanitorRules.dropOnLoad(true, true, true));
        assertFalse(ProjectileJanitorRules.dropOnLoad(true, true, false), "a freshly fired bullet must live");
        assertFalse(ProjectileJanitorRules.dropOnLoad(true, false, true), "other entities are never touched");
        assertFalse(ProjectileJanitorRules.dropOnLoad(false, true, true), "disabled = no effect");
    }

    @Test
    void aFreshBulletIsKept() {
        assertFalse(ProjectileJanitorRules.stale(1000, 1000, 0, MAX));
        assertFalse(ProjectileJanitorRules.stale(1000 + MAX, 1000, (int) MAX, MAX), "exactly at the limit is still allowed");
    }

    @Test
    void aFrozenBulletAgesByServerTicksEvenThoughItsTickCountNeverMoves() {
        assertTrue(ProjectileJanitorRules.stale(1000 + MAX + 1, 1000, 0, MAX));
    }

    @Test
    void aLongLivedTickingBulletIsCaughtByItsOwnTickCount() {
        assertTrue(ProjectileJanitorRules.stale(1000, 1000, (int) MAX + 1, MAX));
    }

    @Test
    void clockGoingBackwardsNeverCountsAsAge() {
        assertFalse(ProjectileJanitorRules.stale(500, 1000, 0, MAX));
    }

    @Test
    void listConfigShape() {
        assertEquals(1, ProjectileJanitorRules.types(List.of("a:b", "A:B")).size());
    }
}
