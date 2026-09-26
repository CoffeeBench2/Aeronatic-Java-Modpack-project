# ── OVERRIDE of endfight:attacks/raining_fireballs — Coffees Aero SMP 1.11.0 ───
# Stock fires exactly ONE fireball. This ARMS a 40-shot volley which aero_end:dragon
# paces out one every 4 ticks (0.2s) — about 8 seconds of continuous incoming fire.
#
# 40 x 4 = 160 ticks. The fireball attack is phase 3 of 4 (of 8 in MAD), so it
# recurs every 4x133 = 532 ticks, or 8x66 = 528 in MAD — still longer than the
# volley, so volleys never overlap themselves.
# ⚠ Re-derive this file whenever EDF is updated.

scoreboard players set @s aeroVolley 40
scoreboard players set @s aeroVolleyWait 0
