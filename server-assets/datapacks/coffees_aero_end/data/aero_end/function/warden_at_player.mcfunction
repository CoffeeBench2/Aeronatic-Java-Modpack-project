# Executed AT a player in the arena. Three wardens; they vanish after 6s — see
# aero_end:tick. aeroWardenNew is how that loop knows to start their timer.
particle sculk_soul ~ ~ ~ 3 1 3 1 80 force
playsound minecraft:entity.warden_emerge master @a[distance=..60] ~ ~ ~ 1 0.8
summon minecraft:warden ~5 ~ ~3 {PersistenceRequired:1b,Tags:["aeroWarden","aeroWardenNew"],CustomNameVisible:0b,CustomName:'{"text":"Warden of the Void","color":"dark_aqua","italic":false}'}
summon minecraft:warden ~-5 ~ ~3 {PersistenceRequired:1b,Tags:["aeroWarden","aeroWardenNew"],CustomNameVisible:0b,CustomName:'{"text":"Warden of the Void","color":"dark_aqua","italic":false}'}
summon minecraft:warden ~0 ~ ~-6 {PersistenceRequired:1b,Tags:["aeroWarden","aeroWardenNew"],CustomNameVisible:0b,CustomName:'{"text":"Warden of the Void","color":"dark_aqua","italic":false}'}
