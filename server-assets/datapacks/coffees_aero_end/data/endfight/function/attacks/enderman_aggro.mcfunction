# ── OVERRIDE of endfight:attacks/enderman_aggro — Coffees Aero SMP 1.11.0 ──────
# 3x the enraged-enderman pull (5 -> 15), doubled to 30 once past 50% (MAD).
# ⚠ Re-derive this file whenever EDF is updated.

# Geez i need to cap this because it actually works
execute if entity @s[tag=!MAD] at @s run tag @e[type=minecraft:enderman,limit=15,sort=nearest,tag=!ANGRY] add ANGRY
execute if entity @s[tag=MAD] at @s run tag @e[type=minecraft:enderman,limit=30,sort=nearest,tag=!ANGRY] add ANGRY
execute at @s run tag @r add VICTIM
#execute as @a[tag=VICTIM] run say HELP IM BEING TARGETED
execute as @e[tag=ANGRY] run data modify entity @s AngerTime set value 1200
execute as @e[tag=ANGRY] run data modify entity @s AngryAt set from entity @a[tag=VICTIM,limit=1] UUID
#tag @e[tag=ANGRY] remove ANGRY
tag @e[tag=VICTIM] remove VICTIM
