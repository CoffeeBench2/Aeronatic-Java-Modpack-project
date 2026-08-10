package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.screen.AeroSettingsScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds the pack's settings tile to the in-game pause menu, beside the button column — the same
 * placement Create's goggles and spark's icon use.
 *
 * <p>Done with {@link ScreenEvent.Init.Post} rather than a mixin: the pause menu is rebuilt by
 * several mods in this pack, and appending a widget through the event survives that, where a mixin
 * on a specific injection point would be one more thing to fight over.
 */
public final class PauseMenuButton {

    private PauseMenuButton() {}

    private static final ResourceLocation ICON =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/logo_icon.png");

    private static final int TILE = 20;

    /**
     * Picks a gutter slot beside the button column that nothing else occupies. Returns {x, y}.
     * Rows are tried top-down, left gutter before right, so the tile lands as high as it can.
     */
    private static int[] firstFreeSlot(ScreenEvent.Init.Post event, List<Integer> rowYs,
                                       int columnLeft, int columnWidth) {
        int left = columnLeft - TILE - 6;
        int right = columnLeft + columnWidth + 6;
        for (int y : rowYs) {
            for (int x : new int[]{left, right}) {
                if (x < 2) continue;
                if (isFree(event, x, y)) return new int[]{x, y};
            }
        }
        // Everything taken — sit under the column rather than on top of someone else's button.
        return new int[]{Math.max(2, left), rowYs.get(rowYs.size() - 1) + 24};
    }

    private static boolean isFree(ScreenEvent.Init.Post event, int x, int y) {
        for (var child : event.getListenersList()) {
            if (!(child instanceof AbstractWidget w) || !w.visible) continue;
            boolean overlap = x < w.getX() + w.getWidth() && x + TILE > w.getX()
                           && y < w.getY() + w.getHeight() && y + TILE > w.getY();
            if (overlap) return false;
        }
        return true;
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen screen)) return;

        // Anchor to the real button column instead of vanilla's layout constants: other mods add
        // rows here (ReplayMod adds two), so hard-coded coordinates drift.
        // Anchor to the WIDEST button ("Back to Game", 204px and centred) rather than the smallest
        // X in the menu: some widget here sits at x=0, which dragged the column edge to 0 and put
        // the tile off-screen at x=-26.
        List<Integer> rowYs = new ArrayList<>();
        int columnLeft = Integer.MAX_VALUE, columnWidth = 0;
        for (var child : event.getListenersList()) {
            if (child instanceof AbstractWidget w && w.getWidth() >= 98 && w.getHeight() <= 24
                    && w.getX() > 0) {
                if (!rowYs.contains(w.getY())) rowYs.add(w.getY());
                if (w.getWidth() > columnWidth) {
                    columnWidth = w.getWidth();
                    columnLeft = w.getX();
                }
            }
        }
        int x, y;
        if (rowYs.isEmpty() || columnLeft == Integer.MAX_VALUE) {
            // Fallback to vanilla's own layout maths so the button still appears if another mod
            // has rebuilt the menu into something this scan doesn't recognise.
            x = screen.width / 2 - 130;
            y = screen.height / 4 + 96;
        } else {
            rowYs.sort(Integer::compareTo);
            // Create's goggles and spark's icon already claim gutter slots here, so don't just pick
            // a row — walk the free ones. Left gutter first (Create's side), then right (spark's),
            // taking the first slot that collides with nothing.
            int[] found = firstFreeSlot(event, rowYs, columnLeft, columnWidth);
            x = found[0];
            y = found[1];
        }

        AeroButton b = AeroButton.aero(
            Component.literal("Aero Settings"),
            btn -> screen.getMinecraft().setScreen(new AeroSettingsScreen(screen))
        ).bounds(x, y, 20, 20).icon(ICON, 16).build();
        b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.literal("Coffees Aero SMP settings")));
        event.addListener(b);
        com.mojang.logging.LogUtils.getLogger().info(
            "[AeroCore] pause tile at {},{} (rows={}, columnLeft={}, colWidth={})",
            x, y, rowYs.size(), columnLeft == Integer.MAX_VALUE ? "n/a" : columnLeft, columnWidth);
    }
}
