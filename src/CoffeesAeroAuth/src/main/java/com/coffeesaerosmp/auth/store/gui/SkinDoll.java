package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's own skin as a flat front-view "paper doll" for the pack Wardrobe ({@link PackWardrobe} draws it
 * pixel by pixel with {@link TitleCanvas#pixels}). The skin comes from the textures property on the player's
 * profile (premium: Mojang's; offline: whatever CoffeesAeroSkins applied), fetched once off-thread and cached.
 *
 * <p>Only Mojang's texture host is ever fetched: a textures property is client-influenced on offline accounts,
 * and the server must not be talked into requesting arbitrary URLs.
 */
final class SkinDoll {

    private SkinDoll() {}

    /** Doll pixels, row-major [32][width], ARGB (alpha 0 = nothing there). */
    record Doll(int[][] px, int width) {}

    private static final Map<String, Doll> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    /** The cached doll, or null while it's being fetched ({@code onReady} runs on the server thread when it is). */
    static Doll get(ServerPlayer p, Runnable onReady) {
        Skin s = skin(p);
        if (s == null) return fallback();
        String key = s.url + (s.slim ? "#slim" : "");
        Doll d = CACHE.get(key);
        if (d != null) return d;
        if (PENDING.add(key)) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.url)).timeout(Duration.ofSeconds(8)).GET().build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((resp, err) -> {
                Doll made = null;
                try {
                    if (err == null && resp.statusCode() == 200 && resp.body().length < 128 * 1024) {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.body()));
                        if (img != null) made = compose(img, s.slim);
                    }
                } catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.debug("[Wardrobe] skin decode failed: {}", e.toString());
                }
                CACHE.put(key, made != null ? made : fallback());
                if (CACHE.size() > 512) CACHE.clear();          // a crude cap; dolls are cheap to remake
                PENDING.remove(key);
                var server = p.getServer();
                if (server != null && onReady != null) server.execute(onReady);
            });
        }
        return null;
    }

    private record Skin(String url, boolean slim) {}

    private static Skin skin(ServerPlayer p) {
        try {
            for (var prop : p.getGameProfile().getProperties().get("textures")) {
                String json = new String(Base64.getDecoder().decode(prop.value()), StandardCharsets.UTF_8);
                JsonObject skin = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
                if (skin == null) return null;
                String url = skin.get("url").getAsString();
                URI u = URI.create(url);
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
                if (!host.equals("textures.minecraft.net")) return null;
                boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                    && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
                return new Skin("https://textures.minecraft.net" + u.getPath(), slim);
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[Wardrobe] no usable textures property: {}", e.toString());
        }
        return null;
    }

    /** Front view: hat over head, jacket/sleeves/trousers over body, arms and legs. Slim arms are 3 wide. */
    static Doll compose(BufferedImage skin, boolean slim) {
        boolean legacy = skin.getHeight() == 32;
        int aw = slim ? 3 : 4, w = 8 + 2 * aw;
        int[][] px = new int[32][w];
        // head + hat
        blit(px, skin, 8, 8, 8, 8, aw, 0, false, 1f);
        // Vanilla's "Notch hack": an old 64x32 skin whose hat area has no transparent pixel at all is drawn
        // WITHOUT the hat (those skins filled it solid by accident).
        if (!legacy || !solid(skin, 32, 0, 64, 32)) blit(px, skin, 40, 8, 8, 8, aw, 0, false, 1f, true);
        // body + jacket
        blit(px, skin, 20, 20, 8, 12, aw, 8, false, 1f);
        if (!legacy) blit(px, skin, 20, 36, 8, 12, aw, 8, false, 1f, true);
        // arms (the player's right arm is on the viewer's left), a touch darker so the body reads in front
        blit(px, skin, 44, 20, aw, 12, 0, 8, false, 0.86f);
        if (!legacy) blit(px, skin, 44, 36, aw, 12, 0, 8, false, 0.86f, true);
        if (legacy) blit(px, skin, 44, 20, aw, 12, aw + 8, 8, true, 0.86f);
        else {
            blit(px, skin, 36, 52, aw, 12, aw + 8, 8, false, 0.86f);
            blit(px, skin, 52, 52, aw, 12, aw + 8, 8, false, 0.86f, true);
        }
        // legs
        blit(px, skin, 4, 20, 4, 12, aw, 20, false, 0.93f);
        if (!legacy) blit(px, skin, 4, 36, 4, 12, aw, 20, false, 0.93f, true);
        if (legacy) blit(px, skin, 4, 20, 4, 12, aw + 4, 20, true, 0.93f);
        else {
            blit(px, skin, 20, 52, 4, 12, aw + 4, 20, false, 0.93f);
            blit(px, skin, 4, 52, 4, 12, aw + 4, 20, false, 0.93f, true);
        }
        return new Doll(px, w);
    }

    private static void blit(int[][] px, BufferedImage s, int sx, int sy, int w, int h, int dx, int dy,
                             boolean mirror, float shade) {
        blit(px, s, sx, sy, w, h, dx, dy, mirror, shade, false);
    }

    private static void blit(int[][] px, BufferedImage s, int sx, int sy, int w, int h, int dx, int dy,
                             boolean mirror, float shade, boolean overlay) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int src = mirror ? sx + (w - 1 - x) : sx + x;
                if (src >= s.getWidth() || sy + y >= s.getHeight()) continue;
                int argb = s.getRGB(src, sy + y);
                int a = argb >>> 24;
                if (overlay && a < 128) continue;             // overlays: only solid pixels cover the base
                if (!overlay) a = 255;                         // base layers are opaque in game
                int r = (int) (((argb >> 16) & 255) * shade), g = (int) (((argb >> 8) & 255) * shade),
                    b = (int) ((argb & 255) * shade);
                px[dy + y][dx + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static boolean solid(BufferedImage s, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < Math.min(y1, s.getHeight()); y++)
            for (int x = x0; x < Math.min(x1, s.getWidth()); x++)
                if ((s.getRGB(x, y) >>> 24) < 128) return false;
        return true;
    }

    /** No skin to fetch: a plain figure in Steve's colours. */
    static Doll fallback() {
        int[][] px = new int[32][16];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) {
            int c = 0;
            if (y < 8 && x >= 4 && x < 12) c = y < 2 ? 0xFF2B1E0E : 0xFFB98A6E;
            else if (y >= 8 && y < 20 && x >= 4 && x < 12) c = 0xFF00A8A8;
            else if (y >= 8 && y < 20) c = y < 12 ? 0xFF00A8A8 : 0xFFB98A6E;
            else if (y >= 20 && x >= 4 && x < 12) c = y < 30 ? 0xFF3A3AB0 : 0xFF6A6A6A;
            px[y][x] = c;
        }
        return new Doll(px, 16);
    }
}
