package com.coffeesaerosmp.core.wardrobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.inventory.ClickType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * DEV ONLY. With {@code -Daerocore.fittingHarness=host:port} the client joins a dev auth server, registers, opens the
 * CHEST shop ({@code /shop classic}, the vanilla-client path) and clicks through it like a player, screenshotting
 * the server-side fitting room (mannequin, nameplate, effects, mini airship) beside the window. Then quits.
 * Without the property this class does nothing.
 */
public final class FittingRoomHarness {

    private FittingRoomHarness() {}

    /** One scripted action, then wait {@code ticks}, then optionally screenshot. */
    private record Step(String what, Runnable action, int ticks, String shot) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int idx = -1, wait = 0, stage = 0, inWorld = 0;

    public static void maybeInit() {
        String addr = System.getProperty("aerocore.fittingHarness", "");
        if (addr.isBlank()) return;
        STEPS.add(new Step("settle", () -> { }, 60, null));
        STEPS.add(new Step("noon", () -> { cmd("time set noon"); cmd("weather clear"); cmd("gamerule doDaylightCycle false"); }, 40, null));
        STEPS.add(new Step("open shop", () -> cmd("shop classic"), 60, "fr-01-main"));
        STEPS.add(new Step("trails", () -> click(20), 30, null));
        STEPS.add(new Step("cloudwalk", () -> click(10), 100, "fr-02-cloudwalk"));
        STEPS.add(new Step("brass", () -> click(11), 100, "fr-03-brass"));
        STEPS.add(new Step("clockwork", () -> click(12), 120, "fr-04-clockwork"));
        STEPS.add(new Step("espresso", () -> click(13), 100, "fr-05-espresso"));
        STEPS.add(new Step("kettle", () -> click(14), 120, "fr-06-kettle"));
        STEPS.add(new Step("petal", () -> click(15), 120, "fr-07-petal"));
        STEPS.add(new Step("ember", () -> click(16), 100, "fr-08-ember"));
        STEPS.add(new Step("snow", () -> click(19), 120, "fr-09-snow"));
        STEPS.add(new Step("back", () -> click(45), 30, null));
        STEPS.add(new Step("contrails", () -> click(21), 30, null));
        STEPS.add(new Step("vapour", () -> click(10), 140, "fr-10-vapour"));
        STEPS.add(new Step("steam", () -> click(11), 140, "fr-11-steam"));
        STEPS.add(new Step("espresso wake", () -> click(12), 140, "fr-12-espresso-wake"));
        STEPS.add(new Step("sakura", () -> click(13), 140, "fr-13-sakura"));
        STEPS.add(new Step("aurora", () -> click(14), 160, "fr-14-aurora"));
        STEPS.add(new Step("back", () -> click(45), 30, null));
        STEPS.add(new Step("gradients", () -> click(23), 30, null));
        STEPS.add(new Step("aurora gradient", () -> click(20), 60, "fr-15-gradient"));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick(addr));
    }

    /** Same layout as CoffeesAeroAuth's CookieAuth.sign: v1, premium, uuid, expiry, nonce, name, HMAC-SHA256. */
    static byte[] devCookie(String hexSecret, String name) {
        try {
            byte[] key = java.util.HexFormat.of().parseHex(hexSecret.trim());
            byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("DevPremium:" + name).getBytes());
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1 + 1 + 16 + 8 + 16 + 2 + n.length);
            b.put((byte) 1).put((byte) 1).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
             .putLong(System.currentTimeMillis() + 600_000L);
            byte[] nonce = new byte[16];
            new java.security.SecureRandom().nextBytes(nonce);
            b.put(nonce).putShort((short) n.length).put(n);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] body = b.array();
            byte[] sig = mac.doFinal(body);
            byte[] out = new byte[body.length + sig.length];
            System.arraycopy(body, 0, out, 0, body.length);
            System.arraycopy(sig, 0, out, body.length, sig.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void cmd(String c) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.connection.sendCommand(c);
    }

    private static void click(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> s && mc.player != null && mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(s.getMenu().containerId, slot, 0, ClickType.PICKUP, mc.player);
        } else {
            com.mojang.logging.LogUtils.getLogger().warn("[FittingHarness] no container open to click slot {}", slot);
        }
    }

    private static void tick(String addr) {
        Minecraft mc = Minecraft.getInstance();
        if (stage == 0 && mc.level == null && mc.screen != null && mc.getOverlay() == null) {
            stage = 1;
            // Arrive the way the gate sends players: carrying a signed PREMIUM auth cookie (dev secret only).
            net.minecraft.client.multiplayer.TransferState transfer = null;
            String secret = System.getProperty("aerocore.fittingSecret", "");
            if (!secret.isBlank()) {
                byte[] cookie = devCookie(secret, mc.getUser().getName());
                transfer = new net.minecraft.client.multiplayer.TransferState(
                    java.util.Map.of(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aerosmp", "auth"), cookie));
            }
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(addr),
                new ServerData("dev", addr, ServerData.Type.OTHER), false, transfer);
            return;
        }
        if (stage == 1 && mc.level != null && mc.player != null && ++inWorld > 60) {
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            stage = 2;
        }
        if (stage != 2) return;
        if (wait > 0) { wait--; return; }
        if (idx >= 0 && STEPS.get(idx).shot() != null) {
            Screenshot.grab(mc.gameDirectory, STEPS.get(idx).shot() + ".png", mc.getMainRenderTarget(), m -> { });
        }
        idx++;
        if (idx >= STEPS.size()) {
            com.mojang.logging.LogUtils.getLogger().info("[FittingHarness] done");
            mc.stop();
            return;
        }
        Step s = STEPS.get(idx);
        com.mojang.logging.LogUtils.getLogger().info("[FittingHarness] step {}: {}", idx, s.what());
        s.action().run();
        wait = s.ticks();
    }
}
