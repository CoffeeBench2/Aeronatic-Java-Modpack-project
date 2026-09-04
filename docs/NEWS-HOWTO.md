# Writing the in-client News

The Announcements scroll on the title screen is **live**. Edit one file, push, and players see it
the next time they open the menu — **no pack rebuild, no version bump, no release**.

```
overrides/config/coffees_aero_announcements.json
```

Push it to `main` and you are done. Give the CDN ~5 minutes; the client already cache-busts, so it
will pick it up on its own.

---

## The shape of an entry

```json
{
  "version": "1.10",
  "date": "September 2026",
  "title": "Head for the Skies",
  "added":   ["...", "..."],
  "fixed":   ["...", "..."],
  "removed": ["..."]
}
```

Entries live in `"entries"`, **newest first**. `added` / `fixed` / `removed` can each be `[]` and
that section just won't render. Nothing else is required.

**Keep "On the horizon" pinned at the top.** It has an empty `date` and is the teaser for what is
coming; new releases go directly underneath it.

---

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
