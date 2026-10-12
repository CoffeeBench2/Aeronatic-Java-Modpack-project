package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.discord.expert.CoffeeBot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The CoffeeBot expert on a real server: answers come back from its worker thread with LIVE data read on the
 * server thread (no deadlock while the test itself is between ticks): status counts, the real recipe registry,
 * the real mod list, the restart schedule; plus the intro card for a bare mention (the old banter is gone).
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class CoffeeBotGameTests {

    @GameTest(template = "empty", batch = "coffeebot", timeoutTicks = 200_000)   // unthrottled ticks: not seconds
    public static void coffeeBotAnswersWithLiveData(GameTestHelper h) {
        String err = CoffeeBot.reload();
        if (err != null) h.fail("knowledge base failed to load: " + err);
        if (CoffeeBot.topicCount() < 80) h.fail("only " + CoffeeBot.topicCount() + " topics loaded");
        if (!CoffeeBot.active()) h.fail("expert not active on a non-lobby server");

        Map<String, String> asks = new LinkedHashMap<>();
        asks.put("status", "<@1> is the server down?");
        asks.put("mine", "<@1> whats my level");
        asks.put("hello", "<@1>");
        asks.put("recipe", "<@1> how do i craft a crafting table?");
        asks.put("mod", "<@1> what is coffees aero auth");
        asks.put("who", "<@1> who is online");
        asks.put("restart", "<@1> when is the next restart");
        Map<String, String> got = new ConcurrentHashMap<>();
        int n = 0;
        for (var e : asks.entrySet()) {
            String k = e.getKey();
            CoffeeBot.ask("gt-" + (n++), e.getValue(), a -> got.put(k, a == null ? "<null>" : a));
        }

        h.succeedWhen(() -> {
            if (got.size() < asks.size()) throw new GameTestAssertException("waiting for answers " + got.keySet());
            try {
                StringBuilder dump = new StringBuilder();
                for (var e : asks.entrySet()) dump.append("Q: ").append(e.getValue()).append('\n').append(got.get(e.getKey())).append("\n\n");
                java.nio.file.Files.writeString(java.nio.file.Path.of("coffeebot-answers.txt"), dump);
            } catch (Exception ignored) { }
            String s = got.get("status");
            if (!s.contains("online") || !s.matches("(?s).*\\d+/\\d+.*") || s.contains("{")) h.fail("status: " + s);
            if (!got.get("mine").contains("/discord link")) h.fail("unlinked asker not told how to link: " + got.get("mine"));
            String hello = got.get("hello");
            if (!hello.contains("how do I join") || hello.contains("Not sure what you want")) h.fail("intro card: " + hello);
            String recipe = got.get("recipe");
            if (!recipe.contains("Crafting Table") || !recipe.contains("Crafting table") || !recipe.contains("Planks"))
                h.fail("recipe lookup: " + recipe);
            String mod = got.get("mod");
            if (!mod.contains("Coffees Aero Auth") || !mod.contains("own server mods") || mod.toLowerCase().contains("cracked"))
                h.fail("own-mod lookup should be short and say nothing about internals: " + mod);
            if (!got.get("who").contains("online")) h.fail("who's online: " + got.get("who"));
            if (!got.get("restart").contains("daily restart")) h.fail("restart: " + got.get("restart"));
        });
    }
}
