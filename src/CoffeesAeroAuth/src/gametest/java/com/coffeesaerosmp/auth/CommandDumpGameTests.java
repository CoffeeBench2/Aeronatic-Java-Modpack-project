package com.coffeesaerosmp.auth;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes every command this mod registers, with full usage, to run-gametest/command-dump.txt (for the handbook). */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class CommandDumpGameTests {

    static final List<String> OURS = List.of(
        "aerobot", "aeroid", "aerostore", "afkcheck", "authmod", "back", "beans", "buy", "changename", "changepassword",
        "clan", "cosmetics", "daily", "discord", "home", "invsee", "invsee_curios", "invsee_echest", "lobby", "login",
        "logout", "mail", "mytrustedips", "namecolor", "profile", "register", "rtp", "sablecollision", "setbio",
        "setname", "shipname", "shop", "sidebar", "spawn", "ticket", "tpa", "tpaccept", "tpdeny", "vote", "lag",
        "skin", "resetpassword", "unlockadmin", "freshstart", "itemclear", "lockdown", "confiscate", "censor", "filter");

    @GameTest(template = "empty", batch = "zz_command_dump", timeoutTicks = 20)
    public static void dumpCommands(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var src = server.createCommandSourceStack();
        List<String> out = new ArrayList<>();
        for (var node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            if (!OURS.contains(name)) continue;
            String[] usage = dispatcher.getAllUsage(node, src, false);
            if (usage.length == 0) out.add("/" + name);
            for (String u : usage) out.add("/" + name + " " + u);
        }
        out.sort(String::compareTo);
        Files.write(Path.of("command-dump.txt"), out, StandardCharsets.UTF_8);
        // Same tree as an ordinary player (permission 0) sees it: what's left is the player-facing set.
        var player = src.withPermission(0);
        List<String> pl = new ArrayList<>();
        for (var node : dispatcher.getRoot().getChildren()) {
            if (!OURS.contains(node.getName()) || !node.canUse(player)) continue;
            String[] usage = dispatcher.getAllUsage(node, player, true);
            if (usage.length == 0) pl.add("/" + node.getName());
            for (String u : usage) pl.add("/" + node.getName() + " " + u);
        }
        pl.sort(String::compareTo);
        Files.write(Path.of("command-dump-player.txt"), pl, StandardCharsets.UTF_8);
        h.succeed();
    }
}
