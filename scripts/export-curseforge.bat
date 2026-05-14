@echo off
:: -----------------------------------------------
:: Coffees Aero SMP — CurseForge Export (Windows)
:: -----------------------------------------------
:: Requires packwiz: https://packwiz.infra.link/
:: Run from the project root (where pack.toml lives).
:: Output: aero-smp-x.x.x-cf.zip (CurseForge format)

echo Exporting Coffees Aero SMP for CurseForge...
packwiz curseforge export

echo.
echo Upload the generated .zip to CurseForge via:
echo   https://www.curseforge.com/minecraft/modpacks
echo.
pause
