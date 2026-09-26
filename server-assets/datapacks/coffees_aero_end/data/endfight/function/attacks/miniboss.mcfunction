# ── OVERRIDE of endfight:attacks/miniboss — Coffees Aero SMP 1.11.0 ───────────
# 3x the Warrior of Years Past: 100 -> 300 HP. Still ONE at a time — EDF gates on
# `unless entity @e[tag=miniboss]` and drives a single bossbar off that one entity,
# so spawning three would fight its own bossbar. Tripled toughness instead of count.
# The miniboss bossbar reads max from the attribute, so it rescales on its own.
# ⚠ Re-derive this file whenever EDF is updated.

##
 # miniboss.mcfunction
 # attacks
 #
 # Created by .
##

execute at @e[tag=monumentMarker,limit=1] unless entity @s[tag=spawnedMiniboss] unless entity @e[tag=miniboss] run summon skeleton ~ ~ ~ {CustomNameVisible:1b,Health:300f,Tags:["miniboss"],CustomName:'{"text":"Warrior of Years Past","color":"red","bold":false,"italic":false,"underlined":false}',HandItems:[{id:"minecraft:bow",Count:1b,tag:{display:{Name:'{"text":"The Bow of Old","color":"dark_purple","italic":false}'},Enchantments:[{id:"minecraft:unbreaking",lvl:5s},{id:"minecraft:power",lvl:3s},{id:"minecraft:punch",lvl:3s},{id:"minecraft:flame",lvl:1s},{id:"minecraft:infinity",lvl:1s}]}},{}],ArmorItems:[{id:"minecraft:leather_boots",Count:1b,tag:{display:{color:6911}}},{id:"minecraft:golden_leggings",Count:1b,tag:{Enchantments:[{id:"minecraft:unbreaking",lvl:10s},{id:"minecraft:swift_sneak",lvl:1s}]}},{id:"minecraft:diamond_chestplate",Count:1b,tag:{Enchantments:[{id:"minecraft:protection",lvl:4s},{id:"minecraft:fire_protection",lvl:4s},{id:"minecraft:blast_protection",lvl:4s}]}},{id:"minecraft:iron_helmet",Count:1b}],attributes:[{id:"generic.max_health",base:300},{id:"generic.follow_range",base:100},{id:"generic.movement_speed",base:0.5}]}
execute unless entity @s[tag=spawnedMiniboss] run tag @s add spawnedMiniboss
