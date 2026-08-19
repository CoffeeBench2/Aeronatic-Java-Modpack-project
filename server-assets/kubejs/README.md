# KubeJS lobby greeter — reload instead of restart

`aero_greeter.js` puts the NPC greeter behaviour into a **KubeJS server script**, so you can change
it and apply it with `/kubejs reload server_scripts` — no jar build, no server restart.

## Install

1. Upload `aero_greeter.js` to `<server>/kubejs/server_scripts/`
2. `/kubejs reload server_scripts`

(First time only, a restart is safest if KubeJS has never loaded a server script before.)

## Set up an NPC

Stand within 5 blocks of it:

```
/tag @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] add aero_npc_greeter
```

Give it floating text and make it invulnerable:

```
/data merge entity @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] {CustomName:'{"text":"Right-click me!","color":"gold","bold":true}',CustomNameVisible:1b,Invulnerable:1b}
```

Use `easy_npc:humanoid_slim` if you spawned the slim (Alex) model.

Right-clicking it now runs `/spawn` for that player.

## 🔴 Two tags — don't mix them

| Tag | Handled by | Change requires |
|---|---|---|
| `aero_spawn_greeter` | the mod (`CoffeesAeroAuth`) | jar build + restart |
| `aero_npc_greeter` | **this script** | `/kubejs reload server_scripts` |

**Putting both on one NPC makes both fire** and `/spawn` may run twice. Pick one per NPC. Use the
script's tag while you're iterating on behaviour.

## What you can tweak and reload

Everything in the config block at the top: the tag, the greeting message, the cooldown, the command
it runs, and `GREET_ONCE`.

## ⚠️ Two things a script genuinely cannot do

**1. It cannot make the Easy NPC *dialog* open for normal players in the lobby.** The lobby lockdown
in `PlayerRestrictEvents.onLivingTick` closes any container menu for non-ops, and Easy NPC's dialog
is a container menu (`EasyNPCMenu`). That's Java. **Deploy auth 1.7.36** if you want dialog mode —
it exempts Easy NPC menus.

**2. It cannot tell a genuinely new player from a returning one.** That's `startupBonusGiven` in the
mod's profile store and isn't exposed to KubeJS. `GREET_ONCE` approximates it with "first time this
player used this greeter", stored in their player data — useful, but not the same thing.

## Verified against your actual jars

These were checked in `kubejs-neoforge-2101.7.2-build.368.jar`, not assumed:

- `ItemEvents.entityInteracted` wraps NeoForge's **`PlayerInteractEvent.EntityInteract`** — the same
  event the mod uses.
- It **does** fire with an empty hand. The handler keys on the held item (`minecraft:air` when
  empty), and a listener registered with no item target makes `hasListeners(anyTarget)` return true.
- `event.cancel()` reaches `EventResult.applyCancel(ICancellableEvent)`, so the script really can
  consume the click.
- `player.runCommand(...)`, `player.tell(...)` exist as `kjs$runCommand` / `kjs$tell`.

There is **no** general entity-interaction event in `PlayerEvents` or `EntityEvents` in this KubeJS
version — `ItemEvents.entityInteracted` is the only route, which is why the script hangs off an
item event rather than a player one.

## Gotchas baked in

- **Both hands fire.** NeoForge raises `EntityInteract` for main hand *and* off hand; without the
  `MAIN_HAND` guard the command runs twice per click.
- **`runCommandSilent` swallows errors**, so the script uses `runCommand` — otherwise a command
  refused by the lobby whitelist would look like the greeter just doing nothing.
- **`GREET_COMMAND` must be on `lobbyAllowedCommands`** (auth 1.7.32+) or the lobby silently refuses
  it. `spawn` is on the default list; `/warp`, `/discord` etc. are not.
