# Aero Toolgun Lockdown — one-shot purge.  /function aeroguard:purge
#
# Strips the restricted toolgun items from EVERY online player, including creative, spectator and
# tg.exempt. Use this once after deploying the pack to clear items already in circulation; the 2s
# sweep then keeps things clean from that point on.
#
# 🔑 ONLY the items in #aeroguard:toolgun_restricted are touched. `clear` with an item predicate
# removes nothing else — inventories are otherwise untouched.
#
# ⚠️ ONLINE PLAYERS ONLY. A datapack cannot reach an offline player's inventory. Anyone logged out
# keeps their items until they next join, at which point the ordinary 2s sweep takes them (unless
# they are creative/spectator/tg.exempt, which the sweep skips but this purge does not).

execute as @a store result score @s tg.count run clear @s #aeroguard:toolgun_restricted

tellraw @s {"text":"── Toolgun purge ──","color":"gold","bold":true}
tellraw @s [{"text":"Items taken from: ","color":"gray"},{"selector":"@a[scores={tg.count=1..}]","color":"yellow"}]
execute unless entity @a[scores={tg.count=1..}] run tellraw @s {"text":"Nobody was holding any. Nothing to do.","color":"green"}

# Tell the affected players why, so it does not read as item loss.
execute as @a[scores={tg.count=1..}] run function aeroguard:notify

scoreboard players reset @a tg.count
