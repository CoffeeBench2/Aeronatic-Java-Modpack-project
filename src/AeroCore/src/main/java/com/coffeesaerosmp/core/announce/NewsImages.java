package com.coffeesaerosmp.core.announce;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the pictures used by the News screen: fetch once, cache on disk, upload to a GPU texture,
 * hand back a {@link ResourceLocation} the screen can draw.
 *
 * <p>Everything is best effort and non-blocking. {@link #get} never waits and never throws — it
 * returns null until the image is ready, and null forever if it cannot be had. A news entry whose
 * picture is missing still renders its text, because a changelog that fails to appear because a CDN
 * was slow would be a worse screen than one with no pictures at all.
 *
 * <p><b>Decode off-thread, upload on-thread.</b> {@link NativeImage#read} is pure CPU work and is
 * done on the download thread, but {@code DynamicTexture} touches GL and must happen on the render
 * thread — hence the hop through {@link Minecraft#execute}. Doing the upload from the worker is the
 * classic way to get a driver crash that only reproduces on other people's machines.
 *
 * <p>Disk cache lives in {@code .aero-update/newscache/} keyed by a hash of the URL, so a player who
 * has opened the news once sees the pictures instantly forever after, and offline.
 */
public final class NewsImages {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-News");

    /** Enough for a wide banner at 2x; anything larger is almost certainly a mistake in the JSON. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIM   = 2048;

    /** A ready picture: its texture plus the pixel size, which the screen needs to keep aspect. */
    public record Tex(ResourceLocation id, int width, int height) {}

    private static final Map<String, Tex> READY = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED   = ConcurrentHashMap.newKeySet();

    /** One thread: news pictures are never urgent and must not compete with the pack updater. */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AeroNewsImages");
        t.setDaemon(true);
        return t;
    });

    private NewsImages() {}

    /**
     * @return the picture for this URL, or null if it is not ready yet (a fetch is started on the
     *         first call). Safe to call every frame — that is the intended usage.
     */
    public static Tex get(String url) {
        if (url == null || url.isBlank()) return null;
        Tex done = READY.get(url);
        if (done != null) return done;
        if (FAILED.contains(url) || !INFLIGHT.add(url)) return null;
        POOL.submit(() -> load(url));
        return null;
    }

    /** True once we have given up on this URL, so the screen can stop reserving space for it. */
    public static boolean failed(String url) { return url != null && FAILED.contains(url); }

    private static void load(String url) {
        try {
            byte[] bytes = cached(url);
            if (bytes == null) {
                bytes = download(url);
                if (bytes != null) store(url, bytes);
            }
            if (bytes == null) { fail(url, "no data"); return; }

            NativeImage img;
            try {
                img = NativeImage.read(new java.io.ByteArrayInputStream(bytes));
            } catch (Exception e) {
                fail(url, "not a readable PNG: " + e);
                return;
            }
            if (img.getWidth() > MAX_DIM || img.getHeight() > MAX_DIM) {
                img.close();
                fail(url, "image too large (" + img.getWidth() + "x" + img.getHeight() + ")");
                return;
            }

            // GL work belongs to the render thread.
            final int iw = img.getWidth(), ih = img.getHeight();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    ResourceLocation id = mc.getTextureManager().register(
                        "aero_news/" + hash(url), new DynamicTexture(img));
                    READY.put(url, new Tex(id, iw, ih));
                } catch (Throwable t) {
                    img.close();
                    fail(url, "upload failed: " + t);
                }
            });
        } catch (Throwable t) {
            fail(url, t.toString());
        } finally {
            INFLIGHT.remove(url);
        }
    }

    private static void fail(String url, String why) {
        FAILED.add(url);
        LOGGER.warn("[News] image unavailable ({}): {}", why, url);
    }

    private static byte[] download(String url) {
        try {
            // Only http(s). A file: or jar: URL in a document fetched off the internet is a way to
            // make the client read something local it was never meant to touch.
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                fail(url, "not an http(s) url");
                return null;
            }
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestProperty("User-Agent", "CoffeesAeroCore-News");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) { fail(url, "HTTP " + c.getResponseCode()); return null; }
            try (InputStream in = c.getInputStream()) {
                byte[] b = in.readNBytes(MAX_BYTES + 1);
                if (b.length > MAX_BYTES) { fail(url, "larger than " + MAX_BYTES + " bytes"); return null; }
                return b;
            }
        } catch (Exception e) {
            fail(url, e.toString());
            return null;
        }
    }

    private static Path cacheDir() {
        return FMLPaths.GAMEDIR.get().resolve(".aero-update").resolve("newscache");
    }

    private static byte[] cached(String url) {
        try {
            Path p = cacheDir().resolve(hash(url) + ".png");
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void store(String url, byte[] bytes) {
        try {
            Path dir = cacheDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve(hash(url) + ".png"), bytes);
        } catch (Exception ignored) {
            // A cache that cannot be written is a slower news screen, not a broken one.
        }
    }

    /** Stable, filesystem-safe key for a URL. */
    private static String hash(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
