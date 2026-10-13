package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;


/**
 * The server resource pack (owner 2026-10-12: "make a resource pack everyone has to download ... players without
 * the RP will be kicked"). Built from {@code src/ShopResourcePack/build_rp.py}; configured by URL + SHA-1.
 *
 * <p>Delivered by the existing {@code [resourcepack] url/hash} config, which AuthManager pushes as REQUIRED on join:
 * vanilla itself disconnects a client that declines or can't download it. With no URL configured every visual
 * falls back to the vanilla-only versions (block-display ship, particles only, plain shop window).
 *
 * <p>Custom model data numbers on minecraft:paper are a CONTRACT with build_rp.py. Add, never renumber.
 */
public final class RpModels {

    private RpModels() {}

    public static final int AIRSHIP = 9001, STAGE = 9002, BACKDROP = 9003;

    /** Font with the shop window skins: \uE000 = 6-row window, \uE001 = 3-row, \uF801 = -8px, \uF802 = -169px. */
    public static final ResourceLocation SHOP_FONT = ResourceLocation.fromNamespaceAndPath("aerosmp", "shop");

    /** GameTest hook: act as if a pack is configured. */
    public static volatile boolean testForce;

    /** True when a pack is configured, so every player on this server has its models. */
    public static boolean enabled() {
        if (testForce) return true;
        try {
            return !AuthConfig.RESOURCE_PACK_URL.get().isBlank() && !AuthConfig.RESOURCE_PACK_HASH.get().isBlank()
                && !LobbyHandoff.isLobbyRole();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A shop window title: the window skin drawn behind the slots (a font glyph, shifted to the window's left
     * edge), then the title text in gold. Without a pack, just the text.
     */
    public static Component windowTitle(String text, int rows) {
        if (!enabled()) return Component.literal(text);
        Style font = Style.EMPTY.withFont(SHOP_FONT).withColor(0xFFFFFF);
        MutableComponent out = Component.empty();
        out.append(Component.literal("\uF801" + (rows >= 6 ? "\uE000" : "\uE001") + "\uF802").withStyle(font));
        out.append(Component.literal(text).withStyle(Style.EMPTY.withColor(0xF0C05A)));
        return out;
    }
}
