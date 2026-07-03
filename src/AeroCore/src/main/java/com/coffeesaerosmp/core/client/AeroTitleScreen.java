package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.net.ServerLocator;
import com.coffeesaerosmp.core.screen.AdminSettingsScreen;
import com.coffeesaerosmp.core.screen.UpdateScreen;
import com.coffeesaerosmp.core.version.VersionCheck;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * The pack's native title screen — replaces both the vanilla menu and the old FancyMenu layout
 * (which was config-heavy and fragile). Steampunk look: vanilla panorama behind the pack's
 * "COFFEE'S CREATE AERONAUTICS SMP" logo with two slowly counter-rotating Create cogwheels, one
 * primary Join button, and the usual Options/Quit. Admins get corner buttons for Admin Settings and
 * the untouched vanilla menu (see {@link TitleReplacer}).
 */
public class AeroTitleScreen extends Screen {

    private static final ResourceLocation LOGO =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/title_logo.png");
    private static final ResourceLocation COG =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/cogwheel.png");
    private static final int LOGO_W = 1404, LOGO_H = 752;
    private static final int COG_W = 464, COG_H = 450;

    private static final CubeMap PANORAMA_CUBE =
        new CubeMap(ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama"));
    private final PanoramaRenderer panorama = new PanoramaRenderer(PANORAMA_CUBE);

    public AeroTitleScreen() {
        super(Component.literal("Coffees Aero SMP"));
    }

    @Override
    protected void init() {
        // Poll the pack version once per session so the Join button can block a stale pack.
        VersionCheck.startAsync();

        boolean isAdmin = Minecraft.getInstance().getUser().getName()
            .equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get());

        int joinY = (int) (this.height * 0.58);
        // If the version check already knows the pack is stale, route to the update screen
        // instead of connecting (prevents the raw registry-mismatch wall). Fail-open otherwise.
        this.addRenderableWidget(Button.builder(
                Component.literal("⚙ Join Coffees Aero SMP"),
                b -> {
                    if (VersionCheck.isOutdated()) {
                        this.minecraft.setScreen(new UpdateScreen(this));
                    } else {
                        connectToServer();
                    }
                }
        ).bounds(this.width / 2 - 100, joinY, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("menu.options"),
                b -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options))
        ).bounds(this.width / 2 - 100, joinY + 24, 98, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("menu.quit"),
                b -> this.minecraft.stop()
        ).bounds(this.width / 2 + 2, joinY + 24, 98, 20).build());

        if (isAdmin) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("Admin Settings"),
                    b -> this.minecraft.setScreen(new AdminSettingsScreen(this))
            ).bounds(5, this.height - 50, 90, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("◄ Vanilla Menu"),
                    b -> {
                        TitleReplacer.useCustom = false;
                        this.minecraft.setScreen(new TitleScreen());
                    }
            ).bounds(5, this.height - 25, 90, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.panorama.render(graphics, this.width, this.height, 1.0F, partialTick);
        graphics.fillGradient(0, 0, this.width, this.height, 0x33000000, 0x66000000);

        // Two slowly counter-rotating Create cogwheels tucked into the corners, under everything.
        float angle = (Util.getMillis() % 36000L) / 100.0F;   // one turn / 6s
        int cogBig = (int) (this.height * 0.55);
        drawCog(graphics, -cogBig / 4, this.height - cogBig / 3, cogBig, angle);
        int cogSmall = (int) (this.height * 0.32);
        drawCog(graphics, this.width - cogSmall * 3 / 4, -cogSmall / 4, cogSmall, -angle * 1.5F);

        // Pack logo, centered in the upper part of the screen.
        int logoH = (int) (this.height * 0.42);
        int logoW = logoH * LOGO_W / LOGO_H;
        if (logoW > (int) (this.width * 0.9)) {
            logoW = (int) (this.width * 0.9);
            logoH = logoW * LOGO_H / LOGO_W;
        }
        int logoX = (this.width - logoW) / 2;
        int logoY = (int) (this.height * 0.05);
        graphics.pose().pushPose();
        graphics.pose().translate(logoX, logoY, 0);
        graphics.pose().scale(logoW / (float) LOGO_W, logoH / (float) LOGO_H, 1.0F);
        graphics.blit(LOGO, 0, 0, 0.0F, 0.0F, LOGO_W, LOGO_H, LOGO_W, LOGO_H);
        graphics.pose().popPose();

        // Version pill, top-left (same styling the old FancyMenu-era overlay used).
        String label = "Coffees Aero SMP v" + AeroConfig.PACK_VERSION.get();
        int w = this.font.width(label);
        graphics.fill(1, 1, 5 + w, 12, 0x90000000);
        graphics.drawString(this.font, label, 3, 3, 0xFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the cogwheel spinning around its center; x/y are the top-left of the un-rotated cog. */
    private void drawCog(GuiGraphics graphics, int x, int y, int size, float angleDeg) {
        graphics.pose().pushPose();
        graphics.pose().translate(x + size / 2.0F, y + size / 2.0F, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
        float s = size / (float) COG_W;
        graphics.pose().scale(s, s, 1.0F);
        graphics.blit(COG, -COG_W / 2, -COG_H / 2, 0.0F, 0.0F, COG_W, COG_H, COG_W, COG_H);
        graphics.pose().popPose();
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
