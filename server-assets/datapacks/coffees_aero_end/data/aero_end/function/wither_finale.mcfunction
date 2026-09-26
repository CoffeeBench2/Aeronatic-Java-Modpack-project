# ONE-TIME, at the dragon's last 10% — real Health < 30 of 300, i.e. the final
# 30,000 of the 300,000 pool. The aeroWithers tag on the dragon is the guard, and it
# dies with the dragon, so a respawned dragon gets its own finale.
#
# 🔑 PASSIVE TOWARD THE DRAGON, hostile to players. Joining them to aeroEndBoss
# alongside the dragon is what achieves it: vanilla target goals call
# Entity.isAlliedTo, so an allied wither never picks the dragon as a target. Guard
# also refuses wither damage to the dragon, covering skulls already in flight.
#
# ⚠ Withers charge up invulnerable for ~10s and break blocks as they fight. Five of
# them WILL reshape the arena.
tag @s add aeroWithers
playsound minecraft:entity.wither_spawn master @a[distance=..120] ~ ~ ~ 1 0.6
title @a[predicate=endfight:end_centre] times 10t 50t 20t
title @a[predicate=endfight:end_centre] subtitle {"text":"they are not here for the dragon","color":"gray","italic":true}
title @a[predicate=endfight:end_centre] title {"text":"\u2620 THE LAST TENTH","color":"dark_red","bold":true}
summon minecraft:wither ~14 ~2 ~0 {Tags:["aeroWither"]}
summon minecraft:wither ~-14 ~2 ~0 {Tags:["aeroWither"]}
summon minecraft:wither ~0 ~2 ~14 {Tags:["aeroWither"]}
summon minecraft:wither ~0 ~2 ~-14 {Tags:["aeroWither"]}
summon minecraft:wither ~0 ~10 ~0 {Tags:["aeroWither"]}
team join aeroEndBoss @e[tag=aeroWither]
