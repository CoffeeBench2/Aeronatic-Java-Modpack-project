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
