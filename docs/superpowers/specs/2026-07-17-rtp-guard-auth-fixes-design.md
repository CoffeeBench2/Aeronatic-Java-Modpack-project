# 2026-07-17 — RTP Guard + Auth Fixes + Server Optimization (design)

Approved by user 2026-07-17 (chat). Targets CoffeesAeroAuth **1.6.26** + Apex config deploy.

## Problems (from 1.8.0 live logs, 17 Jul)

1. **/rtp freezes the server.** FTB Essentials `/rtp` (cooldown 600s, max_distance 25000) generates
   destination chunks synchronously on the server thread → 27–38s stalls (756 ticks behind at 14:33),
   mass disconnect of all players.
2. **Premium resolved as OFFLINE on reconnect.** Gate cookie is single-use (nonce) + short expiry;
   after a freeze-kick, clients reconnect directly to Apex re-presenting the spent cookie →
   `Cookie REJECTED … treating as OFFLINE` → premium player lands in the offline lobby flow.
3. **Discord "N pilots aboard" count sometimes stale.** DiscordGateway ignores heartbeat ACKs
   (op-11) — a silently-dead websocket is never detected; `connected` stays true, presence freezes.
4. **Tornado overload.** Weather2 at stock config: 20 storms/player/layer, F0 70%…F5 10%,
   `aimAtPlayerOnSpawn=true`, block-grab physics on.
5. **JVM:** `-Xms10G -Xmx10G` on a 12GB box leaves ~2GB off-heap → native OOM/swap risk
   (same failure as the 07-15 test-server crash). Flags otherwise correct Aikar.

## Design

### 1. AeroRtp — our own /rtp (FTBE rtp disabled via ftbessentials.snbt)
- `commands/RtpCommand.java`. One active request per player.
- **Cooldown 24h** per player (config `rtpCooldownHours`, default 24; ops exempt), persisted to
  `coffeesaeroauth/rtp_cooldowns.json` (survives restarts/relogs; written via AsyncIo).
- Overworld only. Target: random ring **1500–10000** blocks from world spawn (configurable);
  up to 60 biome samples via the chunk generator's BiomeSource (no chunk gen) to skip
  ocean/river targets.
- **Async pregen:** request the 5×5 chunk area around the target via
  `ServerChunkCache.getChunkFuture(FULL)` — c2me generates off-thread; no server-thread stall.
- Player waits in place, **minimum 10s** (config `rtpMinWaitSeconds`), action-bar progress:
  "✈ Charting course… N%" → countdown → teleport **only when all chunks are ready** AND min wait
  elapsed. Landing = heightmap top of a dry column (spiral search in the generated area);
  all-water landing → one automatic re-chart, then abort with cooldown refund.
- Timeout 90s (config) → abort + refund. Logout cancels. `rtp` already in CombatGuard's
  blocked-while-tagged list.

### 2. Premium reconnect grace
- `auth/PremiumReconnectGrace.java` (in-memory): on every `Cookie OK … PREMIUM`, record
  username(lowercase) → (mojang UUID, IP, expiry). Touched (now + `premiumReconnectGraceMinutes`,
  default 10) on logout; refreshed while online.
- In `handleAuthCookie`: cookie missing/rejected → if grace entry matches name AND **same IP**
  AND profile accountType == PREMIUM → resolve PREMIUM (+ SkinsHook with stored UUID) and log
  `[Gate] Reconnect grace`. Different IP / expired / no entry → OFFLINE as before.
- Cookie stays single-use; no crypto change. Config 0 disables.

### 3. Gateway zombie detection + presence self-heal
- Track `lastAckAt` (op-11). Before each heartbeat: no ACK for >2 intervals → warn + force
  reconnect (per Discord spec).
- `flushPresence`: re-send unchanged presence every 5 min so a re-identified session or missed
  frame self-corrects.

### 4. Weather2 tuning (config deploy → Apex `config/Weather2/`)
- Storm.toml: `Storm_MaxPerPlayerPerLayer` 20→4; tornado/cyclone chance ladder halved
  (F0/C0 70→35, F1/C1 50→25, F2/C2 40→18, F3/C3 30→12, F4/C4 20→8, F5/C5 10→3);
  deadly-storm odds 30→60 and min time between 72000→144000 ticks (land-based 1200→2400 /
  240000→480000).
- Tornado.toml: `aimAtPlayerOnSpawn=false`, `maxFlyingEntityBlocks` 200→50,
  `maxBlocksGrabbedPerTick` 5→3.

### 5. JVM (user applies in panel)
`-Xms8G -Xmx8G`, all other flags unchanged. GC was never the freeze cause; this prevents
native OOM/swap on the 12GB box.

### 6. GTP waystones-only (already in repo, ships with next version bump)
`grand_teleport.properties`: `externalTeleportTransitionsEnabled=false`,
`warpPlateTransitionsEnabled=true` (commit 3f3cd16). Updater is version-gated — reaches players
on the next pack bump; include in deploy checklist.

## Out of scope (tracked separately)
Ghost-ship spam root cause (aeroclaims re-detection) — #1 open bug, needs the source machine found.
