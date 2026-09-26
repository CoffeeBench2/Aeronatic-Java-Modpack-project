# ══════════════════════════════════════════════════════════════════════════════
#  Executed AS the ender dragon, IN minecraft:the_end, once per tick.
#
#  🔑 THIS FILE DOES NOT SCALE DAMAGE. CoffeesAeroGuard does it in
#  LivingIncomingDamageEvent, BEFORE the damage is applied
#  ([enderdragon] enderDragonDamageDivisor = 1000).
#
#  The first version scaled reactively from here — read Health, work out the drop,
#  write back a smaller value. Exact, and broken: a single hit larger than the
#  dragon's REAL health (capped at 1024 by the attribute) killed it outright, with
#  no next tick left to correct anything. A 2048-damage sword killed the dragon at
#  a bar reading 999,100 on 2026-09-26. Do NOT reintroduce scaling here — with
#  Guard also scaling, every hit would be divided twice.
#
#  So real Health runs 300 -> 0 across exactly 300,000 points of damage, and the
#  bar is Health x 1000 read straight off the entity.
#
#  ⚠ EXACTLY ONE SPACE between command arguments — see load.mcfunction.
# ══════════════════════════════════════════════════════════════════════════════

# ── baseline, once per dragon ────────────────────────────────────────────────
# EDF's dragon_init already sets max_health and Health from DragonHealth (300).
# The attribute write is belt-and-braces for a dragon that predates this pack,
# whose base would still be 500 and would clamp Health on the way in.
# The team join is what makes the finale withers ignore it — see wither_finale.
execute unless entity @s[tag=aeroScaled] run data modify entity @s attributes[{id:"minecraft:generic.max_health"}].base set value 300.0d
execute unless entity @s[tag=aeroScaled] run team join aeroEndBoss @s
execute unless entity @s[tag=aeroScaled] run tag @s add aeroScaled

# ── 3x attack cadence ────────────────────────────────────────────────────────
# EDF resets its timer to 800 (init), 400 (normal) or 200 (MAD); rewrite each reset
# to a third. These only ever match ON a reset tick: after 400 -> 133 the countdown
# runs 133..0 and can never pass back through 400 or 200, so a mid-countdown value
# is never mistaken for a reset. EDF fires its attack at EXACTLY 0, which is why
# this rewrites the reset value rather than draining the timer faster — an extra
# decrement would step over 0 and no attack would ever fire.
execute if entity @s[tag=!MAD] if score @s dragonAttackTimer matches 800 run scoreboard players set @s dragonAttackTimer 266
execute if entity @s[tag=!MAD] if score @s dragonAttackTimer matches 400 run scoreboard players set @s dragonAttackTimer 133
execute if entity @s[tag=MAD] if score @s dragonAttackTimer matches 200 run scoreboard players set @s dragonAttackTimer 66

# ── paced fireball volley ────────────────────────────────────────────────────
# The overridden endfight:attacks/raining_fireballs sets aeroVolley to 40 instead of
# firing. This drips them out one per 4 ticks (0.2s) — roughly 8 seconds of
# sustained incoming fire rather than one instant wall.
execute if score @s aeroVolley matches 1.. run scoreboard players remove @s aeroVolleyWait 1
execute if score @s aeroVolley matches 1.. if score @s aeroVolleyWait matches ..0 at @s run function aero_end:fireball_one

# ── the last tenth: five withers, once ───────────────────────────────────────
# EDF writes `dragonHealth` = floor(Health) every tick, so ..29 is real health below
# 30 of 300 — the final 30,000 of the 300,000 pool.
execute unless entity @s[tag=aeroWithers] if score @s dragonHealth matches ..29 at @s run function aero_end:wither_finale

# ── TEST-ONLY readout, twice per second ─────────────────────────────────────
# 🔴 STRIPPED FOR THE OFFICIAL RELEASE — production ships the vanilla dragon bar
# only. Health x 1000 IS the effective HP remaining, because the divisor is 1000.
# Throttled because the bar's name is a macro: each distinct number is a fresh
# command parse, so doing it every tick would parse 20 commands a second for nothing.
scoreboard players add #uitick aero_end 1
execute if score #uitick aero_end matches 10.. run scoreboard players set #uitick aero_end 0
execute if score #uitick aero_end matches 0 run bossbar set aero_end:dragon visible true
execute if score #uitick aero_end matches 0 run bossbar set aero_end:dragon players @a[predicate=endfight:end_centre]
execute if score #uitick aero_end matches 0 store result bossbar aero_end:dragon value run data get entity @s Health 1000
execute if score #uitick aero_end matches 0 store result storage aero_end:ui hp int 1 run data get entity @s Health 1000
execute if score #uitick aero_end matches 0 run function aero_end:bossbar_name with storage aero_end:ui
