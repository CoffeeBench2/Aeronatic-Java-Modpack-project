# server-assets

**Server-side deployables, tracked in git.** None of this ships to players.

Everything here is uploaded by hand to the game server. It is deliberately **not** in `overrides/`
— that directory is the client pack and drives the in-client updater, and none of these files belong
on a client.

🔴 **`server-assets/` is in `.packwizignore`.** It must stay there. packwiz indexes every on-disk
file that is not ignored, so without that entry every file here would be added to `index.toml` and
downloaded into every player's instance.

| Folder | Goes to | Applies with |
|---|---|---|
| `kubejs/` | `<server>/kubejs/server_scripts/` | `/kubejs reload server_scripts` — no restart |
| `datapacks/aero-admin-flat/` | zip → `<server>/world/datapacks/` | **restart** (dimensions load at world load) |
| `npc-presets/` | `<server>/config/easy_npc/preset/humanoid/` | `/easy_npc preset import_new …` |
| `jvm/log4j2.xml` | `<server>/` (server root, next to `server.jar`) | **restart** + a JVM flag, see below |

## `jvm/log4j2.xml`

Two mods were logging to disk **on the server thread** at high frequency — 656,216 lines from
`com.happysg.radar` `CannonMountPitch` and 261,766 from Sable's `SubLevelHoldingChunkMap`, measured
across 116 hours of logs on 2026-09-01. 🔑 **NeoForge writes DEBUG to `debug.log` regardless of what
the console shows**, so neither ever appeared in `latest.log` while every line was still a real
synchronous disk write on the tick loop. This config raises just those two loggers to WARN; their
genuine warnings and errors still come through.

It does nothing on its own — the JVM must be told to use it:

```
-Dlog4j.configurationFile=log4j2.xml
```

🔴 **On Lagless that flag cannot be set by us.** `user_jvm_args.txt` is NOT read, and the JVM Flags
panel variable did not apply either; the flags live in the admin-side startup command, so adding or
changing one means a support ticket. It went live 2026-09-02 in the same ticket that fixed the heap.
Verify with `grep -c rotateCBC logs/debug.log` — expect 0 after some uptime.

Built zips live in `Releases/`, which is gitignored — they are reproducible from the sources here.

⚠️ **Zip datapacks with Python `zipfile`, not PowerShell `Compress-Archive`.** Compress-Archive
writes backslash paths into the zip and Minecraft silently refuses to read the datapack.

```python
import os, zipfile
with zipfile.ZipFile("AeroAdminFlat.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("src"):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, "src").replace(os.sep, "/"))
```

## What's here

- **`kubejs/aero_greeter.js`** — lobby greeter NPC (`aero_npc_greeter` tag), admins-only NPC
  (`aero_npc_admin`), and the seal that ejects non-ops from the admin dimension.
- **`datapacks/aero-admin-flat/`** — the `aeroadmin:admin_flat` creative building dimension.
- **`npc-presets/aero_greeter.npc.snbt`** — reusable Easy NPC greeter preset.

Each folder has its own README with install steps and the gotchas.
