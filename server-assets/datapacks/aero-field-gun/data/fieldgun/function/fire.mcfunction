# Aero Field Gun — launch one shell along the firer's look vector.
# Called "as <player> at <player>". Never call this directly.

# ---------------------------------------------------------------- look vector
# Vanilla has no way to read a player's facing as a motion vector, so we measure it: drop two
# markers one block apart along the line of sight and subtract. Markers are used because they do
# not tick, render, collide or get saved as loaded entities.
execute anchored eyes run summon minecraft:marker ^ ^ ^0 {Tags:["fg.a"]}
execute anchored eyes run summon minecraft:marker ^ ^ ^1 {Tags:["fg.b"]}

# Scores are integers, so read the positions multiplied by 1000. A unit vector therefore lands in
# ±1000 and we keep three decimal places of aim precision — far finer than the eye can hold.
execute store result score #ax fg.math run data get entity @e[tag=fg.a,limit=1,sort=nearest] Pos[0] 1000
execute store result score #ay fg.math run data get entity @e[tag=fg.a,limit=1,sort=nearest] Pos[1] 1000
execute store result score #az fg.math run data get entity @e[tag=fg.a,limit=1,sort=nearest] Pos[2] 1000
execute store result score #bx fg.math run data get entity @e[tag=fg.b,limit=1,sort=nearest] Pos[0] 1000
execute store result score #by fg.math run data get entity @e[tag=fg.b,limit=1,sort=nearest] Pos[1] 1000
execute store result score #bz fg.math run data get entity @e[tag=fg.b,limit=1,sort=nearest] Pos[2] 1000

# B - A is the unit look vector x1000.
scoreboard players operation #bx fg.math -= #ax fg.math
scoreboard players operation #by fg.math -= #ay fg.math
scoreboard players operation #bz fg.math -= #az fg.math

# Scale to muzzle velocity. Still x1000 at this point; the 0.001 on the stores below undoes it.
scoreboard players operation #bx fg.math *= #speed fg.math
scoreboard players operation #by fg.math *= #speed fg.math
scoreboard players operation #bz fg.math *= #speed fg.math

kill @e[tag=fg.a,limit=1,sort=nearest]
kill @e[tag=fg.b,limit=1,sort=nearest]

# ---------------------------------------------------------------- the shell
# Spawned 2 blocks ahead of the eyes so it does not immediately clip the firer.
#
# 🔑 ProjectileMass MUST be set. It is synched entity data that a real cannon writes at fire time,
# and a shell with mass 0 carries no kinetic energy — it will fly and hit nothing of consequence.
# 🔑 Fuze MUST be set on any fuzed round. An HE shell without a fuze is inert and simply never
# detonates, which in game is indistinguishable from a broken datapack.
execute anchored eyes run summon createbigcannons:he_shell ^ ^ ^2 {Tags:["fg.shell"],ProjectileMass:20.0f,Fuze:{id:"createbigcannons:impact_fuze",count:1},Motion:[0.0d,0.0d,0.0d],NextMotion:[0.0d,0.0d,0.0d]}

# 🔑 The velocity has to go into BOTH fields. Create Big Cannons drives its own kinematics through
# Ritchie's Projectile Lib and reads NextMotion, not vanilla Motion — setting Motion alone produces
# a round that spawns, ignores the aim and drops straight to the ground. This is exactly why a
# plain /summon with Motion appears to do nothing at all.
execute store result entity @e[tag=fg.shell,limit=1,sort=nearest] Motion[0] double 0.001 run scoreboard players get #bx fg.math
execute store result entity @e[tag=fg.shell,limit=1,sort=nearest] Motion[1] double 0.001 run scoreboard players get #by fg.math
execute store result entity @e[tag=fg.shell,limit=1,sort=nearest] Motion[2] double 0.001 run scoreboard players get #bz fg.math
execute store result entity @e[tag=fg.shell,limit=1,sort=nearest] NextMotion[0] double 0.001 run scoreboard players get #bx fg.math
execute store result entity @e[tag=fg.shell,limit=1,sort=nearest] NextMotion[1] double 0.001 run scoreboard players get #by fg.math
execute store result entity @e[tag=fg.shell,limit=1,sort=nearest] NextMotion[2] double 0.001 run scoreboard players get #bz fg.math

tag @e[tag=fg.shell,limit=1,sort=nearest] remove fg.shell

# ---------------------------------------------------------------- feedback
execute anchored eyes run particle minecraft:large_smoke ^ ^ ^2 0.2 0.2 0.2 0.02 12 normal
execute anchored eyes run particle minecraft:flame ^ ^ ^2 0.1 0.1 0.1 0.05 8 normal
playsound minecraft:entity.generic.explode master @a ~ ~ ~ 1.6 1.7

scoreboard players operation @s fg.cool = #cooldown fg.math
