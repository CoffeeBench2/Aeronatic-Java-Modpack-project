package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "There's an update — get it from the store" screen, shown instead of the in-client updater when
 * {@link AeroConfig#MANUAL_UPDATE_ONLY} is set.
 *
 * <h2>Why this exists</h2>
 * The CurseForge <i>import</i> installs sit several pack versions behind, so the in-client updater
 * has to fetch hundreds of changed files. Every request is cache-busted (deliberately — that is what
 * defeats GitHub's 300s stale-cache bug), which means every one bypasses the CDN and hits origin.
 * Origin answers <b>429 Too Many Requests</b> part-way through a delta that size, and
 * {@code VersionCheck} treats any non-200 as ERROR — so the update fails with no message at all.
 * Diagnosed 2026-08-17 after CF players reported never receiving updates.
 *
 * <p>A mrpack player updating one version fetches a handful of files and never notices. Same
 * updater, same code, completely different outcome — which is exactly why this is a per-install
 * switch rather than a change to the updater itself.
 *
 * <p>Re-downloading the pack from the store is faster than a several-hundred-file delta anyway, and
 * it is something the CurseForge and Modrinth launchers already do natively.
 */
public class StoreUpdateScreen extends Screen {

    private final Screen parent;
    private final String latest;

    public StoreUpdateScreen(Screen parent, String latest) {
        super(Component.literal("Update Available"));
        this.parent = parent;
        this.latest = latest == null ? "" : latest;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 + 6;

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Open CurseForge"),
            b -> open(AeroConfig.CURSEFORGE_URL.get())
        ).bounds(cx - 155, y, 150, 20).build());

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Open Modrinth"),
            b -> open(AeroConfig.MODRINTH_URL.get())
        ).bounds(cx + 5, y, 150, 20).build());

        // Deliberately still allows playing. A stale pack usually still connects, and locking
        // someone out of a server they can reach is a worse outcome than an out-of-date warning.
        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Play anyway"),
            b -> this.minecraft.setScreen(this.parent)
        ).bounds(cx - 100, y + 30, 200, 20).build());
    }

    private void open(String url) {
        if (url == null || url.isBlank()) return;
        this.minecraft.setScreen(new ConfirmLinkScreen(ok -> {
            if (ok) Util.getPlatform().openUri(url);
            this.minecraft.setScreen(this);
        }, url, true));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 60;
        g.drawCenteredString(this.font, "§6§lUpdate Available", cx, y, 0xFFFFFF);
        if (!this.latest.isBlank()) {
            g.drawCenteredString(this.font,
                "§7Latest pack version: §f" + this.latest, cx, y + 16, 0xFFAAAAAA);
        }
        g.drawCenteredString(this.font,
            "§7This install updates through your launcher, not in-game.", cx, y + 36, 0xFFAAAAAA);
        g.drawCenteredString(this.font,
            "§7Grab the newest version from CurseForge or Modrinth,", cx, y + 48, 0xFFAAAAAA);
        g.drawCenteredString(this.font,
            "§7then reinstall the pack from your launcher.", cx, y + 60, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
