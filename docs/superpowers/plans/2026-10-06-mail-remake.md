# Mail Remake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Players and staff send mail through `/mail send <player>` → letter typed in chat → parcel window → Send; no subject syntax; `/mail help`; letters read as a real book; Numismatics never travels; expired player parcels return to the sender.

**Architecture:** Keep `MailStore`'s two invariants (dedupe-keyed INSERT IGNORE, compare-and-set claim) and the click-proof `MailMenu`. Add: pure `MailRules` (validation, preview, book pages, blocked-content scan — unit-tested), `MailCompose` (per-player draft sessions + HIGHEST-priority chat capture), `ParcelMenu` (9 real slots that refuse blocked items, button row, two-step confirm for broadcasts), `MailBook` (virtual written-book screen via fake slot packet; server inventory never changes). `MailStore` gains `sender_uuid`, a rolling-24h send count and expired-parcel returns.

**Tech Stack:** Java 21, NeoForge 21.1.x, MySQL 8, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-06-s3-channel-live-counts-mail-handbook-design.md` §3

## Files
- Create `mail/MailRules.java` (pure) + `test/.../mail/MailRulesTest.java`
- Create `mail/MailCompose.java`, `mail/ParcelMenu.java`, `mail/MailBook.java`
- Modify `mail/MailStore.java` (sender_uuid column + index, `countSentSince`, `send` with sender uuid, `returnExpired`)
- Modify `mail/MailGui.java` (inbox look, letter view, "📖 Read" button)
- Modify `mail/MailItems.java` (`containsBlocked(ItemStack, registries)`)
- Rewrite `commands/MailCommands.java` (`/mail`, `/mail help`, `/mail send`, `/mail admin send|sendall|sendonline`)
- Modify `CoffeesAeroAuth.java` (chat capture at HIGHEST, logout return, boot `returnExpired`)

## Task 1: MailRules (TDD)
Tests: letter blank → error; 257 chars → error naming 256; 256 ok; preview = first line, ≤ 64 chars with "…"; `\n` literal treated as newline; `containsBlockedSnbt` true for `numismatics:spur` at any depth incl. inside `minecraft:container`, false otherwise; `bookPages` wraps to ≤ 19-char lines, ≤ 13 lines/page, never splits a word ≤ 19; `limitReached(10, 10)` true, `(9, 10)` false.
- [ ] write tests → run (FAIL) → implement → run (PASS) → commit.

## Task 2: MailStore
- [ ] `createSchema`: `ALTER TABLE mail ADD COLUMN sender_uuid CHAR(36) NULL` (swallow 1060 duplicate column), `ADD INDEX idx_mail_sender (sender_uuid, created_at)` (swallow 1061).
- [ ] `Outgoing` gets `senderUuid` (null for system); insert writes it.
- [ ] `countSentSince(server, sender, sinceMs, Consumer<Integer>)`.
- [ ] `returnExpired()` replaces `purgeExpired()`: unclaimed expired rows with `sender_uuid` + items → INSERT IGNORE a return mail to the sender (dedupe `return:<id>`, never expires), then DELETE all expired.

## Task 3: ParcelMenu + MailBook + MailCompose + commands + GUI
- [ ] ParcelMenu: 9x3; slots 0–8 real (`mayPlace` rejects blocked); 9–26 buttons (no place/pickup); shift-click both ways; Cancel/close/logout returns items once (`settled` flag); Send = snapshot + clear, then DB; failure → give back (offline → watchdog alert with SNBT).
- [ ] Broadcast (>1 recipient) needs a second Send click within 30 s ("⚠ Confirm: mail N players").
- [ ] Staff mode copies items (the staff member's own stacks return on close); player mode moves them.
- [ ] MailCompose: `begin`, chat capture (cancel/too long/filter BLOCK→retry, CENSOR→starred), 2-minute expiry checked on next chat, logout clears.
- [ ] MailBook: written book with title/author/pages; fake `ClientboundContainerSetSlotPacket` into the selected hotbar slot, `ClientboundOpenBookPacket`, then resync the real slot.
- [ ] Commands + `/mail help`; GUI restyle; watchdog LOW alert per player parcel.

## Task 4: Build + boot test
- [ ] `gradlew.bat build` green. Boot test on the S3 test backend with two accounts (owner): send/claim, coin + shulker-with-coin refused, self refused, 11th send refused, filtered word, close/disconnect mid-compose, staff broadcast confirm, book view.
