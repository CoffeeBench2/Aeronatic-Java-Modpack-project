# Aero Toolgun Lockdown — is it actually running?  /function aeroguard:status
#
# Every failure mode of this pack is silent: no error, nothing confiscated, no clue why. This
# reports each link separately so a failure names itself.

tellraw @s {"text":"── Toolgun Lockdown ──","color":"gold","bold":true}

# 1. Did load.mcfunction run? If #enabled has no score at all, the pack is not loaded.
execute unless score #enabled tg.found matches -2147483648.. run tellraw @s {"text":"✗ NOT LOADED — load.mcfunction never ran. Check /datapack list.","color":"red"}
execute if score #enabled tg.found matches 1 run tellraw @s {"text":"✓ loaded, sweep ACTIVE (every 2s)","color":"green"}
execute if score #enabled tg.found matches 0 run tellraw @s {"text":"✗ loaded but DISABLED — /scoreboard players set #enabled tg.found 1","color":"red"}

# 2. Who is currently holding restricted items?
#
# 🔑 `clear <target> <item> 0` COUNTS without removing — a maxCount of 0 makes it a pure query.
# That is what lets this be a read-only status check rather than a stealth purge.
execute as @a store result score @s tg.count run clear @s #aeroguard:toolgun_restricted 0

tellraw @s [{"text":"Holding restricted items: ","color":"gray"},{"selector":"@a[scores={tg.count=1..}]","color":"yellow"}]
tellraw @s [{"text":"(exempt: creative, spectator, and anyone tagged tg.exempt)","color":"dark_gray","italic":true}]

scoreboard players reset @a tg.count
