---
title: Performance
---

# Performance

This is a 180+ mod pack with Distant Horizons and real airship physics. It asks a lot
of a computer. Most of the fixes are one setting.

---

## RAM: 8 GB, more if you can spare it

| Allocation | Result |
|---|---|
| Under 6 GB | Runs out of memory, stutters badly |
| **8 GB** | **The sweet spot for most machines** |
| 10–12 GB | Fine with **32 GB+** in your PC — smoother with Distant Horizons and big ships |
| More than half your PC's RAM | Crashes with **no crash report** |

> [!warning] The limit is your PC, not the pack
> Minecraft's allocation is only part of what the game uses. Your graphics driver and
> Distant Horizons need memory **outside** it. Give Minecraft so much that nothing is
> left for them and the game dies during loading with **no crash report** — the log
> simply stops mid-line.
>
> If that happens, lower the allocation by 2 GB and try again.

## Potato mode

There's a **Mode** button in **Aero Settings** (the gear tile on the main menu).

**Potato** switches off the heaviest client-side extras — Distant Horizons, Sound
Physics, Subtle Effects, Punchy and the Sounds mod. **Normal** puts them back. You can
flip between them whenever you like; it doesn't touch your world, keybinds or the
server, and you can still join either way.

If the pack is unplayable on your hardware, try this **before** you start disabling
mods by hand — the updater manages the `mods` folder and will put them back.

## Other switches in Aero Settings

The same screen has on/off switches for cosmetic client mods, including **Spatial GUI**
(inventories drawn as a 3D panel in the world). It ships **off**; turn it on there if
you want it.

## Shaders

Shaders ship **off** and are the most expensive thing you can turn on.

> [!warning] Start low with shaders
> Compiling a heavy shader pack needs a large block of memory *outside* the game's
> allocation, at the same moment Distant Horizons wants it. Start from a lower preset
> and work up.

## Distant Horizons

DH is what lets you see terrain far past your render distance — most of the point of
flying. If you're struggling, **lower your render distance before you turn DH off**:
vanilla chunks cost far more than DH's simplified terrain.

## Getting your FPS back, in order

1. **Check your RAM allocation** — 8 GB, or more only if your PC has room
2. **Turn shaders off**, or drop to a lower preset
3. **Lower render distance** to 8–12 and let DH handle the horizon
4. **Switch to Potato mode** in Aero Settings
5. **Close the other things.** A browser with forty tabs competes for the same memory

## High ping right after joining?

The tab list shows everyone's ping as a number. For the first minutes after you join,
the server streams map data and distant terrain to you, and on a slow connection the
number can read high while that catches up, then settle. If it stays high, ask in Discord.

Still bad? Ask in **[Discord](https://discord.gg/AnFUh5vTz6)** with your specs and your
allocation — that's usually enough to spot it.

---

## Related

- [[getting-started]] — setting the allocation in the first place
- [[faq]] — load times and other common questions
