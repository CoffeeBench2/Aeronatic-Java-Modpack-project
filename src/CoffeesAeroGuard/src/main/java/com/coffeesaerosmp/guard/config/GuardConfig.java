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
    public static final ModConfigSpec.ConfigValue<String> NETHER_OPENS_AT;
    public static final ModConfigSpec.BooleanValue        TEAM_LEAVE_TRIMS_CLAIMS;
    public static final ModConfigSpec.BooleanValue        PUBLIC_INTERACT_ENABLED;
    public static final ModConfigSpec.BooleanValue        DEBUG_INTERACT_LOGGING;
    public static final ModConfigSpec.IntValue            DRAGON_DAMAGE_DIVISOR;
    public static final ModConfigSpec.BooleanValue        DEBUG_DRAGON_SCALING;
    public static final ModConfigSpec.BooleanValue        END_ARENA_NO_CLAIM;
    public static final ModConfigSpec.IntValue            END_ARENA_RADIUS;
    public static final ModConfigSpec.IntValue            END_ARENA_CENTER_X;
    public static final ModConfigSpec.IntValue            END_ARENA_CENTER_Z;

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
        NETHER_OPENS_AT = b
            .comment("Keep players out of the Nether until this moment (ISO-8601 with offset, e.g.",
                     "\"2026-10-10T18:00:00+05:30\"). Blank = the Nether is open. Same routes and op bypass as",
                     "the End lock; players are told how long is left. Hot-reloadable: edit, save, done.",
                     "An unparseable value keeps the Nether OPEN and logs a warning — a typo must not lock",
                     "players out with no end date.")
            .define("netherOpensAt", "");
        b.pop();

        b.comment("Ender dragon difficulty.").push("enderdragon");
        DRAGON_DAMAGE_DIVISOR = b
            .comment("Divide every point of damage dealt to the ender dragon by this, BEFORE it is",
                     "applied. Effective hit points = the dragon's real max health x this divisor.",
                     "Set to 1 to disable and get an ordinary dragon.",
                     "",
                     "🔴 THE RIGHT VALUE DEPENDS ON WHO IS SETTING THE DRAGON'S REAL MAX HEALTH, and it",
                     "is never this mod. Whatever sets it, effective HP = that health x this divisor.",
                     "",
                     "  * coffees_aero_end datapack LOADED: it writes DragonHealth EDFR.config = 300, so",
                     "    at divisor 1000 a kill costs 300 x 1000 = 300,000 damage. That was the pairing",
                     "    the datapack's own header documents; change one and the fight silently rescales.",
                     "  * datapack DISABLED (the case since 2026-09-27): EDF Remastered still runs, and its",
                     "    stock config/setup_config sets DragonHealth 500 — NOT vanilla's 200, because",
                     "    EDF's dragon_init overwrites max_health from that score on every new dragon.",
                     "    Verified by reading setup_config out of edf-remastered-5.0.2.jar. So effective",
                     "    HP = 500 x divisor, and the divisor of 4 below is what makes that ~2,000.",
                     "",
                     "Do NOT leave a large divisor set with the datapack off. The datapack was also the",
                     "only thing that could draw a NUMBER for the pool — vanilla's dragon bar renders no",
                     "digits at all — so at divisor 1000 players would face 500,000 effective HP behind a",
                     "bar that never visibly moves, which reads as an invincible, bugged boss rather than",
                     "a hard one. At 500 x 4 the vanilla bar drains smoothly and needs no readout.",
                     "",
                     "EDF also derives MadThreshold = DragonHealth / DivisionConstant = 250, so MAD still",
                     "begins at exactly the halfway point without anything on our side arranging it.",
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
            .defineInRange("enderDragonDamageDivisor", 4, 1, 1_000_000);
        DEBUG_DRAGON_SCALING = b
            .comment("Log every scaled dragon hit (raw -> scaled). OFF by default.",
                     "Exists because a scaling hook that silently does not fire is indistinguishable",
                     "from one that works until the dragon dies in 1000 damage instead of 1,000,000.",
                     "Turn it on for one fight to confirm the hook is live, then turn it off.")
            .define("debugEnderDragonScaling", false);
        b.pop();

        b.comment("Team claims.").push("teamclaims");
        TEAM_LEAVE_TRIMS_CLAIMS = b
            .comment("When a player leaves a party team, release the team's NEWEST claims until it is back under its",
                     "new limit (FTB recalculates the limit but never removes chunks). Pair with FTB Chunks",
                     "party_limit_mode = \"sum\" so a team's allowance is its members' allowances added up.")
            .define("teamLeaveTrimsClaims", true);
        b.pop();

        b.comment("The dragon's island — an unclaimable region inside an otherwise claimable End.").push("endarena");
        END_ARENA_NO_CLAIM = b
            .comment("Refuse FTB Chunks claims on the dragon's island while leaving the rest of the End",
                     "claimable. Players may settle anywhere in the End EXCEPT here.",
                     "",
                     "🔑 This exists because FTB Chunks cannot express it. Its own setting,",
                     "claiming.claim_dimension_blacklist, is per-DIMENSION: it closes all of the End or",
                     "none of it. Opening the End for habitation means taking minecraft:the_end OUT of",
                     "that blacklist, and this rule is what then carves the arena back out. If you turn",
                     "this off, remember the blacklist is no longer protecting the fight either.",
                     "",
                     "Why the arena must stay unclaimed: the pillars, crystals, gateways and exit portal",
                     "all sit inside it, so a team holding that ground can lock every other player out of",
                     "the boss. Nobody is exempt, ops included — set the radius to 0 if an admin really",
                     "must claim in there. Hot-reloadable; no restart.")
            .define("endArenaNoClaim", true);
        END_ARENA_RADIUS = b
            .comment("Radius in BLOCKS around the centre below, inside which End chunks cannot be claimed.",
                     "0 disables the rule (same as endArenaNoClaim = false).",
                     "",
                     "256 covers the island itself, all ten obsidian pillars and the gateway ring at",
                     "radius 96, with room to spare for YUNG's Better End Island reshaping the centre.",
                     "Players can still settle on the void platforms just outside it.",
                     "",
                     "NOTE a chunk is refused as soon as ANY part of it touches the circle, not when its",
                     "centre does — otherwise 16-block slivers of the arena would stay claimable at the",
                     "boundary. The effective zone is therefore up to one chunk diagonal (~22 blocks)",
                     "wider than this number at the corners.",
                     "",
                     "For scale: the vanilla central island region runs out to 1024 blocks, beyond which",
                     "the outer End islands begin. A radius of 1024 makes the entire central void",
                     "unclaimable too.")
            .defineInRange("endArenaRadius", 256, 0, 1_000_000);
        END_ARENA_CENTER_X = b
            .comment("Centre of the no-claim zone. The vanilla dragon fight is always built around 0, 0",
                     "— the exit portal, the pillar ring and EDF Remastered's fixed-coordinate set",
                     "pieces are all placed relative to it — so these should only move if you have",
                     "relocated the fight itself.")
            .defineInRange("endArenaCenterX", 0, -30_000_000, 30_000_000);
        END_ARENA_CENTER_Z = b
            .defineInRange("endArenaCenterZ", 0, -30_000_000, 30_000_000);
        b.pop();


        SERVER_SPEC = b.build();
    }
}
