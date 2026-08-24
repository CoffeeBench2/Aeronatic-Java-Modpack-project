# Aero Field Gun

A right-click artillery piece for the live SMP. Fires a **Create Big Cannons HE shell with an impact
fuze** along the player's line of sight. Pure datapack — no mod, no client install, no restart.

## Install

```
zip → <server>/world/datapacks/aero-field-gun.zip
/reload
```

⚠️ **`/reload` re-reads the contents of packs the server already knows about, but does not discover
new ones.** A zip dropped into a running world usually needs one of:

```
/datapack list available
/datapack enable "file/aero-field-gun.zip"
```

…or a restart, after which the console reports
`Found new data pack file/aero-field-gun.zip, loading it automatically`.

Once the pack is enabled, **editing the zip and running `/reload` is enough** — verified 2026-08-24.
No restart needed for function changes.

## Use

```
/function fieldgun:give        # gives the operator a Field Gun
/function fieldgun:debug       # self-check if it is not firing
```

Right-click to fire. 20-tick (1 s) cooldown per player.

### If it does nothing

Run `fieldgun:debug` — right-click once first, then run it immediately. It checks each link
separately (pack loaded → master switch → held item matched → click registered → cooldown) because
**every failure mode here is silent**: the item looks correct, the click registers, and nothing
happens.

🔴 **The bug that cost the first live test**, kept here because it is invisible and easy to
reintroduce: a player with **no score at all** in an objective is *not* matched by a selector that
tests that objective — `scores={fg.cool=..0}` excluded anyone who had never fired, rather than
treating them as zero. `tick.mcfunction` now opens with `scoreboard players add @a fg.cool 0` to seed
them. Any new objective used in a selector needs the same treatment.

## 🔴 Read this before handing one to anybody

**The live server runs `damageRestriction = "ALL_DAMAGE"`.** An HE shell will **destroy blocks** where
it lands, and nothing in the current stack stops it inside a claim — `CoffeesAeroGuard` covers Create's
configuration packets, not cannon fire, and FTB Chunks never sees the impact.

**A Field Gun in a player's hands is a working grief tool.** Pick one:

| | How | Effect |
|---|---|---|
| **Keep it op-only** | Don't run `fieldgun:give` for non-ops. There is no recipe and no loot table. | Simplest. Nothing changes server-wide. |
| **Disarm the shells** | `damageRestriction = NO_EXPLOSIVE_DAMAGE` in `config/createbigcannons-server.toml` | Blast, sound and particles, **no terrain damage** — server-wide, so it also affects real cannons. |
| **Kill switch** | `/scoreboard players set #enabled fg.math 0` | Disables every Field Gun instantly without removing the pack. Resets to 1 on `/reload`. |

The kill switch exists so a bad afternoon is one command, not a restart.

## Tuning

Everything lives in `load.mcfunction`:

| Score | Default | Meaning |
|---|---|---|
| `#speed fg.math` | `6` | Muzzle velocity in blocks/tick |
| `#cooldown fg.math` | `20` | Ticks between shots, per player |
| `#enabled fg.math` | `1` | Master switch |

Damage is **½mv²**, so `#speed` matters more than mass. Projectile mass is set in `fire.mcfunction`
(`ProjectileMass:20.0f`).

To change the round, edit the summon in `fire.mcfunction`. Options:
`createbigcannons:he_shell`, `ap_shell`, `shrapnel_shell` (all need a `Fuze`), or
`createbigcannons:ap_shot` (**no** `Fuze` — it is not a fuzed round and the tag is ignored).

## How it works, and the two things that make or break it

Both were found by reading CBC 5.11.7 bytecode after a plain `/summon` produced twenty-four rounds
and no impacts whatsoever.

1. **`ProjectileMass` must be set.** It is synched entity data written by the cannon at fire time.
   It *is* NBT-serialised (`putFloat("ProjectileMass", …)`), so `/summon` can set it — but the default
   is 0, and a massless shell carries no kinetic energy.

2. **Velocity must go into `NextMotion`, not just `Motion`.** CBC drives its own kinematics through
   Ritchie's Projectile Lib and reads `NextMotion`. A round given only vanilla `Motion` spawns,
   ignores the aim and drops. This is the single reason `/summon … {Motion:[…]}` looks like it does
   nothing.

The aim vector is measured rather than computed: two `minecraft:marker`s are dropped one block apart
along the line of sight, their positions read into scoreboards at ×1000, and the difference is the
unit look vector. Vanilla offers no way to read facing as a motion vector directly.

## Files

| File | Role |
|---|---|
| `load.mcfunction` | objectives + tunables; runs on load and every `/reload` |
| `tick.mcfunction` | cooldowns, right-click dispatch, use-counter reset |
| `fire.mcfunction` | the shot itself |
| `give.mcfunction` | hands out the item |
| `debug.mcfunction` | self-check when it silently does nothing |

## Status

**Verified working on the Tank Wars test server, 2026-08-24** — same CBC 5.11.7, same NeoForge
21.1.244, same 182-mod set as live. Shell flies along the line of sight and detonates.

**Not yet run on the live SMP.**
