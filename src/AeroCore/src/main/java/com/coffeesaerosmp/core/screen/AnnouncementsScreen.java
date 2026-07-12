package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.announce.AnnouncementData;
import com.coffeesaerosmp.core.announce.AnnouncementState;
import com.coffeesaerosmp.core.client.AeroButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Main-menu "Announcements" screen — the pack changelog, newest first, with color-coded
 * ADDED / FIXED / REMOVED sections and the full history of past updates. Opening it marks the newest
 * entry as seen (clears the button's NEW badge). Scrollable; no network — reads bundled data only.
 */
public class AnnouncementsScreen extends Screen {

    private final Screen parent;

    // Pre-flattened display lines (built once in init) so scrolling is cheap.
    private record Line(String text, int color, int indent) {}
    private final List<Line> lines = new ArrayList<>();

    private int scroll = 0;
    private int contentHeight = 0;
    private int viewTop, viewBottom, viewLeft, viewRight;

    private static final int C_HEADER  = 0xFFF0C05A;   // brass
    private static final int C_DATE    = 0xFF9A8156;
    private static final int C_TITLE   = 0xFFEADCC3;
    private static final int C_ADDED   = 0xFF66DD77;   // green
    private static final int C_FIXED   = 0xFFF2D65C;   // yellow
    private static final int C_REMOVED = 0xFFE0605A;   // red
    private static final int C_TEXT    = 0xFFD8D8D8;

    public AnnouncementsScreen(Screen parent) {
        super(Component.literal("Announcements"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AnnouncementState.markLatestSeen();   // opening = seen; badge won't show again this version

        viewLeft   = this.width / 2 - 180;
        viewRight  = this.width / 2 + 180;
        viewTop    = 40;
        viewBottom = this.height - 40;

        buildLines(viewRight - viewLeft - 16);

        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Back"),
                b -> this.minecraft.setScreen(parent))
            .bounds(this.width / 2 - 60, this.height - 28, 120, 20).build());
    }

    private void buildLines(int wrapWidth) {
        lines.clear();
        List<AnnouncementData.Entry> entries = AnnouncementData.entries();
        if (entries.isEmpty()) {
            lines.add(new Line("No announcements yet — check back after the next update!", C_TEXT, 0));
        }
        boolean first = true;
        for (AnnouncementData.Entry e : entries) {
            if (!first) lines.add(new Line("", C_TEXT, 0));   // spacer between versions
            first = false;
            String head = "v" + e.version() + (e.date().isBlank() ? "" : "   " + e.date());
            lines.add(new Line(head, C_HEADER, 0));
            if (!e.title().isBlank()) lines.add(new Line(e.title(), C_TITLE, 0));
            section(wrapWidth, "＋ Added",   e.added(),   C_ADDED);
            section(wrapWidth, "✔ Fixed",   e.fixed(),   C_FIXED);
            section(wrapWidth, "－ Removed", e.removed(), C_REMOVED);
        }
        contentHeight = lines.size() * 11;
    }

    private void section(int wrapWidth, String label, List<String> items, int color) {
        if (items.isEmpty()) return;
        lines.add(new Line(label, color, 0));
        for (String item : items) {
            List<net.minecraft.util.FormattedCharSequence> wrapped =
                this.font.split(Component.literal("• " + item), wrapWidth - 10);
            boolean firstWrap = true;
            for (var seq : wrapped) {
                lines.add(new Line(seqToString(seq), C_TEXT, firstWrap ? 8 : 14));
                firstWrap = false;
            }
        }
    }

    private static String seqToString(net.minecraft.util.FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((idx, style, cp) -> { sb.appendCodePoint(cp); return true; });
        return sb.toString();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        // Panel
        g.fill(viewLeft - 8, viewTop - 22, viewRight + 8, viewBottom + 8, 0xD01A0F06);
        g.fill(viewLeft - 8, viewTop - 22, viewRight + 8, viewTop - 21, 0xFF9C7430);   // brass top rule
        g.drawCenteredString(this.font,
            Component.literal("✦ Coffees Aero SMP — Announcements").withStyle(ChatFormatting.GOLD),
            this.width / 2, viewTop - 18, 0xFFFFFF);

        // Clipped scroll region
        g.enableScissor(viewLeft - 6, viewTop, viewRight + 6, viewBottom);
        int y = viewTop - scroll;
        for (Line ln : lines) {
            if (y > viewTop - 12 && y < viewBottom + 2 && !ln.text().isEmpty())
                g.drawString(this.font, ln.text(), viewLeft + ln.indent(), y, ln.color(), false);
            y += 11;
        }
        g.disableScissor();

        // Scrollbar
        int viewH = viewBottom - viewTop;
        if (contentHeight > viewH) {
            int barH = Math.max(20, viewH * viewH / contentHeight);
            int barY = viewTop + (viewH - barH) * scroll / Math.max(1, contentHeight - viewH);
            g.fill(viewRight + 4, barY, viewRight + 7, barY + barH, 0xFF9C7430);
        }

        super.render(g, mouseX, mouseY, partialTick);   // Back button
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int viewH = viewBottom - viewTop;
        int max = Math.max(0, contentHeight - viewH);
        scroll = Mth.clamp(scroll - (int) (dy * 18), 0, max);
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Reuse the pack's dim menu backdrop (parent draws the airship art underneath if present).
        if (parent != null) parent.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
