# Coffees Aero SMP — Auto-Update (Packwiz) Setup

This makes your instance **auto-update its mods every launch**, so you never get kicked at the
loading screen with a "registry / railways:" wall just because your pack is a version behind.

It pulls from the official pinned index, so you always get the **exact** mod versions the server
runs — no version drift.

---

## One-time setup (Prism Launcher)

**1. Install the pack once (gets mods + configs):**
Import `CoffeesAeroSMP-1.3.0.mrpack` into Prism (`Add Instance → Import`). Launch it once so the
configs (server IP, resource-pack order, voice chat, etc.) are in place.

**2. Drop the bootstrap into the instance:**
Download **packwiz-installer-bootstrap.jar**:
> https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest/download/packwiz-installer-bootstrap.jar

Put it in the instance's **`.minecraft`** folder (Prism → right-click instance → *Folder*).

**3. Add the pre-launch command:**
Prism → right-click the instance → **Edit → Settings → Custom commands** → enable **Custom commands**
→ in **Pre-launch command** paste:

```
"$INST_JAVA" -jar "$INST_MC_DIR/packwiz-installer-bootstrap.jar" -s client https://raw.githubusercontent.com/CoffeeBench2/Aeronatic-Java-Modpack-project/main/pack.toml
```

Save. **Done.**

---

## What happens now
- Every time you launch, the bootstrap checks the official index and downloads any **new/changed
  mods**, removes any that were dropped, and leaves everything else alone — then the game starts.
- Your **configs and options stay put** (the bootstrap only manages mods + resource packs).
- When we push an update, you just **launch** — no re-importing, no manual downloads, no wall.

## Notes
- First launch after setup may take a few seconds while it syncs.
- It reads the pinned index, so you get the **same versions as the server** — that's the whole point.
- If the bootstrap ever can't reach GitHub, it just launches with what you have (fail-safe).
