# Gated on the 9-warden cap by the caller. A second trio past 50%.
# 🔴 These used to spawn 10 blocks under the DRAGON — open air in the End, so they
# dropped into the void and were never seen. They spawn at a player's feet now:
# solid ground, and the darkness lands immediately.
execute as @r[predicate=endfight:end_centre] at @s run function aero_end:warden_at_player
execute if entity @e[type=minecraft:ender_dragon,tag=MAD,limit=1] as @r[predicate=endfight:end_centre] at @s run function aero_end:warden_at_player
