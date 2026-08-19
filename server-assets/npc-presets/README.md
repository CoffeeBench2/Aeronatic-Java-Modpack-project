# `aero_greeter` NPC preset

A cleaned-up version of your exported "Survival" NPC, made reusable.

## Install

Put `aero_greeter.npc.snbt` on the **SERVER**, at:

```
config/easy_npc/preset/humanoid/aero_greeter.npc.snbt
```

⚠️ The path you sent me was the **client** one
(`ModrinthApp/profiles/.../config/easy_npc/preset/humanoid`). That's where *your game* keeps presets.
For an NPC that lives on the server, the file has to be in the **server's** config folder. Same
relative path, different machine.

Then:

```
/easy_npc preset import_new  …
```

Tab-complete the arguments. `import_new` spawns a brand-new NPC from the preset; `import` applies it
to an NPC you already have; `import_with_owner` spawns one and assigns the owner.

## What I changed from your export, and why

| Change | Why |
|---|---|
| `name` → `aero_greeter` | yours was the raw UUID `f02c1570-…`, which is unusable as a name you'd type |
| Added `description` + `category:"Lobby"` | so it's identifiable in a list |
| Added **`PermLevel:0`** to the action | your export had no permission level at all, so it fell through to a default. Explicit is better — see the security note below |
| **Removed `Navigation.Home {X:0,Y:1,Z:11}`** | 🔴 this is the important one. Your export hard-codes the lobby home position, so **every NPC spawned from it would try to walk back to lobby coords 0,1,11** — including ones you place elsewhere |
| **Removed `neoforge:attachments`** | carried `mowziesmobs` and `accessories` runtime state from the source entity. Not configuration, and it makes the preset fragile if either mod is ever removed |
| **Removed `PresetUUID`** | it was the source NPC's own UUID; a new spawn gets its own |

## What I kept

- **Your skin** — `SkinData` is `PLAYER_SKIN` keyed by *UUID*, not a name, so it survives a rename.
- **`VariantType: ALEX`** — the slim model, matching your NPC.
- **The look-at objectives** — `LOOK_AT_PLAYER` / `LOOK_AT_MOB` / `LOOK_AT_RESET`. This is why he
  turns to face people, which is exactly what you want from a greeter.
- **`Cmd:"/spawn"` with the leading slash.** Your export has the slash and EasyNPC's own log
  normalised it to `spawn`, so it clearly accepts either. I said "no slash" earlier — that was
  needlessly strict; leaving it as your working export had it.

## 🔴 The action will still be blocked until you fix `security.cfg`

This preset uses the **Actions** route, which goes through EasyNPC's command executor — the thing
that produced:

```
Blocked execute-as-player command 'spawn' … not allowlisted up to GAMEMASTERS.
```

Add `spawn` to **`executeAsUserCommandAllowList.ALL`** in `config/easy_npc/security.cfg` — the
**ALL** tier, not `GAMEMASTERS`. The error suggests GAMEMASTERS only because *you* are one; ordinary
players are not, and they're who the greeter is for. Requires a **restart** (`/easy_npc reload` only
reloads `npc_targets`, not config).

**Until then**, the tag route still works with no restart and no security config:

```
/tag @e[type=easy_npc:humanoid,limit=1,sort=nearest,distance=..5] add aero_npc_greeter
```

The tag path never touches EasyNPC's command executor at all.

## What I deliberately did NOT put in

**A dialog.** The `DialogDataSet` schema is a nested structure of dialogs, buttons and conditions,
and I'd be guessing at the exact nesting — a malformed block risks the whole preset failing to
import. Build the dialog once in the GUI, then **re-export**, and you'll have a preset with a
verified dialog in it.

That's the right workflow generally: **GUI to author, export to preserve, preset to replicate.**
