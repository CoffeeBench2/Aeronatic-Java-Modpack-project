package com.coffeesaerosmp.skins.server;

import com.coffeesaerosmp.skins.CoffeesAeroSkins;
import com.coffeesaerosmp.skins.api.AeroSkinsApi;
import com.coffeesaerosmp.skins.network.SkinSyncPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Server-side skin engine for the offline-mode backend (online-mode=false), where players otherwise
 * get the default Steve/Alex skin because Mojang doesn't attach a textures property.
 *
 * <ul>
 *   <li><b>Premium</b> — the gate-verified UUID fetches the player's REAL Mojang skin+cape.</li>
 *   <li><b>Offline</b> — {@code /skin <java_username>} copies any Java account's public skin.</li>
 * </ul>
 *
 * <p>Every applied skin is (1) written into the player's GameProfile + player-info rebroadcast, the
 * vanilla path any un-modded client understands, and (2) recorded in {@link #ACTIVE} and pushed over
 * {@code aerosmp:skin_sync} to every modded client — including the full table to each joining client.
 * The payload path is authoritative for pack clients: it survives vanilla's local-player secure-skin
 * filter and observer-side render caches that miss the one-shot rebroadcast.</p>
 *
 * <p>All Mojang HTTP is off-thread; GameProfile mutation + broadcasts run on the server thread.
 * Fail-closed: any lookup error leaves the current skin untouched.</p>
 */
public final class SkinService {

    private SkinService() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6)).build();

    /** Donor UUID for a "revert to default skin" entry (no donor profile). */
    public static final UUID NO_SKIN_UUID = new UUID(0L, 0L);

    /** One applied skin: donor profile UUID + base64 textures value. */
    public record ActiveSkin(UUID donorId, String textures) {}

    /** Online players' applied skins — the table replayed to every joining client. */
    private static final Map<UUID, ActiveSkin> ACTIVE = new ConcurrentHashMap<>();

    // ── Public entry points ─────────────────────────────────────────────────────

    /** Premium: apply the player's REAL Mojang skin AND cape by verified UUID (fetched fresh each join). */
    public static void applyPremium(ServerPlayer player, UUID realMojangUuid) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String name = player.getGameProfile().getName();
        CoffeesAeroSkins.LOGGER.info("[Skins] {} PREMIUM — fetching real Mojang skin for {}", name, realMojangUuid);
        fetchTexturesValue(realMojangUuid).thenAccept(value -> {
            if (value == null) {
                CoffeesAeroSkins.LOGGER.warn("[Skins] {} — Mojang skin fetch returned nothing (Mojang unreachable, or no textures).", name);
                return;
            }
            server.execute(() -> {
                apply(player, value, realMojangUuid);
                AeroSkinsApi.backend().saveTextures(player.getUUID(), value);
                CoffeesAeroSkins.LOGGER.info("[Skins] {} — applied real skin+cape ({} chars).", name, value.length());
            });
        });
    }

    /** Re-apply the custom skin saved on the profile. Call on join (server thread). */
    public static void applySaved(ServerPlayer player) {
        String saved = AeroSkinsApi.backend().savedTextures(player.getUUID());
        if (saved != null && !saved.isBlank()) apply(player, saved, profileIdFromTextures(saved));
    }

    /** {@code /skin <name>}: copy a Java account's public SKIN (cape stripped unless the player is
     *  cape-enabled — i.e. premium). Persisted. Callback on server thread. */
    public static void applyByName(ServerPlayer player, String javaName, Consumer<String> onResult) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        boolean allowCape = AeroSkinsApi.backend().capeAllowed(player.getUUID());
        resolveName(javaName)
            .thenCompose(uuid -> uuid == null
                ? CompletableFuture.completedFuture((Map.Entry<UUID, String>) null)
                : fetchTexturesValue(uuid).thenApply(v ->
                    v == null ? null : Map.entry(uuid, v)))
            .thenAccept(donor -> server.execute(() -> {
                if (donor == null) { onResult.accept(null); return; }
                String applied = allowCape ? donor.getValue() : stripCape(donor.getValue());
                apply(player, applied, donor.getKey());
                AeroSkinsApi.backend().saveTextures(player.getUUID(), applied);
                CoffeesAeroSkins.LOGGER.info("[Skins] {} — applied skin from '{}' (cape={}).",
                    player.getGameProfile().getName(), javaName, allowCape);
                onResult.accept(javaName);
            }));
    }

    /** {@code /skin reset}: clear the custom skin (offline → default; premium → real skin on next join). */
    public static void reset(ServerPlayer player) {
        player.getGameProfile().getProperties().removeAll("textures");
        AeroSkinsApi.backend().saveTextures(player.getUUID(), null);
        refresh(player);
        ACTIVE.remove(player.getUUID());
        broadcast(new SkinSyncPayload(player.getUUID(), NO_SKIN_UUID, ""), player.getServer());
    }

    // ── Join / leave sync ───────────────────────────────────────────────────────

    /** Join: re-apply the saved custom skin AND replay the whole skin table to the joining client. */
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        try { applySaved(sp); } catch (Exception ignored) {}
        try { syncAllTo(sp); } catch (Exception ignored) {}
    }

    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) ACTIVE.remove(sp.getUUID());
    }

    /** Replays every online player's applied skin to one (newly joined) client. */
    private static void syncAllTo(ServerPlayer viewer) {
        for (Map.Entry<UUID, ActiveSkin> e : ACTIVE.entrySet()) {
            if (e.getKey().equals(viewer.getUUID())) continue;   // own entry arrives via applySaved/applyPremium
            send(viewer, new SkinSyncPayload(e.getKey(), e.getValue().donorId(), e.getValue().textures()));
        }
    }

    // ── GameProfile mutation + client sync (server thread only) ─────────────────

    private static void apply(ServerPlayer player, String value, UUID donorId) {
        UUID donor = donorId != null ? donorId : NO_SKIN_UUID;
        PropertyMap props = player.getGameProfile().getProperties();
        props.removeAll("textures");
        props.put("textures", new Property("textures", value)); // unsigned OK (enforce-secure-profile=false)
        refresh(player);
        ACTIVE.put(player.getUUID(), new ActiveSkin(donor, value));
        broadcast(new SkinSyncPayload(player.getUUID(), donor, value), player.getServer());
    }

    /** Vanilla fallback path: rebroadcasts the player-info entry so un-modded clients re-read the skin. */
    private static void refresh(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        server.getPlayerList().broadcastAll(
            ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
    }

    /** Pushes one skin entry to EVERY connected modded client (skin_sync is an optional channel). */
    private static void broadcast(SkinSyncPayload payload, MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) send(viewer, payload);
    }

    private static void send(ServerPlayer viewer, SkinSyncPayload payload) {
        try {
            if (viewer.connection.hasChannel(payload.type())) {
                viewer.connection.send(new ClientboundCustomPayloadPacket(payload));
            }
        } catch (Exception e) {
            CoffeesAeroSkins.LOGGER.warn("[Skins] skin_sync to {} failed: {}",
                viewer.getGameProfile().getName(), e.toString());
        }
    }

    // ── Textures helpers ────────────────────────────────────────────────────────

    /** Extracts the profileId embedded in a base64 textures value (null-safe → NO_SKIN_UUID). */
    private static UUID profileIdFromTextures(String texturesValue) {
        try {
            String json = new String(java.util.Base64.getDecoder().decode(texturesValue),
                java.nio.charset.StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            UUID id = root.has("profileId") ? undashUuid(root.get("profileId").getAsString()) : null;
            return id != null ? id : NO_SKIN_UUID;
        } catch (Exception e) {
            return NO_SKIN_UUID;
        }
    }

    /** Remove the CAPE entry from a textures value so offline players get only the skin. */
    private static String stripCape(String texturesValue) {
        try {
            String json = new String(java.util.Base64.getDecoder().decode(texturesValue),
                java.nio.charset.StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures != null && textures.has("CAPE")) {
                textures.remove("CAPE");
                return java.util.Base64.getEncoder().encodeToString(
                    root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return texturesValue; // no cape present — unchanged
        } catch (Exception e) {
            return texturesValue; // malformed value — apply as-is (it likely won't render anyway)
        }
    }

    // ── Mojang API (off-thread, fail-closed) ─────────────────────────────────────

    /** sessionserver profile -> base64 "textures" value, or {@code null} on any failure. */
    private static CompletableFuture<String> fetchTexturesValue(UUID uuid) {
        String id = uuid.toString().replace("-", "");
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
            .timeout(Duration.ofSeconds(6)).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            try {
                if (resp.statusCode() != 200) return null;
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray props = o.getAsJsonArray("properties");
                if (props == null) return null;
                for (var el : props) {
                    JsonObject po = el.getAsJsonObject();
                    if ("textures".equals(po.get("name").getAsString())) return po.get("value").getAsString();
                }
                return null;
            } catch (Exception e) { return null; }
        }).exceptionally(t -> null);
    }

    /** api.mojang.com username -> UUID, or {@code null} if unknown / error. */
    private static CompletableFuture<UUID> resolveName(String name) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
            .timeout(Duration.ofSeconds(6)).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            try {
                if (resp.statusCode() != 200) return null;
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                return undashUuid(o.get("id").getAsString());
            } catch (Exception e) { return null; }
        }).exceptionally(t -> null);
    }

    private static UUID undashUuid(String s) {
        if (s == null || s.length() != 32) return null;
        return UUID.fromString(s.replaceFirst(
            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
            "$1-$2-$3-$4-$5"));
    }
}
