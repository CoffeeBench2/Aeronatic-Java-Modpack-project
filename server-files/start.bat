@echo off
title Coffees Aero SMP Server

:: -----------------------------------------------
:: Coffees Aero SMP — Windows Server Start Script
:: Minecraft 1.21.1 / NeoForge 21.1.230
:: -----------------------------------------------
:: Adjust RAM values below to match your hardware.
:: Minimum 6GB recommended; 8-12GB for a full server.

:: JVM flags are set in user_jvm_args.txt (6G-10G RAM + Aikar's flags)
java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.230/win_args.txt nogui %*

pause
