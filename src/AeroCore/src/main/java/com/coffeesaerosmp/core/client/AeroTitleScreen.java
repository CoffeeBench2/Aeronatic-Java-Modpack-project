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
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ModListScreen;
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
    /**
     * Native size of the Season 2 title art. MUST match the PNG exactly — it is both the blit source
     * rectangle and the divisor for the scale below, so a stale value crops the art AND scales it
     * wrong at the same time. Re-measure on every art change; the 2026-09-03 banner went 1405x752
     * (1.87:1) to 1280x490 (2.61:1), which is a different shape, not just a different size.
     *
     * <p>The art is edge-to-edge with zero transparent padding (verified: the alpha bounding box is
     * the full canvas), so centering the canvas centers what the player actually sees. If a future
     * version is exported with padding, trim it rather than compensating with offsets here.
     */
    private static final int LOGO_W = 2048, LOGO_H = 672;   // Season 2 art (AERO SHIP)

    /** 16px pixel-art Discord mark for the invite tile. Native size — blitted 1:1 so it stays crisp. */
    private static final ResourceLocation DISCORD_ICON =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/discord_icon.png");

    /** 16px pack logo for the small config tile. Native size — blitted 1:1 so it stays crisp. */
    private static final ResourceLocation CONFIG_ICON =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/logo_icon.png");

    private static final int ANNOUNCE_W = 74;
    private int announceX, announceY;

    /** Manual update check — second slot of the reserved top-right column. */
    private int updateY;
    private AeroButton updateButton;
    /** Wall-clock ms of the last manual check; throttles button-mashing into the CDN. */
    private static long lastManualCheck;
    /** Wall-clock ms until the transient "Up to date ✓" label reverts to "Updates". */
    private static long upToDateUntil;
    private int joinY;

    public AeroTitleScreen() {
        super(Component.literal("Coffees Aero SMP"));
    }

    @Override
    protected void init() {
        EarlyAssets.ensureRegistered(this.minecraft);
        // Poll the pack version once per session so the Join button can block a stale pack.
        // No-op on the CurseForge build (updater absent) — the CF app handles pack updates.
        com.coffeesaerosmp.core.UpdaterBridge.startCheck();
        // Live player count. Self-throttling and on a daemon thread, so calling it from init() —
        // which fires again on every window resize — costs nothing.
        com.coffeesaerosmp.core.net.GatePing.refresh();

        boolean isAdmin = Minecraft.getInstance().getUser().getName()
            .equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get());

        this.joinY = (int) (this.height * 0.62);
        int joinY = this.joinY;
        // If the version check already knows the pack is stale, route to the update screen
        // instead of connecting (prevents the raw registry-mismatch wall). Fail-open otherwise.
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Join Coffees Aero SMP"),
                b -> {
                    if (com.coffeesaerosmp.core.UpdaterBridge.isPackOutdated()) {
                        // manualUpdateOnly installs (the CurseForge import) must NOT run the
                        // in-client downloader — see StoreUpdateScreen for why it fails there.
                        this.minecraft.setScreen(openUpdateTarget());
                        return;
                    }
                    connectToServer();
                }
        ).bounds(this.width / 2 - 100, joinY, 200, 20).build());

        // Singleplayer (Normal or Flat). Full width because it is a primary destination, not a
        // settings shortcut — this is where people test contraptions before building them live.
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Singleplayer"),
                b -> this.minecraft.setScreen(new com.coffeesaerosmp.core.screen.SingleplayerScreen(this))
        ).bounds(this.width / 2 - 100, joinY + 26, 200, 20).build());

        // Mods sits directly under Singleplayer — it's a destination, not a settings shortcut,
        // so it belongs with the primary buttons rather than below Options/Quit.
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Mods"),
                b -> this.minecraft.setScreen(new ModListScreen(this))
        ).bounds(this.width / 2 - 100, joinY + 52, 200, 20).build());

        this.addRenderableWidget(AeroButton.aero(
                Component.translatable("menu.options"),
                b -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options))
        ).bounds(this.width / 2 - 100, joinY + 78, 98, 20).build());

        this.addRenderableWidget(AeroButton.aero(
                Component.translatable("menu.quit"),
                b -> this.minecraft.stop()
        ).bounds(this.width / 2 + 2, joinY + 78, 98, 20).build());

        // Small logo tile beside the Options/Quit row — same shape and placement as the little
        // spark / Create-goggles buttons on the pause menu. Opens the pack's own settings screen
        // (client-mod on/off switches), which is more useful to a player than the raw config UI.
        AeroButton cfg = AeroButton.aero(
                Component.literal("Aero Settings"),
                b -> this.minecraft.setScreen(
                        new com.coffeesaerosmp.core.screen.AeroSettingsScreen(this))
        ).bounds(this.width / 2 + 104, joinY + 78, 20, 20)
         .icon(CONFIG_ICON, 16)
         .build();
        // The icon alone is not self-explanatory, so give it a hover label.
        cfg.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Coffees Aero SMP settings")));
        this.addRenderableWidget(cfg);

        // Announcements (pack changelog) — small button in the TOP-RIGHT corner. Opening it plays the
        // parchment-scroll animation. A NEW badge is drawn beside it in render() until first opened.
        this.announceX = this.width - ANNOUNCE_W - 5;
        this.announceY = 5;
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("News"),
                b -> this.minecraft.setScreen(new com.coffeesaerosmp.core.screen.AnnouncementsScreen(this))
        ).bounds(announceX, announceY, ANNOUNCE_W, 20).build());

        // ── Manual update check ───────────────────────────────────────────────────────────
        // Directly under News, in the RESERVED top-right utility column. Deliberately NOT in the
        // centre stack: every primary button there is placed at joinY + n*26, so adding one later
        // shifts the whole column — anything anchored to the screen edge is immune to that. Both
        // coordinates derive from width/height and init() re-runs on resize, so it re-anchors
        // instead of drifting off-screen.
        //
        // ⚠ THE TOP-RIGHT IS NOW A TWO-SLOT STRIP: News at y=5, Updates at y=29. A third corner
        // button starts at y=53. Minecraft widgets do not z-fight — two widgets sharing bounds BOTH
        // take clicks and the later one wins — so this is layout discipline, not an ordering flag.
        //
        // Hidden entirely on a Core that cannot re-check (the CurseForge build has no updater, and
        // the CF app is the updater there), rather than shown as a button that does nothing.
        // A CURSOR, not a fixed offset. The CurseForge build has no updater, so hardcoding
        // Discord to the third slot would leave a hole where Updates should be. Advancing a
        // cursor keeps the column tight in both builds and means the next corner button added
        // here needs no arithmetic.
        int cornerY = this.announceY + 24;

        if (com.coffeesaerosmp.core.UpdaterBridge.canRecheck()) {
            this.updateY = cornerY;
            this.updateButton = AeroButton.aero(
                    updateLabel(),
                    b -> onUpdatePressed()
            ).bounds(this.announceX, this.updateY, ANNOUNCE_W, 20).build();
            this.updateButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Check for pack updates now")));
            this.addRenderableWidget(this.updateButton);
            cornerY += 24;
        }

        // Discord — a 20x20 icon tile MIRRORING the Aero Settings gear on the other side of the
        // Options/Quit row, so the row reads as a symmetric pair rather than a lone gear. That
        // slot is free in every build, unlike the corner column whose contents differ between the
        // Modrinth and CurseForge jars.
        //
        // Hidden when the invite is blank: a button that goes nowhere is worse than no button, and
        // the CurseForge build is the one most likely to be run by someone who has never seen the
        // Discord at all.
        String discord = AeroConfig.DISCORD_URL.get();
        if (discord != null && !discord.isBlank()) {
            AeroButton dc = AeroButton.aero(
                    Component.literal("Discord"),
                    // ConfirmLinkScreen, not a bare openUri — Minecraft requires the "do you want
                    // to open this link" step, and skipping it is how a mod gets flagged.
                    b -> this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(
                            ok -> {
                                if (ok) net.minecraft.Util.getPlatform().openUri(discord);
                                this.minecraft.setScreen(this);
                            }, discord, true))
            ).bounds(this.width / 2 - 124, joinY + 78, 20, 20)
             .icon(DISCORD_ICON, 16)
             .build();
            dc.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Join the Coffee's Aero SMP Discord")));
            this.addRenderableWidget(dc);
        }

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
        tickUpdateButton();                                    // before the draw, so the label is current
        super.render(graphics, mouseX, mouseY, partialTick);   // background (ours) + widgets

        // Pack logo, top-center. Sized by HEIGHT, because the airship in the background art starts
        // around 40% down and the logo must always clear it: 4% top margin + 30% height puts the
        // bottom edge at 34% on every window shape, ultrawide included.
        //
        // 🔑 The cap tracks the ART'S ASPECT, not a fixed number. Sizing is height-driven, so a
        // wider logo at the same cap renders WIDER on screen: the 3.05:1 AERO SHIP art at the
        // previous 0.30 cap came out ~51% of screen width against the old art's ~44%, which read as
        // too big. 0.21 is a deliberate 30% reduction from that (329px -> 228px on a 640x360
        // window), landing at ~36% width on 16:9. Bottom edge sits at 25%, so it still clears the
        // background airship at ~40% with room to spare, and the 80% width guard below never trips
        // (checked at 4:3, 16:9, 16:10 and 21:9). Re-derive this whenever the art changes shape.
        int logoH = (int) (this.height * 0.21);
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

        // Live server status — a standing gauge on the right edge, clear of the button stack.
        drawServerStatus(graphics);

        // RAM warning. Deliberately on the title screen rather than a modal: it is advice, not an
        // error, and a dialog you must dismiss every launch gets clicked through blindly.
        String ram = RamCheck.message();
        if (ram != null) {
            int rw = this.font.width(ram);
            int rx = (this.width - rw) / 2;
            int ry = this.joinY + 104;  // clears the fourth button row (Mods / Aero Config)
            graphics.fill(rx - 4, ry - 2, rx + rw + 4, ry + 10, 0xB0000000);
            graphics.drawString(this.font, ram, rx, ry,
                RamCheck.verdict() == RamCheck.Verdict.TOO_HIGH ? 0xFFFF6B6B : 0xFFFFC857);
        }

        // Vanilla-style detail lines, bottom-left (kept from the stock menu: MC + loader + mods).
        String line1 = "Minecraft " + SharedConstants.getCurrentVersion().getName()
            + " / NeoForge " + NeoForgeVersion.getVersion();
        String line2 = ModList.get().size() + " mods loaded";
        graphics.drawString(this.font, line1, 2, this.height - 20, 0xFFFFFF);
        graphics.drawString(this.font, line2, 2, this.height - 10, 0xFFFFFF);
    }

    /**
     * Live server gauge: a brass instrument panel pinned to the right edge, vertically centred.
     *
     * <p>It sits on the right rather than above Join for two reasons. The button stack is already
     * three rows plus a RAM warning, and a fifth centred line turned that column into a wall of
     * text. And the count is ambient information — you glance at it, you don't read it — so it
     * belongs beside the eye's path to the Join button, not in it.
     *
     * <p>The player count is rendered at 2× so it reads at a glance from across the room; the
     * status word above it carries the colour, and the left edge bar repeats that colour so the
     * state is legible even in peripheral vision.
     */
    private void drawServerStatus(GuiGraphics g) {
        final int BRASS = 0xFF9C7430;
        var state = com.coffeesaerosmp.core.net.GatePing.state;

        String heading;
        String bigNumber = null;
        java.util.List<String> lines = new java.util.ArrayList<>();
        int accent;

        switch (state) {
            case ONLINE -> {
                accent = 0xFF6FD98A;
                heading = "SERVER ONLINE";
                int on = com.coffeesaerosmp.core.net.GatePing.online;
                int max = com.coffeesaerosmp.core.net.GatePing.max;
                bigNumber = Integer.toString(on);
                lines.add(on == 1 ? "pilot aboard" : "pilots aboard");
                if (max > 0) lines.add("of " + max + " berths");
            }
            case OFFLINE -> {
                accent = 0xFFE06C6C;
                heading = "SERVER OFFLINE";
                lines.add("Likely maintenance —");
                lines.add("check Discord for news");
            }
            default -> {
                accent = 0xFFC9A227;
                heading = "CONTACTING GATE";
                lines.add("hold tight…");
            }
        }

        // ── measure ───────────────────────────────────────────────────────────
        final int PAD = 7, BAR = 3, DOT = 5, LINE = 10;
        int headW = DOT + 4 + this.font.width(heading);
        int textW = 0;
        for (String l : lines) textW = Math.max(textW, this.font.width(l));
        int bodyW = textW;
        int numW = 0;
        if (bigNumber != null) {
            numW = this.font.width(bigNumber) * 2;      // drawn at 2× scale
            bodyW = numW + 6 + textW;
        }
        int contentW = Math.max(headW, bodyW);
        int cardW = BAR + PAD + contentW + PAD;

        int bodyH = (bigNumber != null)
            ? Math.max(16, lines.size() * LINE)          // 16 = the 2× digit's height
            : lines.size() * LINE;
        int cardH = PAD + LINE + 4 + bodyH + PAD;

        int x1 = this.width - 10;
        int x0 = x1 - cardW;
        int y0 = (this.height - cardH) / 2;
        int y1 = y0 + cardH;

        // ── panel ─────────────────────────────────────────────────────────────
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF120B05);                 // outer rim
        g.fillGradient(x0, y0, x1, y1, 0xE84A331E, 0xE82E1F10);             // dark oak, matches AeroButton
        g.fill(x0, y0, x1, y0 + 1, BRASS);                                  // brass top edge
        g.fill(x0, y1 - 1, x1, y1, BRASS);                                  // brass bottom edge
        g.fill(x1 - 1, y0, x1, y1, BRASS);                                  // brass outer edge
        g.fill(x0, y0, x0 + BAR, y1, accent);                               // status bar, left

        int cx = x0 + BAR + PAD;
        int cy = y0 + PAD;

        // Heading: dot + word. The dot breathes while online so a live server reads as alive.
        int dotColor = accent;
        if (state == com.coffeesaerosmp.core.net.GatePing.State.ONLINE) {
            float pulse = 0.6F + 0.4F * (float) Math.sin(Util.getMillis() / 420.0);
            dotColor = ((int) (pulse * 255) << 24) | (accent & 0x00FFFFFF);
        }
        g.fill(cx, cy + 2, cx + DOT, cy + 2 + DOT, dotColor);
        g.drawString(this.font, heading, cx + DOT + 4, cy, accent, false);

        int by = cy + LINE + 4;

        if (bigNumber != null) {
            g.pose().pushPose();
            g.pose().translate(cx, by, 0);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            g.drawString(this.font, bigNumber, 0, 0, 0xFFFFFFFF, false);
            g.pose().popPose();
            int tx = cx + numW + 6;
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(this.font, lines.get(i), tx, by + 1 + i * LINE, 0xFFD8C9AE, false);
            }
        } else {
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(this.font, lines.get(i), cx, by + i * LINE, 0xFFD8C9AE, false);
            }
        }
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

    /**
     * Label for the update button, derived from the live check state.
     *
     * <p>Showing the RESULT is the point. The automatic check is silent, so a player who suspects
     * they are out of date has no way to ask and ends up asking in Discord instead. "Up to date ✓"
     * is the answer to that question, and it costs one transient timestamp.
     */
    private Component updateLabel() {
        if (com.coffeesaerosmp.core.UpdaterBridge.isPackOutdated()) {
            return Component.literal("§6Update ▸");
        }
        String s = com.coffeesaerosmp.core.UpdaterBridge.status();
        if ("CHECKING".equals(s))                        return Component.literal("§7Checking…");
        if ("ERROR".equals(s))                           return Component.literal("§cUpdates ⚠");
        if (System.currentTimeMillis() < upToDateUntil)  return Component.literal("§aUp to date ✓");
        return Component.literal("Updates");
    }

    private void onUpdatePressed() {
        // Already known stale — go straight to the existing, tested update flow.
        if (com.coffeesaerosmp.core.UpdaterBridge.isPackOutdated()) {
            this.minecraft.setScreen(openUpdateTarget());
            return;
        }
        // Each press is a real HTTP round trip to the raw CDN, and a button invites mashing.
        long now = System.currentTimeMillis();
        if (now - lastManualCheck < 5_000L) return;
        lastManualCheck = now;
        upToDateUntil = 0L;
        com.coffeesaerosmp.core.UpdaterBridge.recheck();
    }

    /**
     * Keeps the update button's label in step with the background check.
     *
     * <p>Called from render() rather than from a listener because the check finishes on its own
     * daemon thread — there is nothing to be notified BY. Comparing the rendered label against the
     * computed one means the string is only rebuilt when it actually changes.
     */
    private void tickUpdateButton() {
        if (this.updateButton == null) return;
        if ("UP_TO_DATE".equals(com.coffeesaerosmp.core.UpdaterBridge.status())
                && lastManualCheck > 0 && upToDateUntil == 0L) {
            upToDateUntil = System.currentTimeMillis() + 4_000L;   // show the ✓ briefly, then revert
        }
        Component want = updateLabel();
        if (!want.getString().equals(this.updateButton.getMessage().getString())) {
            this.updateButton.setMessage(want);
        }
    }

    /**
     * Where "you are out of date" leads for THIS install.
     *
     * <p>Normally the in-client updater. On a {@code manualUpdateOnly} install — the CurseForge
     * import — it is the store screen instead, because the updater cannot complete a delta that
     * large without being rate-limited by GitHub. Falls back to the store screen whenever the
     * updater is absent (the CF store jar strips it), so this never returns null and never leaves
     * the player on a dead button.
     */
    private Screen openUpdateTarget() {
        if (!AeroConfig.MANUAL_UPDATE_ONLY.get()) {
            Screen s = com.coffeesaerosmp.core.UpdaterBridge.openUpdateScreen(this);
            if (s != null) return s;
        }
        return new com.coffeesaerosmp.core.screen.StoreUpdateScreen(
            this, com.coffeesaerosmp.core.UpdaterBridge.latestVersion());
    }

    private void connectToServer() {
        Minecraft mc = Minecraft.getInstance();
        String ip = ServerLocator.resolve();   // managed (encrypted, in-memory) or admin override
        ServerAddress address = ServerAddress.parseString(ip);
        ServerData data = new ServerData("Coffees Aero SMP", ip, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, mc, address, data, false, null);
    }
}
