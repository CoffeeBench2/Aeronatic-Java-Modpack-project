package com.coffeesaerosmp.core.wardrobe;

import com.coffeesaerosmp.core.client.FlatWorlds;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * DEV ONLY. Visual test for the Wardrobe screen: with {@code -Daerocore.wardrobeHarness=<sample.json>} the client
 * creates a superflat world, opens the Wardrobe with the server-built sample payload, walks through the views
 * and takes a screenshot of each into {@code screenshots/}, then quits. Without the property this class does
 * nothing at all (players never have it set).
 */
public final class WardrobeDevHarness {

    private WardrobeDevHarness() {}

    private record Step(String shot, Consumer<WardrobeScreen> setup) {}

    private static WardrobeData data;
    private static final List<Step> STEPS = new ArrayList<>();
    private static int stage = 0, frames = 0, ticksInWorld = 0, stepIdx = -1;
    private static boolean worldRequested;

    public static void maybeInit() {
        String path = System.getProperty("aerocore.wardrobeHarness", "");
        if (path.isBlank()) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
            // Make the sample look like a real wardrobe: a Navigator who owns a couple of things and wears some.
            root.addProperty("rank", "NAVIGATOR");
            root.addProperty("rankLabel", "Navigator");
            root.addProperty("rankBadge", "✈");
            data = WardrobeData.parse(root.toString());
            List<WardrobeData.Item> fixed = new ArrayList<>();
            for (WardrobeData.Item i : data.items) {
                boolean own = i.id().equals("trail:petal") || i.id().equals("contrail:espresso");
                boolean rankOk = i.rank() != null && (i.rank().equals("Deckhand") || i.rank().equals("Navigator"));
                boolean eq = i.id().equals("trail:brass") || i.id().equals("colour:gold");
                fixed.add(new WardrobeData.Item(i.id(), i.label(), i.blurb(), i.cat(), i.price(), i.rank(), own,
                    own || rankOk, eq && (own || rankOk), i.icon(), i.fx(), i.nameRgb()));
            }
            data.items.clear();
            data.items.addAll(fixed);
            data.eqTrail = "trail:brass";
            data.eqNameStyle = "colour:gold";
            WardrobeData.Item gold = data.byId("colour:gold");
            if (gold != null) data.nameRgb = gold.nameRgb();
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().error("[WardrobeHarness] can't read {}: {}", path, e.toString());
            return;
        }
        STEPS.add(new Step("wardrobe-1-trail-petal", s -> { s.selectTab(0); s.select("trail:petal"); }));
        STEPS.add(new Step("wardrobe-2-trail-ember", s -> s.select("trail:ember")));
        STEPS.add(new Step("wardrobe-3-contrail-aurora", s -> { s.selectTab(1); s.select("contrail:aurora"); }));
        STEPS.add(new Step("wardrobe-4-contrail-sakura", s -> s.select("contrail:sakura")));
        STEPS.add(new Step("wardrobe-5-gradient-aurora", s -> { s.selectTab(3); s.select("gradient:aurora"); }));
        STEPS.add(new Step("wardrobe-6-colours", s -> { s.selectTab(2); s.select("colour:light_purple"); }));
        STEPS.add(new Step("wardrobe-7-buy-dialog", s -> { s.selectTab(0); s.select("trail:kettle"); s.showConfirm("trail:kettle"); }));
        STEPS.add(new Step("wardrobe-8-owned", s -> s.selectTab(5)));

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> frame());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (stage == 0 && mc.level == null && mc.screen != null && !worldRequested && mc.getOverlay() == null) {
            worldRequested = true;
            Screen parent = mc.screen;
            FlatWorlds.create(parent, "WardrobeHarness", GameType.CREATIVE, FlatWorlds.Kind.FLAT,
                FlatWorlds.Terrain.VANILLA, false);
            stage = 1;
        }
        if (stage == 1 && mc.level != null && mc.player != null) {
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);   // no WASD toast in the shots
            mc.getToasts().clear();
            if (++ticksInWorld > 60) {
                // Through the real network codec first: the bytes the server sends must become this screen.
                try {
                    var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                        mc.level.registryAccess());
                    String raw = Files.readString(Path.of(System.getProperty("aerocore.wardrobeHarness")));
                    WardrobePayload.STREAM_CODEC.encode(buf, new WardrobePayload(raw));
                    WardrobePayload back = WardrobePayload.STREAM_CODEC.decode(buf);
                    if (!back.json().equals(raw)) throw new IllegalStateException("codec changed the payload");
                    WardrobeClient.apply(back);
                    if (!(mc.screen instanceof WardrobeScreen)) throw new IllegalStateException("payload did not open the screen");
                    com.mojang.logging.LogUtils.getLogger().info("[WardrobeHarness] codec round trip OK ({} bytes), screen opened by payload",
                        buf.writerIndex());
                } catch (Exception ex) {
                    com.mojang.logging.LogUtils.getLogger().error("[WardrobeHarness] PAYLOAD PATH FAILED: {}", ex.toString());
                }
                mc.setScreen(new WardrobeScreen(data));
                stage = 2;
                nextStep();
            }
        }
    }

    private static void nextStep() {
        stepIdx++;
        frames = 0;
        Minecraft mc = Minecraft.getInstance();
        if (stepIdx >= STEPS.size()) {
            com.mojang.logging.LogUtils.getLogger().info("[WardrobeHarness] done, {} screenshots", STEPS.size());
            mc.stop();
            return;
        }
        if (mc.screen instanceof WardrobeScreen ws) STEPS.get(stepIdx).setup().accept(ws);
    }

    private static void frame() {
        if (stage != 2 || stepIdx < 0 || stepIdx >= STEPS.size()) return;
        // Let particles build up and the model turn a little before the shot.
        if (++frames == 240) {                         // ~4 s: trails and wakes reach their full length
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, STEPS.get(stepIdx).shot() + ".png", mc.getMainRenderTarget(), msg -> { });
            nextStep();
        }
    }
}
