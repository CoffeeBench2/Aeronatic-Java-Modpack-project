# One fireball of the paced volley. Executed AS the dragon, AT the dragon.
# `facing` sets rotation at the player FIRST, then `positioned ^ ^ ^6` steps along
# that rotation so the fireball does not spawn inside the dragon's own hitbox.
# endfight:sch/new_fireball reads the CURRENT rotation for its direction and cleans
# up its own marker and tag on every call.
scoreboard players remove @s aeroVolley 1
scoreboard players set @s aeroVolleyWait 4
execute facing entity @a[sort=nearest,limit=1] eyes positioned ^ ^ ^6 run function endfight:sch/new_fireball
