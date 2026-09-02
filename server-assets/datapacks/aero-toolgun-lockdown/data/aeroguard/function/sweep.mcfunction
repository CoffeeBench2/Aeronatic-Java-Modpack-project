# Aero Toolgun Lockdown — confiscation sweep. Self-rescheduling every 2s.
#
# 🔑 Deliberately NOT on the tick loop. This server's median tick was 45.67ms of a
# 50ms budget — 91% saturated — so an inventory scan of every player every tick is
# exactly the kind of cost that is invisible in testing and hurts on a full server.
# Once every 2 seconds is far faster than anyone can craft and use one of these.

# Re-arm FIRST, in a form that cannot be skipped by a failure below. `replace`
# guarantees exactly one pending sweep no matter how many times this runs.
schedule function aeroguard:sweep 2s replace

execute if score #enabled tg.found matches 0 run return 0

# `clear` returns the number of items removed, so one command both confiscates and
# tells us whether to say anything. Exempt: creative, spectator, and anyone tagged
# tg.exempt (admins who legitimately need the toolgun).
execute as @a[gamemode=!creative,gamemode=!spectator,tag=!tg.exempt] store result score @s tg.found run clear @s #aeroguard:toolgun_restricted

# Only message players who actually lost something. Silent confiscation reads as a
# bug and generates tickets.
execute as @a[scores={tg.found=1..}] run function aeroguard:notify

scoreboard players reset @a tg.found
