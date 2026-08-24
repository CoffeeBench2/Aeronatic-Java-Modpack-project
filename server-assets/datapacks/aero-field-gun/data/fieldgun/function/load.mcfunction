# Aero Field Gun — setup. Runs on world load and every /reload.

# Right-click detection. Vanilla can only see a right-click through a "used" statistic, and
# carrot_on_a_stick is the standard item for it: it is right-clickable in mid-air and, unlike a
# bow or a food, has no vanilla behaviour to fight with. A plain stick CANNOT be detected this way.
scoreboard objectives add fg.fire minecraft.used:minecraft.carrot_on_a_stick

# Scratch space for the look-vector maths and per-player cooldowns.
scoreboard objectives add fg.math dummy
scoreboard objectives add fg.cool dummy

# Muzzle velocity in blocks/tick x1. Damage is ½mv², so this and fg.mass set how hard it hits.
scoreboard players set #speed fg.math 6

# Ticks between shots, per player.
scoreboard players set #cooldown fg.math 20

# 🔴 MASTER SWITCH. Set to 0 to disable every field gun on the server without removing the pack:
#     /scoreboard players set #enabled fg.math 0
# Survives until the next /reload, which resets it to 1.
scoreboard players set #enabled fg.math 1

scoreboard players reset * fg.fire

tellraw @a[tag=fg.debug] {"text":"[Field Gun] datapack loaded.","color":"gray"}
