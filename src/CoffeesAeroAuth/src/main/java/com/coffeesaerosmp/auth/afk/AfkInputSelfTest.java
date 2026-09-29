package com.coffeesaerosmp.auth.afk;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

/**
 * Proves at BOOT that {@code AfkInputMixin} applied.
 *
 * <p>🔴 {@code ServerGamePacketListenerImpl} is only class-loaded when the first player connects, so a
 * clean boot says nothing about a mixin that targets it — and with {@code defaultRequire = 1} a broken
 * inject would then blow up on the first JOIN of the live server instead of at boot (vault:
 * mixin-boot-test-needs-class-loaded). Loading it here moves that failure to the boot log, where a
 * boot test sees it, and the handler count below proves the hooks are really in the class.
 */
public final class AfkInputSelfTest {

    private AfkInputSelfTest() {}

    private static final String[] HOOKS = {"afkHotbar", "afkInventory", "afkSwing", "afkSneakSprint", "afkSteer"};

    public static void run() {
        try {
            Class<?> c = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            int found = 0;
            for (String hook : HOOKS) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().endsWith(hook)) { found++; break; }
                }
            }
            if (found == HOOKS.length) {
                CoffeesAeroAuth.LOGGER.info("[AFK] input hooks applied ({}/{}).", found, HOOKS.length);
            } else {
                CoffeesAeroAuth.LOGGER.error("[AFK] input hooks MISSING: {}/{} applied — hotbar/inventory/"
                    + "swing/sneak/steering will not count as activity.", found, HOOKS.length);
            }
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.error("[AFK] input hook self-test failed", t);
        }
    }
}
