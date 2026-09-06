# Writing the in-client News

The Announcements scroll on the title screen is **live**. Edit one file, push, and players see it
the next time they open the menu — **no pack rebuild, no version bump, no release**.

```
overrides/config/coffees_aero_announcements.json
```

🔴 **`packwiz refresh` first, then push.** This file is **in the packwiz index** with a recorded
hash, so editing it and pushing on its own leaves the index pointing at a hash the served file no
longer has — and every client mid-update fails with `hash mismatch after download`. The live news
fetch and the packwiz index are two separate mechanisms and this one file is in both.

```
py -c "import json;d=json.load(open('overrides/config/coffees_aero_announcements.json',encoding='utf-8'));print(len(d['entries']),'entries OK')"
packwiz refresh
git add overrides/config/coffees_aero_announcements.json index.toml pack.toml && git commit && git push
```

What you still **don't** need is a version bump, a pack rebuild or a GitHub release — the client
pulls the news live from raw `main` and cache-busts, so players see it on their next menu visit.
Give the CDN ~5 minutes.

---

## The shape of an entry

```json
{
  "version": "1.10",
  "date": "September 2026",
  "title": "Head for the Skies",
  "tag": "MAJOR",
  "banner": "https://raw.githubusercontent.com/.../banner.png",
  "body": "A paragraph in plain prose. Use it to say what the release is about.",
  "added":   ["...", "..."],
  "fixed":   ["...", "..."],
  "removed": ["..."],
  "images":  ["https://.../shot1.png", "https://.../shot2.png"],
  "link":    { "label": "Full changelog on GitHub", "url": "https://..." }
}
```

Entries live in `"entries"`, **newest first**. Only `version`, `date` and `title` really matter —
everything else can be left out and that part simply will not render. An entry written before the
1.10.9 redesign still works exactly as it did.

🔴 **The newest release must be entry 0 — above "On the horizon".** This reverses the old rule, and
the reason is not cosmetic. `AnnouncementData.latest()` returns `entries.get(0)`, and **both** the
What's New popup and the "NEW" badge are driven by it:

- With the teaser pinned first, the popup shows **"Still in the workshop"** instead of the release.
- Worse, `AnnouncementState` stores the seen-state as `latest.version()` — the literal string
  `"On the horizon…"`, which never changes. Once a player dismisses it, `hasUnseen()` is false
  **forever**, so no future release ever pops the card or re-badges the button again.

Putting the release at index 0 fixes both without a code change: the version string differs from
whatever the player has stored, so the badge and popup fire exactly as intended.

"On the horizon" now sits **second**, directly under the newest release. It still renders fine in the
scroll — newest release, then what's coming, then the back catalogue.

⚠️ The durable fix belongs in the Core: `latest()` should return the first entry with a **non-blank
`date`**, which would let the teaser be pinned first again and make the ordering irrelevant. Until
that ships, the ordering above is load-bearing — do not "tidy" the teaser back to the top.

### The newer fields

| Field | What it does |
|---|---|
| `tag` | Coloured badge and the card's accent stripe. `MAJOR` gold · `UPDATE` green · `HOTFIX` red · `SOON` violet. Anything else falls back to brass. |
| `banner` | A wide picture under the title, full card width, aspect kept, capped at 150px tall. |
| `body` | A prose paragraph. Use it for the one thing you would say out loud about the release; leave the detail to the bullets. |
| `images` | A row of thumbnails at the bottom of the card. They wrap. Three across looks right. |
| `link` | A clickable line at the bottom. Opens through Minecraft's own "are you sure" prompt. |

### About the pictures

Anything reachable over **https** works. The easy option is to commit the image into this repo and
point at its `raw.githubusercontent.com` URL — the same place the news file itself comes from, so if
one loads the other will.

- **PNG.** Nothing else is decoded.
- **Under 4 MB and under 2048px** a side, or it is skipped.
- Downloaded once, then cached on disk forever in `.aero-update/newscache/`. Players see them
  instantly on every later visit, and offline.
- A picture that fails to load costs you nothing — the card renders without it. It never blocks
  the text.
- Wide images suit `banner` (roughly 3:1 reads best). Anything squarer is happier in `images`.

## How to write the lines

The scroll is read by players, not by us. The rule of thumb: **say what they can now do, not what
we changed.**

| Don't | Do |
|---|---|
| Updated Sable to 2.0.5 and Aeronautics to 1.3.2 | Trains and contraptions run smoother than they did last season |
| Added `vivecraft`, `vrapi`, `sablevivecraftfix` | Play the whole server in VR — strap in, grab the wheel, and fly your airship by hand |
| Fixed `PLAYER_OWNED` seed-once in the updater | Your keybinds, video settings and map waypoints survive updates now |
| Removed `create_parachute-1.0.4a.jar` | Parachute has left the pack |

More specifically:

- **No mod names or version numbers.** Players don't know what Sable is. They know their ship flies.
- **No filenames, no config keys, no jargon.** If a line needs a wiki tab open, rewrite it.
- **One idea per line.** If a line has an "and" doing heavy lifting, it is probably two lines.
- **A little warmth is right, a lot is noise.** "Ragdolls, because falling off an airship deserved a
  better send-off" earns its keep. Don't do that on every line.
- **Group by what it means, not by which mod did it.** Four Create mods producing one benefit is one
  line, not four.
- **`removed` is not an apology.** State it plainly and move on.

`title` is a short evocative name for the release — "Head for the Skies", "Guns, Gears &
Guardrails". `date` is a month, not a full date; nobody needs the day.

Em-dashes and apostrophes are fine — the file is UTF-8. Save it as UTF-8 (no BOM) or the accents
will come out as garbage in game.

---

## Before you push

Check it is still valid JSON — one stray comma and the scroll falls back to the bundled copy and
your new entry never appears:

```
py -c "import json;d=json.load(open('overrides/config/coffees_aero_announcements.json',encoding='utf-8'));print(len(d['entries']),'entries OK')"
```

---

## If it doesn't show up

1. **Bad JSON.** The client silently falls back to the bundled file. Run the check above.
2. **Not pushed to `main`.** The client reads raw `main`, not your local copy.
3. **Under ~5 minutes since the push.** GitHub's raw CDN caches each path for about 300 seconds.
4. **The player is offline.** They get the copy bundled in the pack instead, which is whatever was
   current when their pack was built.

Because of point 4, the bundled copy is worth keeping roughly current — but it only updates when a
pack release is cut, and that is the one thing this file exists to avoid needing.
