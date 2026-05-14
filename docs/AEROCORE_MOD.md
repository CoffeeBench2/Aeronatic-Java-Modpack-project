# CoffeesAeroSMP Core — Custom Mod Guide

The `CoffeesAeroSMP Core` mod is a small client-side NeoForge mod built specifically for this modpack. It controls who can access singleplayer and how the multiplayer button behaves.

---

## What It Does

| Feature | Behaviour |
|---------|-----------|
| **Singleplayer button** | Hidden for all players. Admin only sees it. |
| **Multiplayer button** | Replaced with "▶ Join Coffees Aero SMP" — auto-connects directly to the configured server IP |
| **Admin Settings panel** | Only visible to the admin. Lets you update the server IP and admin username without repackaging the mod. |
| **Live IP config** | Server IP is stored in `config/coffeesaerosmp_core-client.toml` — changing it via Admin Settings takes effect on next connection attempt. |

---

## First-Time Setup

### 1. Set your admin username

Open `src/AeroCore/gradle.properties` and set:
```
admin_username=YourExactMinecraftUsername
```

Or you can leave it as `CHANGE_ME` and update it in-game via **Admin Settings** on the first launch (you'll still see the button since it defaults to your current username before any config exists).

### 2. Set the default server IP

Edit `src/AeroCore/src/main/resources/META-INF/mods.toml` — or more easily, build the mod once, then update the config in-game via **Admin Settings**.

The config file is at:
```
.minecraft/config/coffeesaerosmp_core-client.toml
```

---

## Building the Mod

Requires Java 17 and Gradle.

```bash
cd src/AeroCore
./gradlew build
```

Output JAR will be at:
```
src/AeroCore/build/libs/aerosmp-core-1.0.0.jar
```

Copy this JAR into:
- Your `overrides/mods/` folder (so it ships with the modpack)
- The server's `mods/` folder is **not** needed — this mod is client-side only

---

## Updating the Server IP

When the server IP changes:

**Method 1 — Admin in-game (recommended):**
1. Launch the modpack with your admin account
2. On the main menu, click **"Admin Settings"**
3. Update the IP in the text field
4. Click "Save & Return"
5. The new IP is written to `config/coffeesaerosmp_core-client.toml` immediately

**Method 2 — Edit the config directly:**
Open `.minecraft/config/coffeesaerosmp_core-client.toml` and change:
```toml
[aerosmp]
    serverIP = "new.ip.here"
```

**Distributing the IP change to all players:**
The config file is per-installation. To push an IP update to all players, you have two options:
1. Re-export the modpack with the updated default IP in the config override
2. Use a redirect service (e.g. `play.aerosmp.net` DNS entry) so the domain always points to the current server — players never need to update

---

## How Admin Access Works

The mod compares the logged-in Minecraft account's username against `adminUsername` in the config at the time the main menu loads. If they match:
- The Singleplayer button is visible
- The "Admin Settings" button appears below the Join button

This check is **client-side only** — it does not affect actual server permissions. For server-side admin rights, use NeoForge's ops system (`/op <username>`).
