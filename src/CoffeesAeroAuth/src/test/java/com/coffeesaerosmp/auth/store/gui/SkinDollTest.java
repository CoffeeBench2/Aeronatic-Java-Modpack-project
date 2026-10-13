package com.coffeesaerosmp.auth.store.gui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class SkinDollTest {

    /** A 64x64 skin with one flat colour per front face, so every region's destination can be checked. */
    private static BufferedImage regions(boolean withOverlays) {
        BufferedImage s = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(s, 8, 8, 8, 8, 0xFF110000);       // head
        fill(s, 20, 20, 8, 12, 0xFF002200);    // body
        fill(s, 44, 20, 4, 12, 0xFF000033);    // right arm
        fill(s, 36, 52, 4, 12, 0xFF440000);    // left arm
        fill(s, 4, 20, 4, 12, 0xFF005500);     // right leg
        fill(s, 20, 52, 4, 12, 0xFF000066);    // left leg
        if (withOverlays) fill(s, 40, 8, 8, 2, 0xFF777777);   // a hat brim over the top two rows
        return s;
    }

    private static void fill(BufferedImage s, int x, int y, int w, int h, int argb) {
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) s.setRGB(x + i, y + j, argb);
    }

    @Test
    void frontFacesLandWhereTheyBelong() {
        var d = SkinDoll.compose(regions(false), false);
        assertEquals(16, d.width());
        assertEquals(0xFF110000, d.px()[0][4], "head");
        assertEquals(0, d.px()[0][0] >>> 24, "nothing beside the head");
        assertEquals(0xFF002200, d.px()[10][8], "body");
        assertEquals(0x00, d.px()[10][1] >> 16 & 0xFF);
        assertTrue((d.px()[10][1] & 0xFF) > 0, "right arm on the viewer's left (shaded)");
        assertTrue((d.px()[10][13] >> 16 & 0xFF) > 0, "left arm on the viewer's right");
        assertTrue((d.px()[25][5] >> 8 & 0xFF) > 0, "right leg on the left");
        assertTrue((d.px()[25][10] & 0xFF) > 0, "left leg on the right");
    }

    @Test
    void overlaysCoverOnlyWhereSolid() {
        var d = SkinDoll.compose(regions(true), false);
        assertEquals(0xFF777777, d.px()[0][6], "hat brim covers the head");
        assertEquals(0xFF110000, d.px()[5][6], "transparent hat leaves the face");
    }

    @Test
    void slimArmsAreThreeWide() {
        assertEquals(14, SkinDoll.compose(regions(false), true).width());
    }

    @Test
    void legacySkinsMirrorTheRightLimbs() {
        BufferedImage s = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        fill(s, 44, 20, 4, 12, 0xFF000033);
        fill(s, 4, 20, 4, 12, 0xFF005500);
        var d = SkinDoll.compose(s, false);
        assertTrue((d.px()[10][13] & 0xFF) > 0, "left arm copied from the right");
        assertTrue((d.px()[25][10] >> 8 & 0xFF) > 0, "left leg copied from the right");
    }

    /** Eyeballing aid: -Ddoll.skins=<dir of skin_*.png> renders each as an upscaled doll next to it. */
    @Test
    void renderRealSkinsWhenAsked() throws Exception {
        String dir = System.getProperty("doll.skins", System.getenv("DOLL_SKINS"));
        if (dir == null) return;
        File[] skins = new File(dir).listFiles((f, n) -> n.startsWith("skin_") && n.endsWith(".png"));
        assertNotNull(skins);
        for (File f : skins) {
            var d = SkinDoll.compose(ImageIO.read(f), false);
            BufferedImage out = new BufferedImage(d.width() * 8, 32 * 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 32 * 8; y++) for (int x = 0; x < d.width() * 8; x++) out.setRGB(x, y, d.px()[y / 8][x / 8]);
            ImageIO.write(out, "png", new File(dir, "doll_" + f.getName()));
        }
    }
}
