@echo off
:: -----------------------------------------------
:: Coffees Aero SMP — CurseForge Export (Windows)
:: -----------------------------------------------
:: Produces a FULLY SELF-CONTAINED CurseForge zip:
::   - all mods bundled or CF-manifest-referenced (no runtime GitHub dependency)
::   - ships the NO-UPDATER Core (-cf variant); the in-client updater is ABSENT
::     (CurseForge forbids external-fetch mods; the CF app is the updater there)
::
:: Requires: packwiz on PATH, and the -cf Core built once via:
::   cd src\AeroCore && gradlew jar -PnoUpdater
:: Run from the project root (where pack.toml lives).
:: Output: D:\MC Project\Releases\CoffeesAeroSMP-<ver>-CURSEFORGE.zip

echo Building self-contained CurseForge pack (no-updater Core)...
py scripts\build_cf.py

echo.
echo Upload the generated zip to CurseForge:
echo   https://www.curseforge.com/minecraft/modpacks
echo.
pause
