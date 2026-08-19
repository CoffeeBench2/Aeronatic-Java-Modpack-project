// priority: 0
//
// ─────────────────────────────────────────────────────────────────────────────
//  Aero lobby greeter — hot-reloadable version
//
//  Right-click a tagged Easy NPC → that player runs /spawn (enter the world).
//
//  WHY THIS EXISTS: the same behaviour is built into CoffeesAeroAuth, but changing
//  it there means building a jar and RESTARTING the server. This file is a KubeJS
//  server script, so edits apply with:
//
//      /kubejs reload server_scripts
//
//  ...no restart. Tweak the messages, the cooldown, the conditions — reload — done.
//
//  📍 INSTALL: put this file at   <server>/kubejs/server_scripts/aero_greeter.js
//     Then run /kubejs reload server_scripts (or restart once, this first time).
//
// ─────────────────────────────────────────────────────────────────────────────
//  🔴 IT USES A DIFFERENT TAG ON PURPOSE
//
//  The mod reacts to   aero_spawn_greeter
//  This script reacts to  aero_npc_greeter
//
//  If you put BOTH tags on one NPC, both systems fire and /spawn may run twice.
//  Pick ONE per NPC. Use this script's tag while you are iterating.
//
// ─────────────────────────────────────────────────────────────────────────────
//  SETUP — stand within 5 blocks of the NPC and run these two lines:
//
//    /tag @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] add aero_npc_greeter
//    /data merge entity @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] {CustomName:'{"text":"Right-click me!","color":"gold","bold":true}',CustomNameVisible:1b,Invulnerable:1b}
//
//  (Use easy_npc:humanoid_slim if you spawned the slim/Alex model.)
//
//  To undo:
//    /tag @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] remove aero_npc_greeter
//
// ─────────────────────────────────────────────────────────────────────────────
//  ⚠️ WHAT THIS SCRIPT CANNOT DO — do not expect these to work here
//
//  1. It cannot make the Easy NPC DIALOG open for normal players in the lobby.
//     The lobby lockdown in CoffeesAeroAuth closes any container menu for non-ops,
//     and Easy NPC's dialog IS a container menu. That is Java, not scriptable.
//     Fixed in auth 1.7.36 — deploy that jar if you want dialog mode.
//
//  2. It cannot tell a genuinely new player from a returning one. That lives in
//     the mod's profile store (startupBonusGiven) and is not exposed to KubeJS.
//     GREET_ONCE below is an approximation: "first time THIS PLAYER used THIS
//     script's greeter", tracked in their own player data. Not the same thing.
// ─────────────────────────────────────────────────────────────────────────────

// ── Config — edit these, then /kubejs reload server_scripts ──────────────────

const GREETER_TAG = 'aero_npc_greeter'

// Message shown when the greeter fires. Set to null for silence.
const GREET_MESSAGE = 'Welcome aboard, pilot!'

// Seconds a player must wait before the greeter will fire again for them.
// Stops a double-click running /spawn twice.
const COOLDOWN_SECONDS = 3

// Command run as the clicking player. Must be on lobbyAllowedCommands in the
// server toml, or the lobby whitelist (auth 1.7.32+) silently refuses it.
// 'spawn' is whitelisted by default.
const GREET_COMMAND = 'spawn'

// If true, the FIRST time a player uses the greeter the interaction is left
// alone so Easy NPC's own dialog can open instead of teleporting them.
// ⚠️ Needs auth 1.7.36+ or the dialog is closed again immediately (see note 1).
const GREET_ONCE = false

// ── Admin-only NPC ───────────────────────────────────────────────────────────
// Tag an NPC with ADMIN_TAG and only ops can use it; it drops them in the
// creative flat building dimension. Everyone else gets a polite refusal.
//
// Requires the AeroAdminFlat datapack (adds aeroadmin:admin_flat).

const ADMIN_TAG = 'aero_npc_admin'

// Minimum vanilla permission level. 2 = /gamemode-tier op, 4 = full owner.
const ADMIN_PERM_LEVEL = 2

// Where the admin NPC sends them. Surface of the flat is y=4 (bedrock/dirt/dirt/grass from y=0).
const ADMIN_DIMENSION = 'aeroadmin:admin_flat'
const ADMIN_X = 0
const ADMIN_Y = 4
const ADMIN_Z = 0

// Put them in creative on arrival. OFF by design — you are ops, so /gamemode is
// one command, and auto-creative has a real failure mode: nothing switches you
// BACK, so an op who builds here and then /spawns home is still in creative,
// one click from dropping stacks into the live economy.
const ADMIN_SET_CREATIVE = false

const ADMIN_DENY_MESSAGE = 'This one is staff only.'

// ── Sealing the dimension ────────────────────────────────────────────────────
// The NPC is only the front door. A datapack CANNOT restrict a dimension —
// vanilla has no permission check — so anyone who reaches aeroadmin:admin_flat
// by any other route (a command, a mod teleport, a waystone, a portal) would
// simply be in there. This ejects non-ops who are inside it, whatever route
// they used.

const SEAL_ADMIN_DIMENSION = true

// How often to sweep, in ticks. 20 = once a second. The tick loop on this server
// is compute-bound, so this is throttled rather than run every tick.
const SEAL_CHECK_INTERVAL = 20

// 🔴 SET THESE to somewhere safe in your overworld before relying on the seal.
// An ejected player is dropped here. The defaults are a guess and could be
// inside terrain.
const EJECT_DIMENSION = 'minecraft:overworld'
const EJECT_X = 0
const EJECT_Y = 100
const EJECT_Z = 0

const EJECT_MESSAGE = 'That area is staff only.'

// ── Implementation ───────────────────────────────────────────────────────────

// Reset on every reload — that is fine, it only guards double-clicks.
const lastUse = {}

ItemEvents.entityInteracted(event => {
    var target = event.target
    if (!target) return

    // Vanilla scoreboard tags on the entity. Works for any entity type, so an
    // armor stand can be tagged too if you ever want a non-NPC greeter.
    var tags = target.tags
    if (!tags) return

    // ── Admin-only NPC ───────────────────────────────────────────────────────
    if (tags.contains(ADMIN_TAG)) {
        if (String(event.hand) !== 'MAIN_HAND') return
        var admin = event.player
        if (!admin || admin.level.isClientSide()) return

        if (!admin.hasPermissions(ADMIN_PERM_LEVEL)) {
            admin.tell(Text.red(ADMIN_DENY_MESSAGE))
            return event.cancel()
        }

        // Run from the SERVER, not the player. Two reasons, both real:
        //  - the auth lobby command whitelist (1.7.32+) only exempts permission
        //    level 4, so a level-2 op standing in the lobby would have /execute
        //    refused if it ran as them. A server-sourced command has no player
        //    entity on the source, so onLobbyCommand skips it entirely.
        //  - it works identically for a level-2 and a level-4 op.
        var srv = admin.server
        if (!srv) return event.cancel()

        // runCommand, not Silent: if the AeroAdminFlat datapack is missing, the
        // dimension does not exist and this fails. Silent would hide that and the
        // NPC would just look broken.
        srv.runCommand(
            'execute in ' + ADMIN_DIMENSION + ' run tp ' + admin.username
            + ' ' + ADMIN_X + ' ' + ADMIN_Y + ' ' + ADMIN_Z)

        if (ADMIN_SET_CREATIVE) {
            srv.runCommandSilent('gamemode creative ' + admin.username)
            admin.tell(Text.yellow('Creative mode on. Switch back BEFORE you return to the live world.'))
        }
        admin.tell(Text.gray('Admin flat world. Use /spawn to go back.'))
        return event.cancel()
    }

    if (!tags.contains(GREETER_TAG)) return

    // NeoForge fires this for the main hand AND the off hand. Without this guard
    // the command would run twice on a single right-click.
    if (String(event.hand) !== 'MAIN_HAND') return

    var player = event.player
    if (!player || player.level.isClientSide()) return

    // Debounce. Date.now() is fine here; this is not tick-critical.
    var now = Date.now()
    var key = String(player.uuid)
    var previous = lastUse[key]
    if (previous && (now - previous) < COOLDOWN_SECONDS * 1000) {
        return event.cancel()
    }

    if (GREET_ONCE) {
        // player.persistentData survives relog and death.
        var data = player.persistentData
        if (!data.getBoolean('aeroGreeted')) {
            data.putBoolean('aeroGreeted', true)
            // Do NOT cancel: letting the interaction through is what hands the
            // click to Easy NPC so its dialog opens.
            return
        }
    }

    lastUse[key] = now

    if (GREET_MESSAGE) {
        player.tell(Text.gold(GREET_MESSAGE))
    }

    // runCommand, not runCommandSilent — the silent variant swallows errors, so a
    // refused command (whitelist, cooldown, combat tag) would look like the
    // greeter simply doing nothing.
    player.runCommand(GREET_COMMAND)

    // Consume the click so Easy NPC does not also open a dialog on top.
    return event.cancel()
})

// ── Dimension seal ───────────────────────────────────────────────────────────
//
// Ejects any non-op found inside the admin dimension, regardless of how they got
// there. This is the part the datapack cannot do.
//
// Throttled with a shared counter rather than checked every tick: PlayerEvents.tick
// fires per player per tick, and comparing a ResourceLocation allocates a string.
// Once a second is plenty — there is nothing to reach in there in under a second.

var sealTick = 0

ServerEvents.tick(event => {
    sealTick++
})

PlayerEvents.tick(event => {
    if (!SEAL_ADMIN_DIMENSION) return
    if (sealTick % SEAL_CHECK_INTERVAL !== 0) return

    var player = event.player
    if (!player || player.level.isClientSide()) return
    if (String(player.level.dimension) !== ADMIN_DIMENSION) return
    if (player.hasPermissions(ADMIN_PERM_LEVEL)) return

    // Native teleport rather than a command: no permission level involved, and it
    // cannot be refused by the lobby command whitelist.
    player.teleportTo(EJECT_DIMENSION, EJECT_X, EJECT_Y, EJECT_Z, 0, 0)
    player.tell(Text.red(EJECT_MESSAGE))
    console.warn('[AeroGreeter] Ejected non-op ' + player.username
        + ' from ' + ADMIN_DIMENSION)
})
