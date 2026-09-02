# Told to the player whose items were just confiscated.
# Explains WHY — a silent removal looks like item loss and generates tickets.

tellraw @s [{"text":"[Aero] ","color":"gold","bold":true},{"text":"Toolgun items are disabled in survival.","color":"red"}]
tellraw @s {"text":"The portable-structure capture/place cycle mints a NEW ship UUID each time, which orphans the AeroClaims claim and leaves unclaimable ghost ships behind. Ask an admin if you need a structure moved.","color":"gray"}
