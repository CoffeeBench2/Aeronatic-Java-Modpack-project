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
    private static net.minecraft.client.gui.screens.Screen hidden;

    public static void maybeInit() {
        String addr = System.getProperty("aerocore.fittingHarness", "");
        if (addr.isBlank()) return;
        if (Boolean.getBoolean("aerocore.fittingManual")) {        // just log in (premium cookie) and hand over
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick(addr));
            return;
        }
        if (Boolean.getBoolean("aerocore.cosmeticsShots")) {
            cosmeticSteps();
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick(addr));
            return;
        }
        // The smallest GUI screen in common use: 1280x720 at auto scale = 426x240 (same as 1440p and 4K at auto).
        STEPS.add(new Step("settle", () -> { Minecraft.getInstance().options.guiScale().set(0);
            Minecraft.getInstance().getWindow().setWindowed(1280, 720); }, 60, null));
        STEPS.add(new Step("noon", () -> { cmd("time set noon"); cmd("weather clear"); cmd("gamerule doDaylightCycle false");
            cmd("clear @s"); cmd("give @s diamond 5"); }, 40, null));
        STEPS.add(new Step("inventory before", () -> logInventory("before"), 5, null));
        STEPS.add(new Step("open shop", () -> cmd("shop classic"), 80, "pw-01-trails"));
        STEPS.add(new Step("select brass", () -> click(18), 80, "pw-02-brass"));
        STEPS.add(new Step("select petal (inventory row)", () -> click(58), 100, "pw-03-petal"));
        STEPS.add(new Step("wear petal", () -> clickRight(54), 60, "pw-04-petal-worn"));
        STEPS.add(new Step("contrails tab", () -> click(1), 60, null));
        STEPS.add(new Step("select sakura", () -> click(36), 120, "pw-05-sakura"));
        STEPS.add(new Step("colours tab", () -> click(2), 60, "pw-06-colours"));
        STEPS.add(new Step("next page", () -> click(89), 60, "pw-07-colours-p2"));
        STEPS.add(new Step("gradients tab", () -> click(3), 40, null));
        STEPS.add(new Step("select a gradient", () -> click(27), 60, "pw-08-gradient"));
        STEPS.add(new Step("owned tab", () -> click(5), 60, "pw-09-owned"));
        STEPS.add(new Step("close", () -> click(8), 40, null));
        STEPS.add(new Step("inventory after", () -> logInventory("after"), 5, "pw-10-closed"));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick(addr));
    }

    /**
     * 1.4.0 cosmetics shots (-Daerocore.cosmeticsShots=true): a sky platform, then every wing standing, one in the
     * air, trails while walking, every aura, and the Wardrobe's new tabs. Screens are named cz-*.
     */
    private static void cosmeticSteps() {
        Minecraft mc = Minecraft.getInstance();
        STEPS.add(new Step("settle", () -> { }, 60, null));
        STEPS.add(new Step("stage", () -> {
            cmd("time set noon"); cmd("weather clear"); cmd("gamerule doDaylightCycle false");
            cmd("fill -6 150 -24 6 150 24 minecraft:polished_andesite");
            cmd("tp @s 0 151 -18 0 0");
            mc.options.hideGui = true;
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        }, 60, null));
        String[] trails = {"brass", "clockwork", "rainbow", "honey", "chocolate", "rosequartz", "ember", "kettle", "cloudwalk"};
        for (String t : trails) {
            STEPS.add(new Step("trail " + t, () -> {
                cmd("tp @s 0 151 -18 0 0"); cmd("cosmetics trail " + t);
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);   // the trail is between you and the camera
                turn(0);
                mc.options.keyUp.setDown(true);
            }, 34, "cz-trail-" + t));
            STEPS.add(new Step("stop", () -> mc.options.keyUp.setDown(false), 5, null));
        }
        STEPS.add(new Step("trail off", () -> { cmd("cosmetics trail off"); cmd("tp @s 0 151 0 200 10"); }, 30, null));
        String[] auras = {"halo", "propeller", "orbit", "raincloud", "spectrum", "updraft", "enchanted", "snowglobe"};
        for (String a : auras) {
            STEPS.add(new Step("aura " + a, () -> cmd("cosmetics aura " + a), 50, "cz-aura-" + a));
        }
        STEPS.add(new Step("dressed up", () -> { cmd("cosmetics aura spectrum"); cmd("cosmetics trail rainbow");
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT); turn(140); }, 40, "cz-combo"));
        STEPS.add(new Step("wardrobe", () -> { mc.options.hideGui = false; cmd("shop"); }, 60, "cz-wardrobe-open"));
        STEPS.add(new Step("auras tab", () -> { if (mc.screen instanceof WardrobeScreen w) w.selectTab(2); }, 30, "cz-wardrobe-auras"));
        STEPS.add(new Step("orbit", () -> { if (mc.screen instanceof WardrobeScreen w) w.select("aura:orbit"); }, 40, "cz-wardrobe-orbit"));
        STEPS.add(new Step("trails tab", () -> { if (mc.screen instanceof WardrobeScreen w) { w.selectTab(0); w.select("trail:honey"); } }, 40, "cz-wardrobe-honey"));
        for (String c : new String[]{"steam", "rainbow", "vapour"}) {
            STEPS.add(new Step("airship " + c, () -> { if (mc.screen instanceof WardrobeScreen w) { w.selectTab(1); w.select("contrail:" + c); } },
                50, "cz-ship-" + c));
        }
        STEPS.add(new Step("airship later", () -> { }, 7, "cz-ship-vapour-b"));        // moving parts: compare with -vapour
        STEPS.add(new Step("close", () -> mc.setScreen(null), 10, null));
        // Contrail try-ons from the ground: the demo wake streams away along the view (no Sable in a dev client).
        for (String c : new String[]{"rainbow", "aurora", "sparkler"}) {
            STEPS.add(new Step("try contrail " + c, () -> { cmd("shop tryon contrail:" + c);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); turn(200); }, 45, "cz-contrail-" + c));
        }
        STEPS.add(new Step("try steam", () -> { cmd("shop tryon contrail:steam"); }, 45, "cz-contrail-steam"));
        STEPS.add(new Step("try vapour", () -> { cmd("shop tryon contrail:vapour"); }, 45, "cz-contrail-vapour"));

    }

    /** Turn the player (and so the third-person camera) to a body yaw. */
    private static void turn(float yaw) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        p.setYRot(yaw);
        p.yBodyRot = yaw;
        p.yHeadRot = yaw;
        p.setXRot(12);
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

    private static void clickRight(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> s && mc.player != null && mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(s.getMenu().containerId, slot, 1, ClickType.PICKUP, mc.player);
        }
    }

    /** The client's own view of the player's inventory (the pack Wardrobe must give it back when it closes). */
    private static void logInventory(String when) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            var st = p.getInventory().getItem(i);
            if (!st.isEmpty()) { n++; sb.append(i).append('=').append(st.getCount()).append('x').append(st.getItem()).append(' '); }
        }
        com.mojang.logging.LogUtils.getLogger().info("[FittingHarness] inventory {}: {} stacks {}", when, n, sb);
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
            ServerData sd = new ServerData("dev", addr, ServerData.Type.OTHER);
            sd.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);    // accept the required pack, no prompt
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(addr), sd, false, transfer);
            return;
        }
        if (stage == 1 && mc.level != null && mc.player != null && ++inWorld > 60) {
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            stage = 2;
        }
        if (stage != 2 || Boolean.getBoolean("aerocore.fittingManual")) return;
        if (hidden != null) {                           // a frame has rendered without the window: the world shot
            Screenshot.grab(mc.gameDirectory, STEPS.get(idx).shot() + "-world.png", mc.getMainRenderTarget(), m -> { });
            mc.screen = hidden;                         // put it straight back; the server never saw it close
            hidden = null;
        } else {
            if (wait > 0) { wait--; return; }
            if (idx >= 0 && STEPS.get(idx).shot() != null) {
                Screenshot.grab(mc.gameDirectory, STEPS.get(idx).shot() + ".png", mc.getMainRenderTarget(), m -> { });
                dumpDisplays(mc, STEPS.get(idx).shot());
                if (mc.screen != null) {                // next frame without the window (field write: no close packet)
                    hidden = mc.screen;
                    mc.screen = null;
                    return;
                }
            }
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

    /** What the client really has: every display entity near the player with its synced data. */
    private static void dumpDisplays(Minecraft mc, String shot) {
        var log = com.mojang.logging.LogUtils.getLogger();
        int n = 0;
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.Display d) || e.distanceTo(mc.player) > 16) continue;
            n++;
            var vals = d.getEntityData().getNonDefaultValues();
            StringBuilder sb = new StringBuilder();
            if (vals != null) for (var v : vals) sb.append(v.id()).append('=').append(v.value()).append(' ');
            log.info("[FittingHarness] {} {} id={} at {} {}", shot, e.getType().toShortString(), e.getId(),
                String.format("%.2f,%.2f,%.2f", e.getX(), e.getY(), e.getZ()), sb);
        }
        log.info("[FittingHarness] {} displays={}", shot, n);
    }
}
