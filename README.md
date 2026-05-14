# Coffees Aero SMP — Custom Minecraft Modpack

A custom Java Edition modpack built around the **Create: Aeronautics** ecosystem. Build and pilot airships, construct sky-bases, run an airborne economy, and explore a world seen from above.

- **Minecraft:** 1.21.1
- **Loader:** NeoForge 21.1.0
- **Distribution:** Modrinth (mrpack)
- **Target:** SMP Server + Client

---

## Features

- Full **Create: Aeronautics** stack — airships, physics, rocket components, tank tracks
- **Heavy industry** via Create: TFMG — oil, steel, and large-scale production
- **Player economy** with Create: Numismatics
- **Trains & ground transport** via Create: Steam 'n' Rails
- Optimized performance with Embeddium, Canary, Oculus, and FerriteCore
- Proximity **voice chat** via Simple Voice Chat
- **Airship claiming & protection** via Sable Protect
- Land claiming and party system via Open Parties and Claims
- Rich world generation with Terralith

---

## Quick Start

See [`docs/INSTALLATION.md`](docs/INSTALLATION.md) for the player install guide.
See [`docs/SERVER_SETUP.md`](docs/SERVER_SETUP.md) for server admin setup.

---

## Project Structure

```
pack.toml              Packwiz manifest
mods/                  Per-mod .pw.toml files
overrides/
  config/              Pre-configured mod settings
  resourcepacks/       Bundled resource packs
server-files/          Server startup scripts & properties
docs/                  Installation and gameplay guides
```

---

## Building the Pack

Requires [packwiz](https://packwiz.infra.link/) CLI.

```bash
# Install all mods and generate index
packwiz refresh

# Export as Modrinth .mrpack
packwiz modrinth export
```

---

## Contributing

1. Fork the repo
2. Create a branch: `git checkout -b feature/your-mod-addition`
3. Add your mod via `packwiz modrinth install <slug>`
4. Update `MODLIST.md` with a description
5. Open a pull request
