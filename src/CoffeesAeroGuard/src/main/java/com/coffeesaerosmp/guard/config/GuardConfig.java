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
    public static final ModConfigSpec.BooleanValue        DEBUG_INTERACT_LOGGING;
    public static final ModConfigSpec.IntValue            DRAGON_DAMAGE_DIVISOR;
    public static final ModConfigSpec.BooleanValue        DEBUG_DRAGON_SCALING;

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
        DEBUG_INTERACT_LOGGING = b
            .comment("Log every interaction with a tagged block, and every Numismatics openScreen call.",
                     "",
                     "🔴 OFF by default, and it must stay off in production. These were added as a",
                     "'temporary diagnostic' whose comment claimed it 'fires only for TAGGED blocks on",
                     "the server, so it cannot spam' — a vendor IS tagged, and a player clicking one",
                     "produced 20 log lines PER SECOND on 2026-09-09. Every line is formatted on the",
                     "server thread, on a server whose median tick is already 44ms of a 50ms budget.",
                     "",
                     "Turn on only while actually chasing an interaction that is being denied; the two",
                     "lines together tell you whether the event reaches us and whether anything cancels",
                     "it. Turn it off again immediately afterwards.")
            .define("debugInteractLogging", false);
        b.pop();

        b.comment("Dimension access.").push("dimensions");
        LOCK_END_DIMENSION = b
            .comment("Block players from entering the End by ANY route (portals, waystones, /tpa, grave",
                     "recalls...). Ops (permission 2+) bypass. Hot-reloadable: flip to false and save to",
                     "open the End without a restart.")
            .define("lockEndDimension", true);
        b.pop();

        b.comment("Ender dragon difficulty.").push("enderdragon");
        DRAGON_DAMAGE_DIVISOR = b
            .comment("Divide every point of damage dealt to the ender dragon by this, BEFORE it is",
                     "applied. With the divisor at 1000 and the dragon's max health at 1000 (set by the",
                     "coffees_aero_end datapack), killing it costs exactly 1000 x 1000 = 1,000,000",
                     "points of damage. Set to 1 to disable and get an ordinary 1000 HP dragon.",
                     "",
                     "🔑 This MUST be pre-application, which is why it is Java and not a datapack.",
                     "minecraft:max_health is capped at 1024.0 by the attribute itself, so the dragon's",
                     "REAL health can never exceed that no matter what the boss bar claims. A datapack",
                     "can only scale damage reactively — read health after the hit, write back a smaller",
                     "value — and that cannot survive a single hit bigger than the dragon's real health,",
                     "because the dragon is already dead and there is no next tick to correct. On",
                     "2026-09-26 a 2048-damage sword killed the '1,000,000 HP' dragon with the bar still",
                     "reading 999,100. A Create Big Cannons shell does the same thing in normal play.",
                     "Scaling here, before the damage lands, means real health drops by at most",
                     "amount/divisor per hit and a one-shot would need 1,000,000 damage in one blow.",
                     "",
                     "NOTE healing is deliberately NOT scaled. Vanilla crystal healing uses setHealth()",
                     "and fires no event, so it cannot be intercepted here — and leaving it unscaled is",
                     "the better fight anyway: it makes destroying the pillars' crystals first genuinely",
                     "mandatory rather than optional.")
            .defineInRange("enderDragonDamageDivisor", 1000, 1, 1_000_000);
        DEBUG_DRAGON_SCALING = b
            .comment("Log every scaled dragon hit (raw -> scaled). OFF by default.",
                     "Exists because a scaling hook that silently does not fire is indistinguishable",
                     "from one that works until the dragon dies in 1000 damage instead of 1,000,000.",
                     "Turn it on for one fight to confirm the hook is live, then turn it off.")
            .define("debugEnderDragonScaling", false);
        b.pop();


        SERVER_SPEC = b.build();
    }
}
