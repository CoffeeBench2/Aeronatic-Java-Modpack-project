# Called from the overridden shulker wave, already gated on the 9-warden cap.
#
# 🔴 These used to spawn at ~-10 under the DRAGON — which in the End is open air, so
# they fell straight into the void and were gone before anyone saw them. They now
# spawn at a random player's feet instead: solid ground, and a target already in
# range so they engage immediately.
#
# NOTE a vanilla warden still digs down and despawns after ~60s with no target at
# all. That is vanilla behaviour and is left alone — if every player leaves the
# arena the wardens bury themselves, which is fine.
execute as @r[predicate=endfight:end_centre] at @s run function aero_end:warden_at_player
