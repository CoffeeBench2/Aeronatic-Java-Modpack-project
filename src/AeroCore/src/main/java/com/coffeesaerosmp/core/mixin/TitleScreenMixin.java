package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.screen.AdminSettingsScreen;
import com.coffeesaerosmp.core.screen.UpdateScreen;
import com.coffeesaerosmp.core.version.VersionCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private static boolean customMenuEnabled = true;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        // Poll the pack version once per session so the Join button can block a stale pack.
        VersionCheck.startAsync();

        boolean isAdmin = Minecraft.getInstance().getUser().getName()
                .equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get());

        if (!customMenuEnabled) {
            // Vanilla mode: only show a small "► Custom" toggle for admin in bottom-left
            if (isAdmin) {
                this.addRenderableWidget(Button.builder(
                        Component.literal("► Custom"),
                        b -> {
                            customMenuEnabled = true;
                            Minecraft.getInstance().setScreen(new TitleScreen());
                        }
                ).bounds(5, this.height - 25, 75, 20).build());
            }
            return;
        }

        // Custom mode: strip every button except Options and Quit
        String optionsLabel = Component.translatable("menu.options").getString();
        String quitLabel    = Component.translatable("menu.quit").getString();

        new ArrayList<>(this.children()).forEach(child -> {
            if (!(child instanceof Button btn)) return;
            String label = btn.getMessage().getString();
            if (!label.equals(optionsLabel) && !label.equals(quitLabel)) {
                this.removeWidget(btn);
            }
        });

        // "Join Coffees Aero SMP" as the single main action button.
        // If the version check already knows the pack is stale, route to the update screen
        // instead of connecting (prevents the raw registry-mismatch wall). Fail-open otherwise.
        this.addRenderableWidget(Button.builder(
                Component.literal("Join Coffees Aero SMP"),
                b -> {
                    if (VersionCheck.isOutdated()) {
                        Minecraft.getInstance().setScreen(new UpdateScreen(this));
                    } else {
                        connectToServer();
                    }
                }
        ).bounds(this.width / 2 - 100, this.height / 4 + 48, 200, 20).build());

        // Admin-only corner buttons (bottom-left stack)
        if (isAdmin) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("Admin Settings"),
                    b -> Minecraft.getInstance().setScreen(new AdminSettingsScreen(this))
            ).bounds(5, this.height - 50, 90, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("◄ Vanilla Menu"),
                    b -> {
                        customMenuEnabled = false;
                        Minecraft.getInstance().setScreen(new TitleScreen());
                    }
            ).bounds(5, this.height - 25, 90, 20).build());
        }
    }

    /** Stamp the bundled pack version in the TOP-LEFT corner, styled like vanilla's own version
     *  line (same font/shadow), so it's easy to tell at a glance which pack build is installed.
     *  Kept clear of NeoForge's mod-loader/version branding, which renders in the bottom-left. */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        String label = "Coffees Aero SMP v" + AeroConfig.PACK_VERSION.get();
        graphics.drawString(this.font, label, 2, 2, 0xFFFFFF);
    }

    private void connectToServer() {
        Minecraft mc = Minecraft.getInstance();
        String ip = AeroConfig.SERVER_IP.get();
        ServerAddress address = ServerAddress.parseString(ip);
        ServerData data = new ServerData("Coffees Aero SMP", ip, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, mc, address, data, false, null);
    }
}
