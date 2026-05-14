# Server Setup Guide

## Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| RAM | 8 GB | 12–16 GB |
| CPU | 4 cores @ 3.0 GHz | 6+ cores @ 3.5+ GHz |
| Java | 17 | 21 |
| Disk | 10 GB | 25 GB (SSD preferred) |
| OS | Linux (Ubuntu 22.04) or Windows Server |

---

## Setup Steps

### 1. Install NeoForge Server

1. Download NeoForge 21.1.0 installer from [neoforged.net](https://neoforged.net)
2. Run: `java -jar neoforge-47.1.0-installer.jar --installServer`
3. This creates `server.jar` and `libraries/` in the current directory

### 2. Install Mods

Copy all server-compatible mods from the `mods/` folder of the mrpack into the server's `mods/` directory.

> Client-only mods (Embeddium, Oculus, AppleSkin, Mouse Tweaks, Controlling, Xaero's, Catalogue) should **not** be installed on the server.

### 3. Apply Configuration Overrides

Copy the contents of `overrides/` into the server root directory. This applies pre-tuned configs for:
- Simple Voice Chat
- Open Parties and Claims

### 4. Configure server.properties

Copy `server-files/server.properties` to the server root.

Key settings already configured:
- `allow-flight=true` — **required** for Aeronautics airships
- `view-distance=10` — balanced for contraption-heavy servers
- `simulation-distance=8`

### 5. Accept the EULA

Create `eula.txt` in the server root:
```
eula=true
```

### 6. Start the Server

**Windows:**
```bat
start.bat
```

**Linux:**
```bash
chmod +x start.sh
./start.sh
```

---

## Voice Chat Port

Simple Voice Chat uses **UDP port 24454** in addition to the standard TCP port.
Open this port on your firewall/router for proximity voice chat to work.

---

## Chunk Loading

Create contraptions (especially airships) require chunks to stay loaded. The **Create: Power Loader** mod is included — give trusted players access to chunk loaders for their builds.

Recommended server-side chunk loading settings:
- Keep `simulation-distance` at 8 or above
- Warn players not to run more than 2–3 large airships simultaneously on underpowered hardware

---

## Performance Tips

- Use **Aikar's JVM flags** (already in `start.sh` / `start.bat`)
- Enable `sync-chunk-writes=false` for SSD servers (edit `server.properties`)
- Monitor TPS with `/forge tps` — target 18+ TPS under normal load
