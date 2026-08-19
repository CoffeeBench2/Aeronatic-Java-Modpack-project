# Admins-only creative flat world

Two pieces: a **datapack** that adds the dimension, and an **NPC tag** that only ops can use.

## 1. Install the datapack

Upload `AeroAdminFlat.zip` to the server at:

```
world/datapacks/AeroAdminFlat.zip
```

**Restart the server.** New dimensions cannot be added by `/reload` — they're read when the world
loads. (You need a restart for `security.cfg` anyway, so do both at once.)

Verify after restart:
```
/execute in aeroadmin:admin_flat run tp @s 0 4 0
```
If that works, the dimension is live.

⚠️ The zip was built with **Python `zipfile`**, not `Compress-Archive` — PowerShell writes backslash
paths into zips and Minecraft silently refuses to read the datapack.

## 2. Set up the NPC

Make sure `aero_greeter.js` is the updated copy (re-upload it, then
`/kubejs reload server_scripts`). Then stand by the admin NPC:

```
/tag @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] add aero_npc_admin
```

That's it. Ops get teleported; everyone else gets *"This one is staff only."*

## What the dimension is

- Superflat: bedrock → dirt ×2 → grass, so the surface is **y = 4**
- `min_y: 0`, height 256 — round numbers, no negative-Y awkwardness
- **Permanent noon** (`fixed_time: 6000`) and full ambient light
- **No mob spawning** (`monster_spawn_light_level: 0`)
- No beds, no raids, no phantoms (`natural: false`)

## Gamemode: auto-creative is OFF

`ADMIN_SET_CREATIVE = false`. You're ops — `/gamemode creative` is one command, and auto-switching
had a real failure mode: nothing switched you *back*, so an op who built here and then `/spawn`ed
home was still in creative, one click from dropping stacks into the live economy. Turned off.

## Access control — the dimension is now SEALED, not just the door

**Two layers:**

1. **The NPC** refuses non-ops (`hasPermissions(2)`, checked server-side, unfakeable).
2. **The seal** — `SEAL_ADMIN_DIMENSION` in the script sweeps once a second and ejects any non-op
   found inside `aeroadmin:admin_flat`, **however they got there** — command, mod teleport,
   waystone, anything. This is the part a datapack fundamentally cannot do: vanilla has no
   permission check to attach to a dimension.

🔴 **Set the eject coordinates before you rely on it.** `EJECT_X/Y/Z` default to `0, 100, 0` in the
overworld, which is a guess and could be inside terrain. Put them somewhere safe near your spawn.

The seat of the check is throttled (`SEAL_CHECK_INTERVAL = 20`, once a second) rather than run every
tick — `PlayerEvents.tick` fires per player per tick, and comparing the dimension allocates. There's
nothing reachable in there inside a second.

Ejections are logged: `[AeroGreeter] Ejected non-op <name> from aeroadmin:admin_flat`.

## Loopholes closed

| Hole | Status |
|---|---|
| Non-op walks in via another teleport | **Closed** — the seal ejects them within a second |
| Monsters spawning | **Closed** — `monster_spawn_light_level: 0` |
| Animals spawning | **Closed** — biome is `minecraft:the_void`, which has no spawn list at all |
| Falling out of the world | **Closed** — bedrock floor at y=0, you stand at y=4 |
| Setting a respawn point inside | **Closed** — `bed_works: false`, `respawn_anchor_works: false` |
| Portals linking in | **Closed** — custom dimensions have no natural portal; nether portals only link overworld↔nether |
| Raids / phantoms | **Closed** — `has_raids: false`, `natural: false` |

**Why `the_void` biome:** it's what your own `auth_lobby` uses, and it carries no mob spawn list, so
nothing generates. The sky still looks normal because `effects` is set to `minecraft:overworld` —
same trick your lobby uses. ⚠️ Grass will be a slightly duller green than `plains`, since grass tint
comes from the biome. If you'd rather have proper plains colour and don't mind the odd cow, change
`"biome"` to `"minecraft:plains"` in the dimension JSON and re-zip.

## ⚠️ Still open: world size

A flat dimension can be explored forever, generating chunks and bloating the save. Nothing in the
datapack prevents that. If it matters, set a border once after the restart:

```
/execute in aeroadmin:admin_flat run worldborder set 512
```

I have not verified how per-dimension world borders behave on 1.21.1 with your mod set — test it
rather than assume it stuck.

## Why the teleport runs as the SERVER, not as you

Two reasons, both real:

- The lobby command whitelist (auth 1.7.32+) only exempts permission level **4**. A level-2 op
  standing in the lobby would have `/execute` refused if it ran as them. A server-sourced command has
  no player entity on the source, so `onLobbyCommand` skips it.
- It behaves the same for a level-2 and a level-4 op.

The teleport uses `runCommand` (not `runCommandSilent`) on purpose — if the datapack is missing, the
dimension doesn't exist and the failure shows in console instead of the NPC just looking broken.
