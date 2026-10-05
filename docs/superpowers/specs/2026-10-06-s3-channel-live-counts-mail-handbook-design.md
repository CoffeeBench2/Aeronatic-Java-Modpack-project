# Season 3 update channel, admin live counts, mail remake, admin handbook — design

Date: 2026-10-06 · Owner-approved in session (option A for the channel; mail = players + staff,
chat-prompt + parcel compose; handbook = PDF, not an in-game command).

Four independent pieces, built and shipped in this order. Each gets its own plan and its own version bump.

---

## 1. Season 3 self-updating channel (AeroCore + scripts)

**Goal.** S3 Modrinth instances (3.0.0 and later) update themselves from an S3-only channel. S2 instances
never receive S3 content; they are told once to install the new Modrinth version. Updates are rare and are
mostly the Core.

**Facts this rests on.**
- Every deployed S2 Core polls `main/version.json` and installs from `main/pack.toml` (`AeroConfig`
  defaults `versionCheckUrl` / `packTomlUrl`). Whatever sits at those two paths is auto-installed into S2.
- The S3 Modrinth profile currently contains NO Core.
- `config/coffeesaerosmp_core-client.toml` is in the S2 packwiz index, so an S2 update can change it.
- `manualUpdateOnly=true` routes an outdated client to `StoreUpdateScreen` and never runs the downloader.

**Design.**
- New folder `s3/` on `main`: `s3/version.json`, `s3/pack.toml`, `s3/index.toml`, `s3/mods/*.pw.toml`,
  `s3/overrides/…`, generated from the S3 Modrinth profile plus the FULL Core (updater compiled in).
  Root `.packwizignore` excludes `s3/`.
- S3 Core client config ships `packVersion = "3.0.0"`, `versionCheckUrl` and `packTomlUrl` pointing at the
  `s3/` raw URLs, `manualUpdateOnly = false`, `modrinthUrl` = the existing Modrinth project.
- The S3 `.mrpack` uploaded to the existing Modrinth project as 3.0.0 bundles that Core + config.
- A later 3.x.x = push to `s3/` only (usually just the Core metafile + `s3/version.json`).
- **S2 retirement**, two steps on the ROOT channel, after which the root files are frozen:
  1. Release S2 **1.11.5**: only change = S2 client config `manualUpdateOnly = true` + store-screen text
     "Season 3 is out — install the new version from Modrinth". No mod changes.
  2. Root `version.json` → `"version": "3.0.0"`, `url` = Modrinth project, notes = the S3 message.
  An S2 client on 1.11.5 sees the store screen. An S2 client still on ≤1.11.4 pulls 1.11.5 (root
  `pack.toml` is still 1.11.5), then lands on the same screen. No loop: `manualUpdateOnly` stops the
  downloader.
- CurseForge S3 keeps the `-cf` Core (no updater); the CF app is the updater there.

**Guards (build script refuses to build).** `s3/` present in the root index; S3 config pointing at root
URLs; the Core in `s3/` being a `-cf` build (count the 6 `version.VersionCheck` methods, per
`build_cf_discord.py` `UPDATER_CLASSES`).

**Test.** Fresh Modrinth instance from the S3 mrpack + a dummy Core bump pushed to `s3/` → it updates.
An S2 instance at 1.11.4 → pulls 1.11.5 → shows the S3 store screen, downloads no S3 content.

---

## 2. Admin live player counts (CoffeesAeroAuth, LOBBY role)

**Goal.** One message at the top of the admin-only Discord status channel, edited every 60 s, showing
Lobby and Survival player counts.

**Design.**
- Runs on the lobby server only. Survival's count and up/down come from `SmpLiveness` (SLP every 15 s,
  N-consecutive confirm). The Lobby count is local.
- Card: `🟢 Survival 12/20` + names · `🏛 Lobby 3` + names · `updated <t:…:R>`. Survival DOWN shows
  `🔴 Survival DOWN since <t:…:R>`. Lobby-role note: hidden staff (vanished) are not listed by name.
- New SERVER config keys (lobby): `adminStatusWebhook` (URL — a SECRET, set on the live server only,
  never committed), `adminStatusChannelId`, `adminStatusIntervalSeconds` (default 60, min 30).
- **Finding the message to edit:** saved message id in `<dataDir>/admin-status.json` → verify with
  `GET /webhooks/{id}/{token}/messages/{msg}`. If missing/404 and a bot token is configured, list the
  channel's oldest messages via the bot and adopt the first one authored by that webhook. Otherwise post a
  new one with `?wait=true` and save its id. Then `PATCH` it on every tick.
- Own daemon thread. Any HTTP failure or 429 is logged once and skipped until the next tick — never
  retried in a loop, never touches the server thread.

**Test.** Boot the lobby with the webhook set → card appears; join/leave → updates within 60 s; delete
the card → a new one is posted; stop Survival → DOWN after the confirm window.

---

## 3. Mail remake (CoffeesAeroAuth, SMP role)

**Goal.** Mail that players and staff can send, with no confusing subject syntax, a friendly help page and
a nicer look. Numismatics never travels by mail.

**Player-facing.**
- `/mail` — mailbox: each letter is an envelope icon (sealed = unread) named `From <sender>`, lore = first
  line, date, `📦 N items` if a parcel. Opening a letter shows it as a written-book page, plus a Claim
  button for parcels.
- `/mail send <player>` — (1) chat prompt "✉ Writing to <player>. Type your letter in chat (or `cancel`)".
  The next chat line is captured, not broadcast, and passes the chat filter (blocked → refused, try again).
  (2) Parcel window, 9 slots, letter preview, `✗ Cancel` / `✉ Send`. Cancel, closing the window,
  disconnecting or a 2-minute timeout returns every item to the sender's inventory (dropped at their feet
  if full).
- `/mail help` — one short page: reading, sending, claiming, what can't be mailed, limits.
- No subject field anywhere: the first line is the preview.

**Rules.**
- Blocked items: any `numismatics:*` item, and any container (shulker box, bundle, anything with item
  contents) that holds one, checked recursively. Refused with a clear message; the item stays in the window.
- Not allowed: mailing yourself; recipients with no profile.
- Limits: 10 sends/day per player (staff permission 3 exempt); letter ≤ 256 chars.
- Expiry (`mailExpiryDays`): an unclaimed PLAYER parcel is returned to the sender as a mail; system mail
  expires as today.
- Every player parcel → audit log + admin watchdog webhook (sender, recipient, item summary).

**Staff.** `/mail admin send <player>`, `sendall`, `sendonline` use the same chat-prompt + parcel flow
(items placed in the window are COPIED to each recipient). The 30 s re-run confirm for broadcasts stays.
The `subject | body` syntax and `item`/`itemall` are removed.

**Spurs.** Players cannot mail spurs; coins change hands in person, via a vendor, or a shop. System mail
keeps carrying the starter bonus and vote rewards (`spurs` field), unchanged.

**Storage.** Existing mail table; the `subject` column stores the first line (≤ 64 chars) so old rows still
render. Parcel items are removed from the sender only at the moment Send succeeds in the DB; on DB failure
they are returned.

**Test.** Send with/without items; try a coin, a shulker with a coin inside, self-mail, 11th send, filtered
word; close the window mid-compose; disconnect mid-compose; expire a parcel; staff broadcast confirm.

---

## 4. Admin handbook (PDF)

Not an in-game command. A PDF "AeroSMP Admin Handbook" for the owner to send to admins: moderation, mail
(incl. the new flow), identity/accounts, Season 3 tools, restarts and the startup-flag check, Discord,
and a "never do this" list. Built from the actual registered commands (permission ≥ 2) so it matches the
jar. Contains no IPs, secrets or webhook URLs. A Markdown copy goes into the Obaa vault.
