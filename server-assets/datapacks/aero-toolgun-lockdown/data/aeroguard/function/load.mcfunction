# Aero Toolgun Lockdown — setup. Runs on world load and every /reload.

# Counts how many restricted items each sweep confiscated, so we only message a
# player who actually lost something.
scoreboard objectives add tg.found dummy

# Used by status/purge to count holders. Separate from tg.found so a status check can never be
# mistaken for, or interfere with, the confiscation sweep.
scoreboard objectives add tg.count dummy

# Master switch. Disable every sweep without removing the pack:
#     /scoreboard players set #enabled tg.found 0
# Resets to 1 on /reload.
scoreboard players set #enabled tg.found 1

# ⚠️ `replace` matters. Without it, a /reload stacks a second scheduled sweep on
# top of the one already pending, and they double every reload until the server
# is sweeping constantly.
schedule function aeroguard:sweep 2s replace

tellraw @a[tag=tg.debug] {"text":"[Toolgun Lockdown] loaded.","color":"gray"}
