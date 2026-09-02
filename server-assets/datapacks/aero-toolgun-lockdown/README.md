# Aero Toolgun Lockdown

Keeps **Create Aeronautics: Toolgun** items out of survival players' hands.

## Why

The portable-structure **capture/place cycle mints a new ship UUID each time**, which orphans the
AeroClaims claim. The result is a ghost ship the owner can no longer claim — the exact failure logged
as `capturedRootStructureId=… activeRootStructureId='null'`.

## Two halves, both required

| | Handles | Applies with |
|---|---|---|
| **This datapack** | **Possession** — confiscates the items from survival players | `/reload` |
| **`server-assets/kubejs/toolgun_lockdown.js`** | **Recipes** — removes them, including from EMI/JEI | `/kubejs reload server_scripts` |

Neither alone is enough:
- recipes only → a creative-given or `/give`n item still works
- possession only → players keep crafting them and losing the materials

A vanilla datapack has **no true recipe delete** — only override-with-something-impossible, which
leaves the recipe visible in the recipe viewer. That is why recipe removal is KubeJS.

## Install

```
zip → <server>/world/datapacks/aero-toolgun-lockdown.zip
/datapack enable "file/aero-toolgun-lockdown.zip"     # if the server is running
```

⚠️ `/reload` re-reads packs the server already knows but does **not discover new ones**. Either
`/datapack enable` or restart.

## Covered items

All 7, via the item tag `#aeroguard:toolgun_restricted`:

| Item | Craftable? |
|---|---|
| `magnetic_gun` | ✅ recipe removed |
| `portable_structure_container` | ✅ recipe removed |
| `survival_structure_tool` | ✅ recipe removed |
| `creative_magnetic_gun` | ❌ creative-only |
| `structure_tool` | ❌ creative-only |
| `disposable_vehicle_container` | ❌ produced by the toolgun |
| `portable_structure_printer` | ❌ block form |

🔴 **Every entry is `{"id": …, "required": false}` — do not "simplify" them back to plain strings.**
A vanilla tag containing ONE missing reference **fails to load in its entirety**, and the failure is
near-silent: one `Couldn't load tag … as it is missing following references:` line at load, then
`#aeroguard:toolgun_restricted` matches nothing and every `clear` reports success while removing
nothing. `portable_structure_printer` is a BLOCK (`block.…` lang key, no item model in the jar), so
it is exactly the kind of entry that takes the whole tag down. `required: false` makes each entry
skip itself instead. Cost of being wrong about an id: nothing. Cost of a plain string: the entire
lockdown silently does nothing.

## Exemptions

- **Creative** and **Spectator** are skipped — admins keep working.
- Anyone tagged **`tg.exempt`** is skipped:
  ```
  /tag <player> add tg.exempt        # grant
  /tag <player> remove tg.exempt     # revoke
  ```

## Controls

```
/scoreboard players set #enabled tg.found 0     # disable all sweeps, no restart
/scoreboard players set #enabled tg.found 1     # re-enable
```
Resets to 1 on `/reload`.

## Design notes

🔑 **The sweep is scheduled every 2 s, deliberately not on the tick loop.** This server's median tick
was **45.67 ms of a 50 ms budget** — 91% saturated. An inventory scan of every player every tick is
exactly the kind of cost that is invisible in testing and hurts on a full server. 2 s is far faster
than anyone can craft and use one of these.

🔑 **`schedule … replace` is load-bearing.** Without `replace`, every `/reload` stacks another pending
sweep on top of the existing one, doubling each time until the server sweeps constantly.

🔑 **`clear` returns a count**, so one command both confiscates and tells us whether to message the
player. Silent confiscation reads as item loss and generates tickets — the notify function explains
the reason.

## Files

| File | Role |
|---|---|
| `load.mcfunction` | objectives, master switch, arms the sweep |
| `sweep.mcfunction` | re-arms itself, confiscates, dispatches the notice |
| `notify.mcfunction` | explains why to the affected player |
| `tags/item/toolgun_restricted.json` | the 7 items |

## Status

**Built 2026-08-26. NOT yet tested in-game.** Worth a single check before trusting it: hold a
`portable_structure_container` in survival and confirm it disappears within ~2 s with a message.
