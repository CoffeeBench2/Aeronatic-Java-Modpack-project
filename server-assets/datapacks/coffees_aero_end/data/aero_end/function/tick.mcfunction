# Runs after endfight:tick in the same game tick.
execute in minecraft:the_end as @e[type=minecraft:ender_dragon,tag=ticked] run function aero_end:dragon
execute unless entity @e[type=minecraft:ender_dragon,tag=ticked] run bossbar set aero_end:dragon visible false

# ── warden lifetime ─────────────────────────────────────────────────────────
# Wardens are a BLINDING tool, not a damage source: they arrive, the darkness
# lands, they leave. Anything longer and nine 500 HP entities are just a tax on the
# tick loop for the rest of the fight. aeroWardenNew is how this loop knows which
# ones still need their timer started (a summon cannot set a score directly).
execute as @e[tag=aeroWardenNew] run scoreboard players set @s aeroWardenLife 120
execute as @e[tag=aeroWardenNew] run tag @s remove aeroWardenNew
execute as @e[tag=aeroWarden] run scoreboard players remove @s aeroWardenLife 1
execute as @e[tag=aeroWarden,scores={aeroWardenLife=..0}] at @s run function aero_end:warden_vanish
