package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.advancements.AdvancementHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether an advancement is a recipe unlock, so its criteria listeners can be skipped.
 *
 * <h2>Why this exists — measured, not guessed</h2>
 * A 180-second spark profile of the live server on 2026-09-08 put the advancement system at
 * <b>9.39% of the server thread</b>, and <b>7.89% (14,188 ms of 179,860 ms)</b> in one path:
 * {@code ServerPlayer$2.slotChanged → InventoryChangeTrigger.trigger}. Every change to any inventory
 * slot linearly scans every {@code inventory_changed} listener registered for that player and tests
 * its item predicate.
 *
 * <p>Scanning the pack's jars gave the reason: <b>10,018 advancements ship, 9,195 of them are recipe
 * unlocks, and 9,168 of those carry an {@code inventory_changed} criterion</b> — so 98% of every
 * listener in that scan exists purely to fill in the recipe book. The per-player advancement files
 * confirm the cost falls on everyone: the file only records advancements a player has <i>started</i>,
 * so the ~7,700 a veteran has never touched still have live listeners. MrCoffeeBench had 2,281 of
 * 10,018 tracked and zero outstanding recipe advancements, and was still paying full price.
 *
 * <h2>Why dropping them is safe on THIS server</h2>
 * <ul>
 *   <li>{@code doLimitedCrafting} is <b>false</b> — a locked recipe never blocks crafting anything;</li>
 *   <li>the pack ships EMI, which is what players actually browse and auto-fill with;</li>
 *   <li>recipes already unlocked are <b>kept</b> — they live in the player's {@code recipeBook} NBT,
 *       not in advancements, so nobody loses anything they already had.</li>
 * </ul>
 * The cost is that the recipe book stops filling in as new ingredients are picked up. That was the
 * owner's explicit call on 2026-09-08, and it is fully reversible: nothing is written or deleted, so
 * flipping {@code skipRecipeAdvancementListeners} back to false re-registers everything on next join.
 *
 * <h2>🔴 Both folder spellings are in use</h2>
 * 1.21 renamed the data folder {@code advancements/ → advancement/}, and mods did not move in step.
 * The pack ships <b>8,717</b> advancements under a {@code recipes/} path and <b>478</b> under
 * {@code recipe/} (tombstone, stellarity and others). Matching only the plural would silently miss
 * 478 of them, which would look like the fix half-working rather than like a bug.
 *
 * <h2>The display guard</h2>
 * Zero of the 9,195 recipe-path advancements in the pack carry a {@code display} block — datagen never
 * writes one for them, and a real advancement always has one. So the display check adds no filtering
 * today; it is here to fail SAFE against a future mod that puts a genuine, visible advancement under a
 * {@code recipes/} folder. That advancement would keep working. This runs once per advancement per
 * join, never per tick, so the extra check is free.
 *
 * <p>⚠️ It deliberately does NOT key on "grants a recipe reward". Three recipe-path advancements in
 * the pack grant no recipe ({@code minecraft:recipes/decoration/end_crystal} and two tree roots), and
 * six hidden recipe unlocks live off-path under normal advancement folders. Those six stay registered;
 * six listeners out of 9,358 is not worth widening the predicate for.
 */
public final class RecipeAdvancementFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroAuth-AdvFilter");

    /** Total listeners skipped since start. Server-thread only, so a plain long is enough. */
    private static long skipped;
    private static boolean announced;

    private RecipeAdvancementFilter() {}

    /**
     * @return {@code true} if {@code holder} is a recipe unlock whose listeners should not be
     *         registered. Fails CLOSED — any config problem returns false, i.e. register as vanilla.
     */
    public static boolean isSkippableRecipeUnlock(AdvancementHolder holder) {
        if (holder == null) return false;

        boolean on;
        try {
            on = AuthConfig.SKIP_RECIPE_ADVANCEMENT_LISTENERS.get();
        } catch (Exception e) {
            return false;   // config not loaded yet -> behave exactly like vanilla
        }
        if (!on) return false;

        String path = holder.id().getPath();
        if (!path.startsWith("recipes/") && !path.startsWith("recipe/")) return false;

        // A real advancement always has a display block; a datagen recipe advancement never does.
        if (holder.value().display().isPresent()) return false;

        skipped++;
        if (!announced) {
            announced = true;
            LOGGER.info("Skipping recipe-advancement criteria listeners (perf). "
                      + "Crafting is unaffected (doLimitedCrafting=false) and already-unlocked "
                      + "recipes are kept. Set skipRecipeAdvancementListeners=false to restore.");
        }
        return true;
    }

    /** Running total of skipped registrations, for the per-join summary log. */
    public static long skippedTotal() {
        return skipped;
    }
}
