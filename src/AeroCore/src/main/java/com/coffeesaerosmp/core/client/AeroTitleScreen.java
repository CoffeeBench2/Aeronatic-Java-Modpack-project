package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.net.ServerLocator;
import com.coffeesaerosmp.core.screen.AdminSettingsScreen;
import com.coffeesaerosmp.core.screen.UpdateScreen;
import com.coffeesaerosmp.core.version.VersionCheck;
import net.minecraft.SharedConstants;
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
    private static final int LOGO_W = 1404, LOGO_H = 752;

    public AeroTitleScreen() {
        super(Component.literal("Coffees Aero SMP"));
    }

    @Override
    protected void init() {
        EarlyAssets.ensureRegistered(this.minecraft);
        // Poll the pack version once per session so the Join button can block a stale pack.
        VersionCheck.startAsync();

        boolean isAdmin = Minecraft.getInstance().getUser().getName()
            .equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get());

        int joinY = (int) (this.height * 0.62);
        // If the version check already knows the pack is stale, route to the update screen
        // instead of connecting (prevents the raw registry-mismatch wall). Fail-open otherwise.
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Join Coffees Aero SMP"),
                b -> {
                    if (VersionCheck.isOutdated()) {
                        this.minecraft.setScreen(new UpdateScreen(this));
                    } else {
                        connectToServer();
                    }
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

        // Vanilla-style detail lines, bottom-left (kept from the stock menu: MC + loader + mods).
        String line1 = "Minecraft " + SharedConstants.getCurrentVersion().getName()
            + " / NeoForge " + NeoForgeVersion.getVersion();
        String line2 = ModList.get().size() + " mods loaded";
        graphics.drawString(this.font, line1, 2, this.height - 20, 0xFFFFFF);
        graphics.drawString(this.font, line2, 2, this.height - 10, 0xFFFFFF);
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
