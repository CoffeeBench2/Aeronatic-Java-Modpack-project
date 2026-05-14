@echo off
:: -----------------------------------------------
:: Coffees Aero SMP — Packwiz Mod Installer (Windows)
:: -----------------------------------------------
:: Requires packwiz installed: https://packwiz.infra.link/
:: Run this from the project root (where pack.toml lives).
:: packwiz will generate the correct .pw.toml for each mod
:: with verified hashes and download URLs.

echo Installing Coffees Aero SMP mods via packwiz...
echo.

:: ---- CORE: Create Ecosystem ----
packwiz modrinth install create
packwiz modrinth install create-aeronautics
packwiz modrinth install sable
packwiz modrinth install create-trackwork
packwiz modrinth install create-liftoff
packwiz modrinth install create-connected
packwiz modrinth install create-tfmg
packwiz modrinth install create-numismatics
packwiz modrinth install create-enchantment-industry
packwiz modrinth install create-deco
packwiz modrinth install copycats
packwiz modrinth install create-power-loader
packwiz modrinth install create-steam-n-rails

:: ---- PERFORMANCE ----
packwiz modrinth install embeddium
packwiz modrinth install canary
packwiz modrinth install iris
packwiz modrinth install modernfix
packwiz modrinth install ferrite-core
packwiz modrinth install entityculling
packwiz modrinth install memoryleakfix
packwiz modrinth install moreculling
packwiz modrinth install immediatefast

:: ---- UTILITIES & API ----
packwiz modrinth install architectury-api
packwiz modrinth install cloth-config
packwiz modrinth install jei
packwiz modrinth install appleskin
packwiz modrinth install mouse-tweaks
packwiz modrinth install controlling
packwiz modrinth install catalogue
packwiz modrinth install searchables

:: ---- EXPLORATION & QoL ----
packwiz modrinth install xaeros-minimap
packwiz modrinth install xaeros-world-map
packwiz modrinth install waystones
packwiz modrinth install terralith
packwiz modrinth install farmers-delight
packwiz modrinth install supplementaries
packwiz modrinth install carry-on

:: ---- SERVER / MULTIPLAYER ----
packwiz modrinth install sable-protect
packwiz modrinth install open-parties-and-claims
packwiz modrinth install simple-voice-chat
packwiz modrinth install no-chat-reports

:: ---- AERONAUTICS IMMERSION ----
packwiz modrinth install vista_tv
packwiz modrinth install vista-aeronautics-fix
packwiz modrinth install aero_cam_sync
packwiz modrinth install sable-create-aeronautics-collision-damage

:: ---- CREATE ADD-ONS ----
packwiz modrinth install slice-and-dice
packwiz modrinth install create-dreams-and-desires
packwiz modrinth install extended-cogwheels
packwiz modrinth install create-misc-and-things
packwiz modrinth install createaddition
packwiz modrinth install create-new-age

:: ---- IMMERSIVE ENGINEERING ----
packwiz modrinth install immersiveengineering

:: ---- WORLD GENERATION ----
packwiz modrinth install tectonic
packwiz modrinth install terratonic

:: ---- VILLAGES & SETTLEMENTS ----
packwiz modrinth install ct-overhaul-village
packwiz modrinth install towns-and-towers

:: ---- WORLD STRUCTURES ----
packwiz modrinth install when-dungeons-arise
packwiz modrinth install structory
packwiz modrinth install explorify

:: ---- COMBAT & ADVENTURE ----
packwiz modrinth install create-big-cannons
packwiz modrinth install apotheosis
packwiz modrinth install alexs-mobs
packwiz modrinth install alexs-caves
packwiz modrinth install yungs-better-dungeons
packwiz modrinth install yungs-better-strongholds

:: ---- DIMENSIONS ----
packwiz modrinth install aether
packwiz modrinth install twilight-forest

:: ---- QUESTING ----
packwiz modrinth install ftb-quests
packwiz modrinth install ftb-library
packwiz modrinth install ftb-teams

:: ---- STORAGE ----
packwiz modrinth install sophisticated-backpacks
packwiz modrinth install sophisticated-storage
packwiz modrinth install toms-storage

:: ---- IMMERSION & BUILDING ----
packwiz modrinth install antique-atlas
packwiz modrinth install create-interiors
packwiz modrinth install handcrafted

echo.
echo All mods installed. Refreshing index...
packwiz refresh

echo.
echo Done! Run "packwiz modrinth export" to build the .mrpack file.
echo Run "packwiz curseforge export" to build the CurseForge .zip file.
pause
