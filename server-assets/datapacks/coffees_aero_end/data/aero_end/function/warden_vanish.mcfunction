# Executed AS a spent warden, AT it. Sinks it back out of the fight.
particle sculk_charge_pop ~ ~1 ~ 1 1 1 1 40 force
playsound minecraft:entity.warden_dig master @a[distance=..50] ~ ~ ~ 1 0.7
kill @s
