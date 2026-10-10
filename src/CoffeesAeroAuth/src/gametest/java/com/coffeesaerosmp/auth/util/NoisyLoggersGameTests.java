package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * The noisy loggers have their OWN log4j rule at INFO on a booted server. Checked on the configuration,
 * not with isDebugEnabled(): the dev server's root logger is INFO, so isDebugEnabled() would be false
 * here with or without the fix (live's debug.log runs at DEBUG). A sibling logger must get no rule.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class NoisyLoggersGameTests {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aptSpawnHasItsOwnInfoRuleAndNothingElseIsTouched(GameTestHelper h) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        for (String name : NoisyLoggers.QUIET) {
            LoggerConfig cfg = ctx.getConfiguration().getLoggerConfig(name);
            if (!name.equals(cfg.getName())) h.fail(name + " has no rule of its own (inherits " + cfg.getName() + ")");
            if (cfg.getLevel() != Level.INFO) h.fail(name + " rule is " + cfg.getLevel() + ", expected INFO");
        }
        // Control: the change is per logger — a sibling of the same mod gets no rule of its own.
        String sibling = "Adaptive Performance Tweaks.distance";
        if (sibling.equals(ctx.getConfiguration().getLoggerConfig(sibling).getName()))
            h.fail("control: a sibling logger got its own rule — the change is too broad");
        h.succeed();
    }
}
