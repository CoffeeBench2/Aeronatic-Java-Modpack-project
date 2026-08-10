package com.coffeesaerosmp.guard.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config for Coffees Aero Guard — {@code coffees_aero_guard-server.toml}.
 *
 * <p>These three settings moved out of {@code coffees_aero_auth-server.toml} on 2026-08-09.
 * <b>They do not migrate automatically:</b> NeoForge writes a fresh file per mod, so any value the
 * admin had customised in the auth config must be re-entered here once. The old keys are left in
 * the auth file harmless and unread — deleting them is optional tidying, not a requirement.
 */
public final class GuardConfig {

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.BooleanValue        LOCK_END_DIMENSION;
    public static final ModConfigSpec.BooleanValue        PUBLIC_INTERACT_ENABLED;

    private GuardConfig() {}

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Public-use blocks — override claim protection for specific blocks.").push("publicinteract");
        PUBLIC_INTERACT_ENABLED = b
            .comment("Let ANY player use blocks tagged #coffees_aero_guard:public_interact inside",
                     "someone else's claim — seats, chairs, ship controls.",
                     "The blocker is AeroClaims (its message is \"You don't have permission to use",
                     "this block\"), which has no allowlist of its own, so this un-cancels the",
                     "interaction event for tagged blocks only. Edit the tag + /reload to change the",
                     "list; no restart needed. Empty tag = does nothing.",
                     "NOTE the tag namespace changed from coffees_aero_auth to coffees_aero_guard.")
            .define("publicInteractEnabled", true);
        b.pop();

        b.comment("Dimension access.").push("dimensions");
        LOCK_END_DIMENSION = b
            .comment("Block players from entering the End by ANY route (portals, waystones, /tpa, grave",
                     "recalls...). Ops (permission 2+) bypass. Hot-reloadable: flip to false and save to",
                     "open the End without a restart.")
            .define("lockEndDimension", true);
        b.pop();


        SERVER_SPEC = b.build();
    }
}
