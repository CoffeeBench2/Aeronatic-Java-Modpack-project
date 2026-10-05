# Admin Live Player Counts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The lobby server keeps ONE message at the top of the admin status channel up to date every 60 s with Survival and Lobby player counts + names.

**Architecture:** Pure `AdminStatusFormat` builds the webhook JSON (unit-tested). `AdminStatusCard` (lobby role only) runs on its own daemon scheduler: snapshots lobby names on the server thread, reads `SmpLiveness` (which now also keeps the SLP player sample), then PATCHes the saved webhook message; finds/creates it on first run. `SmpLiveness` gains `sampleNames()` + `downSinceMs()`.

**Tech Stack:** Java 21, NeoForge 21.1, `java.net.http.HttpClient`, Gson, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-06-s3-channel-live-counts-mail-handbook-design.md` §2

## Files
- Create `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/lobby/AdminStatusFormat.java` — pure: state → JSON body.
- Create `src/CoffeesAeroAuth/src/test/java/com/coffeesaerosmp/auth/lobby/AdminStatusFormatTest.java`.
- Create `src/CoffeesAeroAuth/src/main/java/com/coffeesaerosmp/auth/lobby/AdminStatusCard.java` — scheduler + Discord HTTP + message-id persistence.
- Modify `lobby/SmpLiveness.java` — keep `players.sample[].name`; expose `sampleNames()`.
- Modify `config/AuthConfig.java` (discord section) — `adminStatusWebhook`, `adminStatusChannelId`, `adminStatusIntervalSeconds`.
- Modify `CoffeesAeroAuth.java` — start after `SmpLiveness.start()`, stop in `onServerStopping`.

## Task 1: Formatter (TDD)
- [ ] Tests: up → title color green, field "🟢 Survival — 12/20" with names; down → "🔴 Survival — DOWN" + `<t:SECONDS:R>`; empty → "nobody online"; sample smaller than count → "+N more"; 200 names → field value ≤ 1024 chars; description has "Updated <t:now:R>"; names with quotes produce valid JSON.
- [ ] Run `gradlew.bat test --tests "*AdminStatusFormatTest*"` → FAIL (class missing).
- [ ] Implement; rerun → PASS. Commit.

## Task 2: SmpLiveness sample names
- [ ] Parse `players.sample` (array of `{name,id}`) into a volatile immutable list; clear on failure. Expose `sampleNames()`.

## Task 3: Config keys
- [ ] Add the three keys to the `discord` section (webhook default "", channel default "", interval 60 in [30, 3600]).

## Task 4: AdminStatusCard
- [ ] `start(MinecraftServer, Path dataDir)`: no-op unless lobby role and webhook set. Daemon single-thread scheduler, first run after 10 s, then every interval.
- [ ] Each tick: lobby names via `server.submit(...).get(2s)`; body = `AdminStatusFormat.build(...)`; if no message id → `find()` then `create()`; `PATCH {webhook}/messages/{id}`; 404 → forget id, create next tick; 429/other → log once per state change, skip.
- [ ] `find()`: if a bot token is configured, `GET /channels/{channelId}/messages?after=0&limit=50` (oldest first) and adopt the first message whose `webhook_id` equals the webhook's id. `create()`: `POST {webhook}?wait=true`, save id.
- [ ] Persist the id to `<dataDir>/admin-status.json`.
- [ ] `stop()` shuts the scheduler down. Wire both into `CoffeesAeroAuth`.

## Task 5: Build + boot test
- [ ] `gradlew.bat build` green (tests included).
- [ ] Lobby test boot with the webhook → card appears and updates; delete the card → recreated; stop SMP → DOWN after the confirm window. Deploy only with the owner.
