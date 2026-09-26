# ══════════════════════════════════════════════════════════════════════════════
#  Coffees Aero SMP — End release 1.11.0
#  Loads AFTER endfight, so everything set here wins over EDF's setup_config.
#
#  ⚠ EXACTLY ONE SPACE between command arguments. Brigadier does not skip runs of
#  whitespace; column-aligning arguments is a parse error, not a style choice.
#
#  🔑 PAIRED WITH CoffeesAeroGuard. Guard divides all damage dealt to the dragon by
#  [enderdragon] enderDragonDamageDivisor (1000) BEFORE it is applied. With real max
#  health at 300 a kill costs 300 x 1000 = 300,000 points of damage. Change one
#  without the other and the whole fight silently rescales.
#
#  Why 300 and not 1000: at 1,000,000 a CBC shell body hit removes ~88 of the pool,
#  i.e. ~11,400 shells. Owner's call 2026-09-26. 300,000 is still 1500x vanilla.
# ══════════════════════════════════════════════════════════════════════════════

scoreboard objectives add aero_end dummy
scoreboard objectives add aeroVolley dummy
scoreboard objectives add aeroVolleyWait dummy
scoreboard objectives add aeroWardenLife dummy

# ── boss team ────────────────────────────────────────────────────────────────
# The finale withers must be PASSIVE toward the dragon and hostile to players.
# One team with friendlyFire off is what does it: vanilla target goals call
# Entity.isAlliedTo, so an allied wither never selects the dragon as a target at
# all. Guard additionally refuses wither damage to the dragon, which covers a skull
# already in flight — a targeting rule cannot.
team add aeroEndBoss
team modify aeroEndBoss friendlyFire false

scoreboard players set #uitick aero_end 0

# ── EDF config ───────────────────────────────────────────────────────────────
# Must stay under the 1024 attribute cap. MadThreshold is DragonHealth/2 = 150, so
# MAD begins at exactly 50% — 150,000 points of damage dealt.
scoreboard players set DragonHealth EDFR.config 300
scoreboard players set DivisionConstant EDFR.config 2
scoreboard players operation MadThreshold EDFR.config = DragonHealth EDFR.config
scoreboard players operation MadThreshold EDFR.config /= DivisionConstant EDFR.config

# ── TEST-ONLY readout ────────────────────────────────────────────────────────
# 🔴 STRIPPED FOR THE OFFICIAL RELEASE — production ships the vanilla dragon bar
# only (owner's call). This block and the readout block at the end of
# dragon.mcfunction are the only two places the custom bar exists.
bossbar add aero_end:dragon {"text":"The Ender Dragon"}
bossbar set aero_end:dragon max 300000
bossbar set aero_end:dragon color purple
bossbar set aero_end:dragon style notched_10
bossbar set aero_end:dragon visible false
