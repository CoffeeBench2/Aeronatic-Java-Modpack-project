@echo off
:: -----------------------------------------------
:: Coffees Aero SMP — Modrinth Export (Windows)
:: -----------------------------------------------
:: Requires packwiz: https://packwiz.infra.link/
:: Run from the project root (where pack.toml lives).
:: Output: aero-smp-x.x.x.mrpack

echo Exporting Coffees Aero SMP for Modrinth...
packwiz modrinth export

echo.
echo Upload the generated .mrpack to Modrinth via:
echo   https://modrinth.com/dashboard/projects
echo.
pause
