# Player Installation Guide

## Prerequisites

- Java 17 or 21 (required for 1.20.1)
- At least **6 GB RAM** allocated to Minecraft
- [Modrinth App](https://modrinth.com/app) (recommended) or Prism Launcher

---

## Method 1 — Modrinth App (Recommended)

1. Open the Modrinth App
2. Click **Browse** and search for `Coffees Aero SMP`
3. Click **Install**
4. Once installed, select the instance and click **Play**

---

## Method 2 — Prism Launcher (Manual mrpack)

1. Download the latest `aero-smp-x.x.x.mrpack` from the [Releases](../releases) page
2. Open Prism Launcher → **Add Instance** → **Import from zip**
3. Select the downloaded `.mrpack` file
4. Set Java memory to at least **6144 MB** (6 GB)
5. Launch

---

## Shader Setup (Optional but Recommended)

The modpack ships with Oculus (NeoForge shader support). To use shaders:

1. Download a compatible shader pack (e.g. Complementary Reimagined, BSL)
2. Place the `.zip` in your instance's `shaderpacks/` folder
3. In-game: **Options → Video Settings → Shader Packs**

> Shaders are highly recommended — Aeronautics airships look incredible at sunset altitude.

---

## Recommended JVM Arguments

```
-Xms4G -Xmx6G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
```

---

## Joining the SMP

Once installed, add the server IP in **Multiplayer → Add Server**. Ask an admin for the current server address.
