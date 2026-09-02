# Project E — Player Display & Lobby Round Trip

**Date:** 2026-09-02
**Scope:** CoffeesAeroAuth only. No module split, no new mods.
**Status:** design approved, not yet implemented

This is the first of five projects carved out of a larger request. The remaining four
(C: lag monitor v2, B: exploit resolver, D: block history, A: module split) each get their
own spec. Order agreed with the owner: **E → C → B**.

E was deliberately placed first. The module split (A) is the most satisfying but the most
dangerous change, and it cannot be validated while the production server is down. E is six
visible, small-file fixes that also map the real coupling between the clan, tablist, sidebar
and lobby subsystems — which is exactly the knowledge needed to draw good module boundaries
in A later.

---

## Problem

### The display bug

Three independent systems write a player's displayed name, on overlapping schedules, and
overwrite each other:

| System | Writes | Cadence |
|---|---|---|
| `NameVisibility.reveal()` | scoreboard team prefix (badge + clan tag) | on auth / on tag change |
| `TabListManager.sendStyledNames()` | `UPDATE_DISPLAY_NAME` packet (name styles) | ~2/sec |
| `TabListManager.sendAdminNameOverlay()` | `UPDATE_DISPLAY_NAME` packet (op real-name reveal) | ~2/sec |

When a player has a tab-list display name set, the client renders that verbatim and ignores
the scoreboard team prefix. `sendStyledNames` builds its component from
`NameStyles.nameComponent(...)`, which contains no clan tag. So `NameVisibility` paints the
tag into the team prefix and `sendStyledNames` erases it from TAB twice a second.

`sendAdminNameOverlay` has the same gap — it rebuilds `"✈ " + display + " (real)"` with the
account badge but no clan tag — so ops lose the tag too, and the two packet senders also race
each other on the same field.

**This single root cause explains three of the reported bugs:** clan tag missing from TAB,
the op tag "not working", and the join message rendering in default yellow rather than the
player's real colours.

### Separately: the nameplate

The above-head nameplate uses the team prefix and no packet, so it should be working. It is
reported as not working. Leading hypothesis: **FTB Teams assigns party members to its own
scoreboard team**, and `addPlayerToTeam` moves a player off their previous team, silently
evicting them from the per-player `ap_<uuid>` team that carries the tag. That would affect
exactly the players who have a clan, since the clan *is* the FTB party.

This is unconfirmed — it needs a running server to verify. The design mitigates it, but the
nameplate may need a second pass after in-game testing.

### The lobby

`LobbyInventoryStash` was built for a **one-way** trip: stash at login, restore once at
`/spawn`. The request is for a **repeatable round trip** by an already-verified player who may
be carrying everything they own, into a **public shared lobby**.

---

## Design

### E1 — `PlayerDisplay`, the single renderer

New class `display/PlayerDisplay.java`. One pure function; every consumer calls it.

```java
Component render(ServerPlayer subject, @Nullable ServerPlayer viewer, Surface surface)

enum Surface { TAB, NAMEPLATE, CHAT, JOIN, DISCORD }
```

Composition order:

```
[account badge] [staff badge] [clan tag] [styled name] [(RealName)]
     ✈ / ◈         [ADMIN]      [AERO]     rgb/hex/§k    ops only
```

Per-surface capability — driven by what each mechanism can physically do, not by preference:

| Surface | Mechanism | Per-viewer | Animated | Carries |
|---|---|---|---|---|
| `TAB` | `UPDATE_DISPLAY_NAME` packet | yes | yes | everything |
| `NAMEPLATE` | scoreboard team prefix | **no** | **no** | badge, staff, clan tag, plain name |
| `CHAT` | component per recipient | yes | static only | everything |
| `JOIN` | component per recipient | yes | static only | everything |
| `DISCORD` | plain string | n/a | no | text only, § stripped |

A team prefix is global to the team, so the nameplate structurally cannot carry a per-viewer
op reveal or per-character animation. It gets the static subset. This is a limitation of
Minecraft, not a shortcut.

**Consumers rewritten to call it:**

- `TabListManager.sendStyledNames` + `sendAdminNameOverlay` → collapse into **one**
  per-viewer send. They already loop per viewer, so this removes a packet rather than adding
  one.
- `NameVisibility.reveal` → builds its prefix from `PlayerDisplay(NAMEPLATE)`.
- Join/leave message → `PlayerDisplay(JOIN)`.
- Chat → `PlayerDisplay(CHAT)`.
- Discord bridge → `PlayerDisplay(DISCORD)`.

**FTB Teams mitigation:** re-assert the personal team on FTB team-change events, so a party
membership change cannot leave a player without their tag.

### E2 — Staff badges

Rank comes from config, not op level, so a moderator can be badged without being given
command powers.

```
staffOwner = "MrCoffeeBench"      # label + colour per rank
staffAdmin = ""
staffMod   = ""
```

Re-read on the existing ~5s tick, matching how `DISPLAY_RGB_NAMES` already behaves, so edits
apply live without a restart.

Occupies the staff-badge slot in `PlayerDisplay`. Not implemented anywhere today — no
staff-badge code exists in the mod, which is why it "didn't work out".

### E3 — RGB clan tags, staff only

`RainbowText` already animates names. Extend it to paint the clan tag as well. Gated to
players in the staff config; ordinary parties keep the existing 14 flat colours.

Applies on surfaces that support animation (TAB, and chat as static). The nameplate keeps the
flat colour.

### E4 — `/lobby` round trip

Available to all authenticated players. The lobby is **public and shared**, so inventory is
stashed rather than carried.

**Gates, evaluated in order:**

1. **Combat** — extend `pvp/CombatGuard`, which already lists `lobby` alongside `logout`,
   `back` and `spawn`. Largely built.
2. **Environmental** — refuse while falling (not `onGround` and no water/vehicle below),
   burning, in lava, or below `lobbyMinHealth` (config, default 6.0 = 3 hearts).
3. **Cooldown** — new, per-player, `lobbyCooldownSeconds` (config, default 60).

**Stash:** reuse `LobbyInventoryStash`. Its guarantees are the reason it is being reused and
must not be weakened:

- persisted to disk **before** the inventory is cleared
- idempotent on re-entry — never overwrites a persisted stash with an empty one
- restore loads first and only then drops the entry; a failed restore keeps the stash

**One addition.** The stash is keyed `UUID → blob` with no record of *why* it was taken, so an
auth stash and a voluntary stash would collide on the same key. Add a reason marker
(`AUTH` | `VOLUNTARY`) so restore knows which flow it is completing. The existing
merge-carried-items path continues to cover the case where a player returns holding real items.

### E5 — OP join hiding

Per-op toggle, set by command, persisted to a JSON file in the mod data dir alongside
`clan_tags.json` (not the DB — this is display state, and the vault rule keeps non-essential
work off the DB path). Survives restart, so an op who logged out hidden returns hidden.

- suppresses the join and leave lines
- filters the player out of the TAB packet for non-ops (cheap — the packet is already built
  per viewer)
- ops still see hidden ops, marked
- player count decremented to match, so TAB and the footer do not contradict each other

**Out of scope:** hiding the player entity. That is a full vanish system — entity tracking,
chunk visibility, interaction suppression — and belongs in its own project. A hidden op is
still physically present and visible in world.

### E6 — `/back` cooldown

`backCooldownSeconds` 600 → 60. `backWindowSeconds` unchanged.

### E7 — Lobby copy

`TabListManager` currently tells lobby players "⌂ your private hangar ⌂" and "Your private
hangar is grief-proof". The lobby is public and shared, so this is false. Correct the tips.

---

## Testing

The production server is offline pending a host-side startup-command fix, so verification
happens on the local 66-mod test server (the same one 1.7.43 was boot-tested on).

**Verifiable without a second player:** boot, config load, staff list parsing and live
re-read, no exception from any packet path, `/lobby` gate refusals, stash write/restore round
trip, `/back` cooldown value.

**Requires two connected clients, owner-driven:** clan tag actually rendering above the head
and in TAB, op real-name reveal appearing for ops and not for players, RGB animation, and OP
hiding as seen by a non-op.

The nameplate/FTB-Teams hypothesis can only be confirmed in game. If the mitigation does not
fix it, that becomes a follow-up.

---

## Explicitly out of scope

- Module split (project A) — including the shared-config-across-mods question
- Create: Exploit Resolver (project B)
- Lag monitor v2 (project C)
- Block history (project D)
- Full entity vanish
- Any change to the auth lobby's existing stash-on-login behaviour
