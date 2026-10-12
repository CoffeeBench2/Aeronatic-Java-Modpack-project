package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.discord.expert.CoffeeBot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The CoffeeBot expert on a real server: answers come back from its worker thread with LIVE facts read from the
 * server thread (which must not deadlock while the test itself holds the server thread between ticks), personal
 * questions from an unlinked asker get the link instructions, and a reload keeps working.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class CoffeeBotGameTests {

    @GameTest(template = "empty", batch = "coffeebot", timeoutTicks = 200_000)   // unthrottled ticks: not seconds
    public static void coffeeBotAnswersWithLiveFacts(GameTestHelper h) {
        if (CoffeeBot.reload() != null) h.fail("knowledge base failed to load: " + CoffeeBot.reload());
        if (CoffeeBot.topicCount() < 50) h.fail("only " + CoffeeBot.topicCount() + " topics loaded");
        if (!CoffeeBot.active()) h.fail("expert not active on a non-lobby server");

        AtomicReference<String> status = new AtomicReference<>(), mine = new AtomicReference<>(), hi = new AtomicReference<>("unset");
        CoffeeBot.ask("gt-1", "<@1> is the server down?", status::set);
        CoffeeBot.ask("gt-2", "<@1> whats my level", mine::set);
        CoffeeBot.ask("gt-3", "<@1> hi", a -> hi.set(a == null ? "null" : a));

        h.succeedWhen(() -> {
            if (status.get() == null || mine.get() == null || "unset".equals(hi.get()))
                throw new GameTestAssertException("waiting for answers");
            String s = status.get();
            if (!s.contains("online") || !s.matches("(?s).*\\d+/\\d+.*")) h.fail("status answer lacks live counts: " + s);
            if (s.contains("{") || s.contains("?/?")) h.fail("unfilled placeholder: " + s);
            if (!mine.get().contains("/discord link")) h.fail("unlinked asker not told how to link: " + mine.get());
            if (!"null".equals(hi.get())) h.fail("small talk should be left to banter, got: " + hi.get());
        });
    }
}
