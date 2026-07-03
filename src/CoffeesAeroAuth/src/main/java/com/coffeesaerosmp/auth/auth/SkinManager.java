package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Applies player skins on the offline-mode backend (online-mode=false), where players otherwise
 * get the default Steve/Alex skin because Mojang doesn't attach a textures property.
 *
 * <ul>
 *   <li><b>Premium</b> — fetch the player's REAL Mojang skin via the gate-verified UUID and apply it,
 *       so their actual skin shows. Re-fetched every join (unless they've set a custom skin).</li>
 *   <li><b>Offline</b> — {@code /skin <java_username>} copies any Java account's public skin; saved
 *       to the profile so it persists across sessions.</li>
 * </ul>
 *
 * <p>Skins are stored UNSIGNED (the backend runs {@code enforce-secure-profile=false}, so clients
 * don't require a Mojang signature) as the base64 "textures" value on {@link PlayerProfile#skinUrl}.
 * All Mojang HTTP is off-thread; the actual GameProfile mutation + client refresh runs on the server
 * thread. Fail-closed: any lookup error leaves the current skin untouched.</p>
 */
public final class SkinManager {

    private SkinManager() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6)).build();

    /** UUID used for a "revert to default skin" self-skin payload (no donor profile). */
    private static final UUID NO_SKIN_UUID = new UUID(0L, 0L);

    // ── Public entry points ─────────────────────────────────────────────────────

    /** Premium: apply the player's REAL Mojang skin AND cape by verified UUID (fetched fresh each join). */
    public static void applyPremium(ServerPlayer player, UUID realMojangUuid) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String name = player.getGameProfile().getName();
        CoffeesAeroAuth.LOGGER.info("[Skin] {} PREMIUM — fetching real Mojang skin for {}", name, realMojangUuid);
        fetchTexturesValue(realMojangUuid).thenAccept(value -> {
            if (value == null) {                           // real skin + cape, as-is from Mojang
                CoffeesAeroAuth.LOGGER.warn("[Skin] {} — Mojang skin fetch returned nothing (Mojang unreachable from Apex, or no textures).", name);
                return;
            }
            server.execute(() -> {
                apply(player, value, realMojangUuid);
                saveSkin(player, value);
                CoffeesAeroAuth.LOGGER.info("[Skin] {} — applied real skin+cape ({} chars).", name, value.length());
            });
        });
    }

    /** Re-apply the custom skin saved on the profile. Call on join (server thread). */
    public static void applySaved(ServerPlayer player) {
        String saved = savedSkin(player);
        if (saved != null) apply(player, saved, profileIdFromTextures(saved));
    }

    /** {@code /skin <name>}: copy a Java account's public SKIN (cape stripped unless the player is
     *  cape-enabled — i.e. premium). Persisted. Callback on server thread. Offline-only in practice. */
    public static void applyByName(ServerPlayer player, String javaName, Consumer<String> onResult) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        boolean allowCape = capeEnabled(player); // offline players (default false) never get capes
        resolveName(javaName)
            .thenCompose(uuid -> uuid == null
                ? CompletableFuture.completedFuture((java.util.Map.Entry<UUID, String>) null)
                : fetchTexturesValue(uuid).thenApply(v ->
                    v == null ? null : java.util.Map.entry(uuid, v)))
            .thenAccept(donor -> server.execute(() -> {
                if (donor == null) { onResult.accept(null); return; }
                String applied = allowCape ? donor.getValue() : stripCape(donor.getValue());
                apply(player, applied, donor.getKey());
                saveSkin(player, applied);
                CoffeesAeroAuth.LOGGER.info("[Skin] {} — applied skin from '{}' (cape={}).",
                    player.getGameProfile().getName(), javaName, allowCape);
                onResult.accept(javaName);
            }));
    }

    /** {@code /skin reset}: clear the custom skin (offline → default; premium → real skin on next join). */
    public static void reset(ServerPlayer player) {
        player.getGameProfile().getProperties().removeAll("textures");
        saveSkin(player, null);
        refresh(player);
        sendSelfSkin(player, NO_SKIN_UUID, "");   // client reverts own view to the default skin
    }

    /** Join listener — re-applies the saved custom skin so it survives relogs. */
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            try { applySaved(sp); } catch (Exception ignored) {}
        }
    }

    // ── GameProfile mutation + client refresh (server thread only) ───────────────

    private static void apply(ServerPlayer player, String value, UUID skinProfileId) {
        PropertyMap props = player.getGameProfile().getProperties();
        props.removeAll("textures");
        props.put("textures", new Property("textures", value)); // unsigned OK (enforce-secure-profile=false)
        refresh(player);
        // The wearer's OWN view: vanilla filters non-"secure" skins for the local player
        // (PlayerInfo.createSkinLookup: skin.secure() || !isLocalPlayer), and on an offline-UUID
        // backend the signature can never validate — so no rebroadcast/respawn can ever update the
        // wearer's own model. CoffeesAeroCore on the client handles this payload and installs its own
        // lookup instead. Sent AFTER refresh() so it lands on the re-created player-info entry.
        sendSelfSkin(player, skinProfileId != null ? skinProfileId : NO_SKIN_UUID, value);
    }

    /** Rebroadcasts the player-info entry so all OTHER clients re-read the skin. The wearer's own
     *  client is updated by the {@code aerosmp:self_skin} payload (see {@link #apply}). */
    private static void refresh(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        server.getPlayerList().broadcastAll(
            ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
    }

    /** Sends the own-view skin payload; silently skipped for clients without CoffeesAeroCore. */
    private static void sendSelfSkin(ServerPlayer player, UUID skinProfileId, String value) {
        try {
            var payload = new com.coffeesaerosmp.auth.network.SelfSkinPayload(skinProfileId, value == null ? "" : value);
            if (player.connection.hasChannel(payload.type())) {
                player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload));
                CoffeesAeroAuth.LOGGER.info("[Skin] {} — sent self_skin payload (profile {}).",
                    player.getGameProfile().getName(), skinProfileId);
            } else {
                CoffeesAeroAuth.LOGGER.warn("[Skin] {} — client lacks aerosmp:self_skin channel (no CoffeesAeroCore?); own view will show default.",
                    player.getGameProfile().getName());
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Skin] self_skin payload failed for {}: {}",
                player.getGameProfile().getName(), e.toString());
        }
    }

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

    // ── Profile helpers ──────────────────────────────────────────────────────────

    private static boolean capeEnabled(ServerPlayer player) {
        try {
            if (CoffeesAeroAuth.AUTH_MANAGER == null) return false;
            PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
            return p != null && p.capeEnabled;
        } catch (Exception e) { return false; }
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

    private static String savedSkin(ServerPlayer player) {
        try {
            if (CoffeesAeroAuth.AUTH_MANAGER == null) return null;
            PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
            return (p != null && p.skinUrl != null && !p.skinUrl.isBlank()) ? p.skinUrl : null;
        } catch (Exception e) { return null; }
    }

    private static void saveSkin(ServerPlayer player, String value) {
        try {
            if (CoffeesAeroAuth.AUTH_MANAGER == null) return;
            PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
            if (p == null) return;
            p.skinUrl = value;
            CoffeesAeroAuth.AUTH_MANAGER.getStore().save(p);
        } catch (Exception ignored) {}
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
