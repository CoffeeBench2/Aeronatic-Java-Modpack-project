package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.network.WardrobePayload;
import com.coffeesaerosmp.auth.store.BeanLedger;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.Entitlements;
import com.coffeesaerosmp.auth.store.Gradient;
import com.coffeesaerosmp.auth.store.MojangIds;
import com.coffeesaerosmp.auth.store.Palette;
import com.coffeesaerosmp.auth.store.Rank;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and sends the {@link WardrobePayload} for one player. Sent when they open {@code /shop} (if their client
 * has the Wardrobe screen) and again whenever their cosmetics change while it's open (buy, wear, rank change), so
 * the screen always shows the truth from the server.
 *
 * <p>Payload size: ~40 items × ~200 bytes ≈ 8 KB once per open. Nothing is sent per frame: every preview (3D
 * model, nameplate, trail particles, mini airship + contrail) is drawn by the client from these numbers.
 */
public final class WardrobeSync {

    private WardrobeSync() {}

    /** Players who opened the Wardrobe recently: they get live refreshes. Cleared on logout / after 15 min. */
    private static final Map<UUID, Long> OPEN = new ConcurrentHashMap<>();
    private static final long OPEN_TTL_MS = 15 * 60_000L;

    public static boolean supported(ServerPlayer p) {
        return p != null && p.connection != null && p.connection.hasChannel(WardrobePayload.TYPE);
    }

    /** Open the Wardrobe on the player's client (balance read off-thread first). */
    public static void open(ServerPlayer p) {
        send(p, true);
    }

    /** Called when a player's store state changes; refreshes an open Wardrobe. */
    public static void onChange(ServerPlayer p) {
        Long at = OPEN.get(p.getUUID());
        if (at == null || System.currentTimeMillis() - at > OPEN_TTL_MS || !supported(p)) return;
        send(p, false);
    }

    public static void forget(UUID id) { OPEN.remove(id); }

    private static void send(ServerPlayer p, boolean open) {
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            int bal = mojang == null ? -1 : BeanLedger.balance(mojang);
            var server = p.getServer();
            if (server == null) return;
            server.execute(() -> {
                if (p.hasDisconnected() || !supported(p)) return;
                OPEN.put(local, System.currentTimeMillis());
                p.connection.send(new WardrobePayload(build(p, bal, open).toString()));
            });
        });
    }

    /** The JSON. Package-private for the GameTest. Schema v1, mirrored in AeroCore's WardrobeData. */
    static JsonObject build(ServerPlayer p, int balance, boolean open) {
        String name = p.getGameProfile().getName();
        var snap = StoreState.get(p.getUUID());
        Rank rank = snap.effectiveRank();
        var sel = StoreState.selectionFor(p.getUUID());

        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("open", open);
        root.addProperty("name", name);
        root.addProperty("rank", rank.name());
        root.addProperty("rankLabel", rank == Rank.NONE ? "" : rank.displayName());
        root.addProperty("rankBadge", rank == Rank.NONE ? "" : rank.badge().replaceAll("§.", "").trim());
        root.addProperty("balance", balance);
        String url = "";
        try { url = AuthConfig.STORE_URL.get(); } catch (Throwable ignored) { }
        root.addProperty("storeUrl", url == null ? "" : url);
        JsonObject eq = new JsonObject();
        if (sel.trail() != null) eq.addProperty("trail", sel.trail());
        if (sel.contrail() != null) eq.addProperty("contrail", sel.contrail());
        if (sel.nameStyle() != null) eq.addProperty("nameStyle", sel.nameStyle());
        eq.addProperty("bold", sel.bold());
        root.add("equipped", eq);
        root.add("nameRgb", nameRgb(sel.nameStyle(), name));

        JsonArray items = new JsonArray();
        for (ShopGui.Category cat : new ShopGui.Category[]{ShopGui.Category.TRAILS, ShopGui.Category.CONTRAILS,
                ShopGui.Category.COLOURS, ShopGui.Category.GRADIENTS, ShopGui.Category.EXTRAS}) {
            for (ShopGui.Entry e : ShopGui.entries(p, cat)) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.id());
                o.addProperty("label", e.label());
                if (e.blurb() != null) o.addProperty("blurb", e.blurb());
                o.addProperty("cat", cat.name());
                if (e.price().isPresent()) o.addProperty("price", e.price().getAsInt());
                if (e.rankNeeded() != null) o.addProperty("rank", e.rankNeeded().displayName());
                o.addProperty("owned", e.owned());
                o.addProperty("available", e.available());
                o.addProperty("equipped", e.equipped());
                var key = BuiltInRegistries.ITEM.getKey(ShopGui.iconFor(e.id()));
                o.addProperty("icon", key == null ? "minecraft:paper" : key.toString());
                CosmeticCatalog.get(e.id()).ifPresent(c -> {
                    var fx = c.fx();
                    JsonObject f = new JsonObject();
                    f.addProperty("particle", fx.particleId());
                    f.addProperty("rgb", fx.rgb());
                    f.addProperty("rgb2", fx.rgb2());
                    f.addProperty("scale", fx.scale());
                    f.addProperty("count", fx.count());
                    f.addProperty("spread", fx.spread());
                    f.addProperty("every", fx.everyTicks());
                    o.add("fx", f);
                });
                if (cat == ShopGui.Category.COLOURS || cat == ShopGui.Category.GRADIENTS) o.add("nameRgb", nameRgb(e.id(), name));
                items.add(o);
            }
        }
        root.add("items", items);
        return root;
    }

    /** One RGB per character of the name for a colour or gradient style; empty = default white. */
    static JsonArray nameRgb(String style, String name) {
        JsonArray a = new JsonArray();
        if (style == null) return a;
        if (style.startsWith(Entitlements.GRADIENT_PREFIX)) {
            int[] c = Gradient.forLength(style.substring(Entitlements.GRADIENT_PREFIX.length()), name.length());
            if (c != null) for (int v : c) a.add(v);
        } else if (style.startsWith(Entitlements.COLOUR_PREFIX)) {
            int rgb = Palette.rgb(style.substring(Entitlements.COLOUR_PREFIX.length()));
            if (rgb >= 0) for (int i = 0; i < name.length(); i++) a.add(rgb);
        }
        return a;
    }
}
