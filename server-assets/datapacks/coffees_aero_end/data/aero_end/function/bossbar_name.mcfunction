# Macro function — MUST be called as `function aero_end:bossbar_name with storage aero_end:ui`
# where that storage holds an int `hp`. Vanilla's dragon bossbar renders no numeric
# value, so this is the only place the 1,000,000 is actually legible to a player.
$bossbar set aero_end:dragon name ["",{"text":"❖ The Ender Dragon  ","color":"light_purple","bold":true},{"text":"$(hp)","color":"white"},{"text":" / 300000","color":"gray"}]
