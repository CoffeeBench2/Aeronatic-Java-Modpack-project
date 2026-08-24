# Aero Field Gun — per-tick dispatch.

# 🔴 THIS LINE IS LOAD-BEARING. A player who has never been given a score in an objective is NOT
# matched by a selector that tests that objective — `scores={fg.cool=..0}` silently excludes anyone
# with no fg.cool score at all, rather than treating them as zero. Without this, a brand-new player
# can never fire, and the failure is completely silent: the item is correct, the click registers,
# and the dispatch line simply never matches. `add … 0` seeds anyone missing without disturbing a
# cooldown already counting down.
scoreboard players add @a fg.cool 0

# Tick down live cooldowns.
scoreboard players remove @a[scores={fg.cool=1..}] fg.cool 1

# Fire for anyone who right-clicked a real field gun and is off cooldown.
#
# The custom_data check is what makes this a *specific* item rather than "any carrot on a stick".
# Matching on the display name would be fragile — names are text components and the comparison is
# formatting-sensitive — whereas custom_data is an exact structural match and survives the player
# renaming the item on an anvil.
execute as @a[scores={fg.fire=1..,fg.cool=..0}] at @s if score #enabled fg.math matches 1 if data entity @s SelectedItem.components."minecraft:custom_data".fieldgun run function fieldgun:fire

# Clear the use counter for everyone who clicked, whether or not they fired. Without this a player
# holding right-click banks up score and unloads a barrage the moment they pick the gun up.
scoreboard players set @a[scores={fg.fire=1..}] fg.fire 0
