# Aero Field Gun — self-check. /function fieldgun:debug
#
# Exists because every way this can fail is silent: the item looks right, the click registers, and
# nothing happens. This reports each link in the chain separately so a failure names itself instead
# of having to be guessed at from the outside.

tellraw @s {"text":"── Field Gun diagnostics ──","color":"gold","bold":true}

# 1. Is the pack even running its tick loop? If #enabled is missing, load.mcfunction never ran.
execute unless score #enabled fg.math matches -2147483648.. run tellraw @s {"text":"✗ load.mcfunction has NOT run — the pack is not loaded, or #load did not fire.","color":"red"}
execute if score #enabled fg.math matches 1 run tellraw @s {"text":"✓ pack loaded, master switch ON","color":"green"}
execute if score #enabled fg.math matches 0 run tellraw @s {"text":"✗ master switch is OFF — /scoreboard players set #enabled fg.math 1","color":"red"}

# 2. Is the held item actually a field gun?
execute if data entity @s SelectedItem.components."minecraft:custom_data".fieldgun run tellraw @s {"text":"✓ holding a Field Gun (custom_data matched)","color":"green"}
execute unless data entity @s SelectedItem.components."minecraft:custom_data".fieldgun run tellraw @s {"text":"✗ NOT holding a Field Gun. Select it in your hotbar, then run this again. If you have it and this still fails, the give component syntax is wrong.","color":"red"}

# 3. Did the last right-click register? Right-click ONCE, then run this within a tick or two.
execute if score @s fg.fire matches 1.. run tellraw @s {"text":"✓ right-click detected (fg.fire is set)","color":"green"}
execute if score @s fg.fire matches ..0 run tellraw @s {"text":"• fg.fire is 0 — either you have not clicked since the last tick, or the carrot-on-a-stick 'used' statistic is not incrementing on this server.","color":"yellow"}
execute unless score @s fg.fire matches -2147483648.. run tellraw @s {"text":"✗ you have NO fg.fire score — the statistic objective is not tracking you at all.","color":"red"}

# 4. Cooldown state — the thing that silently blocked everything before.
execute unless score @s fg.cool matches -2147483648.. run tellraw @s {"text":"✗ no fg.cool score — you would be excluded from the dispatch selector.","color":"red"}
execute if score @s fg.cool matches 1.. run tellraw @s {"text":"• cooling down","color":"yellow"}
execute if score @s fg.cool matches ..0 run tellraw @s {"text":"✓ off cooldown","color":"green"}

tellraw @s {"text":"Tip: right-click once, then run this immediately.","color":"gray","italic":true}
