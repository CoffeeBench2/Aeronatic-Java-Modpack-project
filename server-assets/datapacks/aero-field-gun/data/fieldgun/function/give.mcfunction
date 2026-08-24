# Aero Field Gun — hand one out. Operator use: /function fieldgun:give
#
# The item is deliberately a carrot on a stick: it is the only reliably right-clickable vanilla
# item with no behaviour of its own, and vanilla can only detect a right-click through the
# minecraft.used statistic. A stick cannot be detected this way, which is why the flavour lives in
# the display name rather than the base item.
#
# It carries minecraft:custom_data {fieldgun:1b} — that, not the name, is what the tick function
# matches on, so a player may rename it freely without either breaking it or turning an ordinary
# carrot on a stick into artillery.

give @s minecraft:carrot_on_a_stick[minecraft:custom_data={fieldgun:1b},minecraft:custom_name='{"text":"Field Gun","color":"gold","italic":false}',minecraft:lore=['{"text":"Right-click: fire an HE shell","color":"gray","italic":false}','{"text":"Handle with care.","color":"dark_gray","italic":true}'],minecraft:unbreakable={},minecraft:enchantment_glint_override=true] 1
