package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.net.ServerLocator;
import com.coffeesaerosmp.core.screen.AdminSettingsScreen;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import org.lwjgl.glfw.GLFW;

/**
 * The pack's native title screen (replaced FancyMenu in 1.3.0). The airship "loading screen" art is
 * the full-bleed background (cover-scaled, NO vanilla menu blur — we override renderBackground);
 * the pack logo sits top-center, sized so it never overlaps the airship in the art's center band.
 * One primary Join button + Options/Quit (steampunk {@link AeroButton}s), admin corner buttons,
 * pack version top-left, and the vanilla-style MC/NeoForge/mod-count details bottom-left.
 */
public class AeroTitleScreen extends Screen {

    private static final ResourceLocation LOGO =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/title_logo.png");
    private static final int LOGO_W = 1024, LOGO_H = 548;

    private static final int ANNOUNCE_W = 74;
    private int announceX, announceY;

    public AeroTitleScreen() {
        super(Component.literal("Coffees Aero SMP"));
    }

    @Override
    protected void init() {
        EarlyAssets.ensureRegistered(this.minecraft);
        // Poll the pack version once per session so the Join button can block a stale pack.
        // No-op on the CurseForge build (updater absent) — the CF app handles pack updates.
        com.coffeesaerosmp.core.UpdaterBridge.startCheck();

        boolean isAdmin = Minecraft.getInstance().getUser().getName()
            .equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get());

        int joinY = (int) (this.height * 0.62);
        // If the version check already knows the pack is stale, route to the update screen
        // instead of connecting (prevents the raw registry-mismatch wall). Fail-open otherwise.
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Join Coffees Aero SMP"),
                b -> {
                    net.minecraft.client.gui.screens.Screen update =
                        com.coffeesaerosmp.core.UpdaterBridge.isPackOutdated()
                            ? com.coffeesaerosmp.core.UpdaterBridge.openUpdateScreen(this) : null;
                    if (update != null) this.minecraft.setScreen(update);
                    else connectToServer();
                }
        ).bounds(this.width / 2 - 100, joinY, 200, 20).build());

        this.addRenderableWidget(AeroButton.aero(
                Component.translatable("menu.options"),
                b -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options))
        ).bounds(this.width / 2 - 100, joinY + 26, 98, 20).build());

        this.addRenderableWidget(AeroButton.aero(
                Component.translatable("menu.quit"),
                b -> this.minecraft.stop()
        ).bounds(this.width / 2 + 2, joinY + 26, 98, 20).build());

        // Announcements (pack changelog) — small button in the TOP-RIGHT corner. Opening it plays the
        // parchment-scroll animation. A NEW badge is drawn beside it in render() until first opened.
        this.announceX = this.width - ANNOUNCE_W - 5;
        this.announceY = 5;
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("News"),
                b -> this.minecraft.setScreen(new com.coffeesaerosmp.core.screen.AnnouncementsScreen(this))
        ).bounds(announceX, announceY, ANNOUNCE_W, 20).build());

        if (isAdmin) {
            this.addRenderableWidget(AeroButton.aero(
                    Component.literal("Admin Settings"),
                    b -> this.minecraft.setScreen(new AdminSettingsScreen(this))
            ).bounds(5, this.height - 50, 90, 20).build());

            this.addRenderableWidget(AeroButton.aero(
                    Component.literal("◄ Vanilla Menu"),
                    b -> {
                        TitleReplacer.useCustom = false;
                        this.minecraft.setScreen(new TitleScreen());
                    }
            ).bounds(5, this.height - 25, 90, 20).build());
        }
    }

    /** Our own background — overriding this is what keeps vanilla's menu BLUR pass away. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Cover-scale the airship art (crop overflow, never stretch).
        float scale = Math.max(this.width / (float) EarlyAssets.BG_W, this.height / (float) EarlyAssets.BG_H);
        int drawW = (int) (EarlyAssets.BG_W * scale);
        int drawH = (int) (EarlyAssets.BG_H * scale);
        int x = (this.width - drawW) / 2;
        int y = (this.height - drawH) / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(EarlyAssets.BG, 0, 0, 0.0F, 0.0F,
            EarlyAssets.BG_W, EarlyAssets.BG_H, EarlyAssets.BG_W, EarlyAssets.BG_H);
        graphics.pose().popPose();
        // Soft dark band at the bottom so buttons + info lines stay readable over the landscape.
        graphics.fillGradient(0, (int) (this.height * 0.55), this.width, this.height, 0x00000000, 0x90000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);   // background (ours) + widgets

        // Pack logo, top-center. Height capped at 26% of the screen: the airship in the art starts
        // around 40% down, so the logo (ending ~30%) always clears it.
        int logoH = (int) (this.height * 0.26);
        int logoW = logoH * LOGO_W / LOGO_H;
        if (logoW > (int) (this.width * 0.8)) {
            logoW = (int) (this.width * 0.8);
            logoH = logoW * LOGO_H / LOGO_W;
        }
        int logoX = (this.width - logoW) / 2;
        int logoY = (int) (this.height * 0.04);
        graphics.pose().pushPose();
        graphics.pose().translate(logoX, logoY, 0);
        graphics.pose().scale(logoW / (float) LOGO_W, logoH / (float) LOGO_H, 1.0F);
        graphics.blit(LOGO, 0, 0, 0.0F, 0.0F, LOGO_W, LOGO_H, LOGO_W, LOGO_H);
        graphics.pose().popPose();

        // Pack version pill, top-left.
        String label = "Coffees Aero SMP v" + AeroConfig.PACK_VERSION.get();
        int w = this.font.width(label);
        graphics.fill(1, 1, 5 + w, 12, 0x90000000);
        graphics.drawString(this.font, label, 3, 3, 0xFFFFFF);

        // "NEW" badge on the Announcements button until the player opens it once (per pack version).
        if (com.coffeesaerosmp.core.announce.AnnouncementState.hasUnseen()) {
            String badge = "NEW";
            int bw = this.font.width(badge) + 6;
            int bx = announceX - bw - 3;               // pulsing pill just left of the corner button
            int by = announceY + 5;
            float pulse = 0.55F + 0.45F * (float) Math.sin(Util.getMillis() / 260.0);
            int alpha = (int) (pulse * 255) << 24;
            graphics.fill(bx, by, bx + bw, by + 11, alpha | 0x00C0302A);   // pulsing red pill
            graphics.fill(bx, by, bx + bw, by + 1, 0xFFF0C05A);            // brass top edge
            graphics.drawString(this.font, badge, bx + 3, by + 2, 0xFFFFFFFF, false);
        }

        // Vanilla-style detail lines, bottom-left (kept from the stock menu: MC + loader + mods).
        String line1 = "Minecraft " + SharedConstants.getCurrentVersion().getName()
            + " / NeoForge " + NeoForgeVersion.getVersion();
        String line2 = ModList.get().size() + " mods loaded";
        graphics.drawString(this.font, line1, 2, this.height - 20, 0xFFFFFF);
        graphics.drawString(this.font, line2, 2, this.height - 10, 0xFFFFFF);
    }

    /**
     * Analog Audio's Lavaplayer install prompt hooks ScreenEvent.Init.Post on the VANILLA
     * TitleScreen — which this screen replaces, so on installs without the pre-baked player
     * (the CurseForge zip strips {@code .analogaudio/}: CF blacklists the shaded lavaplayer
     * YouTube classes) the prompt never fired and radio audio was silently dead. Re-fire the
     * mod's OWN welcome screen from here instead. Reflection-only (no compile dep), mirrors the
     * mod's exact gate (config {@code lavaplayerWelcomeScreen} && {@code isMissing()}), so a
     * player's "Don't install" choice and the bundled-player installs both stay prompt-free.
     */
    private static boolean audioPromptChecked = false;

    @Override
    public void tick() {
        super.tick();
        if (audioPromptChecked) return;
        audioPromptChecked = true;
        if (ModList.get().isLoaded("analogaudio")) {
            // Mod present (Modrinth/GitHub build): re-fire the mod's OWN Lavaplayer install prompt,
            // which hooks the vanilla TitleScreen we replaced and so never fires on its own here.
            fireLavaplayerWelcomeIfNeeded();
        } else if (!AeroConfig.ANALOG_AUDIO_PROMPT_SHOWN.get()) {
            // Mod absent (the CurseForge-website zip strips it). Analog Audio registers a required
            // network channel, so without it the player can't join the server and radios/cassettes
            // are dead — offer a one-click manual-install path. Invisible on builds that bundle it.
            this.minecraft.setScreen(
                new com.coffeesaerosmp.core.screen.AnalogAudioSetupScreen(this));
        }
    }

    private void fireLavaplayerWelcomeIfNeeded() {
        try {
            Class<?> cfg = Class.forName("com.palm1.analogaudio.config.ModConfig$Client");
            if (!cfg.getField("lavaplayerWelcomeScreen").getBoolean(null)) return;
            Class<?> loader = Class.forName("com.palm1.analogaudio.client.audio.lavaplayer.LavaplayerLoader");
            if (!(Boolean) loader.getMethod("isMissing").invoke(null)) return;
            Class<?> welcome = Class.forName("com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen");
            this.minecraft.setScreen((Screen) welcome.getConstructor(Screen.class).newInstance(this));
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("CoffeesAeroCore-Menu")
                .warn("Analog Audio Lavaplayer prompt hook failed (non-fatal): {}", t.toString());
        }
    }

    /** Secret access combo: Ctrl + Alt + M opens the vanilla Multiplayer screen from any account. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean alt  = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
        if (ctrl && alt && keyCode == GLFW.GLFW_KEY_M) {
            this.minecraft.setScreen(new JoinMultiplayerScreen(this));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void connectToServer() {
        Minecraft mc = Minecraft.getInstance();
        String ip = ServerLocator.resolve();   // managed (encrypted, in-memory) or admin override
        ServerAddress address = ServerAddress.parseString(ip);
        ServerData data = new ServerData("Coffees Aero SMP", ip, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, mc, address, data, false, null);
    }
}
