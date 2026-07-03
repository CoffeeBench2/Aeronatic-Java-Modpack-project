package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.mixin.PlayerInfoAccessor;
import com.coffeesaerosmp.core.network.SelfSkinPayload;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Applies the {@code aerosmp:self_skin} payload: renders the server-assigned skin on the LOCAL player.
 *
 * <p>Vanilla's {@code PlayerInfo.createSkinLookup} refuses non-"secure" skins for the local player
 * (offline-UUID backends can never produce a valid signature for the offline profile), so the wearer
 * sees the default skin no matter what the server rebroadcasts. This handler builds a profile around
 * the REAL owner UUID from the payload (premium Mojang UUID / skin donor), loads it through vanilla's
 * SkinManager, and swaps the local player-info entry's skin supplier via {@link PlayerInfoAccessor}.</p>
 *
 * <p>The payload can arrive while the client is still logging in (player/entry not ready), so the
 * apply is retried once per client tick until it lands — client-side, so it can't miss the way the
 * old server-side timers did.</p>
 */
public final class SelfSkinHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile SelfSkinPayload pending;

    private SelfSkinHandler() {}

    /** Network handler (registered playToClient). Just parks the payload for the tick loop. */
    public static void onPayload(SelfSkinPayload payload, IPayloadContext context) {
        pending = payload;
        LOGGER.info("[AeroCore] Received self_skin payload (profile {}, {} chars).",
            payload.skinProfileId(), payload.texturesValue().length());
    }

    /** Client tick — applies a parked payload as soon as the local player-info entry exists. */
    public static void onClientTick(ClientTickEvent.Post event) {
        SelfSkinPayload payload = pending;
        if (payload == null) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.player == null) return;                      // still logging in — retry
        PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
        if (info == null) return;                                           // entry not synced yet — retry

        pending = null;

        if (payload.texturesValue().isEmpty()) {
            // Revert to the default skin (e.g. /skin reset).
            PlayerSkin def = DefaultPlayerSkin.get(mc.player.getUUID());
            ((PlayerInfoAccessor) info).aerocore$setSkinLookup(() -> def);
            LOGGER.info("[AeroCore] Own skin reverted to default.");
            return;
        }

        // Resolve through vanilla's SkinManager under the skin OWNER's profile — the owner UUID is
        // never the local (offline) UUID, so nothing downstream treats it as the filtered local case.
        GameProfile skinProfile = new GameProfile(payload.skinProfileId(), info.getProfile().getName());
        skinProfile.getProperties().put("textures", new Property("textures", payload.texturesValue()));
        CompletableFuture<PlayerSkin> future = mc.getSkinManager().getOrLoad(skinProfile);

        PlayerSkin fallback = DefaultPlayerSkin.get(payload.skinProfileId());
        ((PlayerInfoAccessor) info).aerocore$setSkinLookup(() -> future.getNow(fallback));
        LOGGER.info("[AeroCore] Own-view skin lookup installed (profile {}).", payload.skinProfileId());
    }

    /** Clears any parked payload when leaving a world (stale payloads must not leak across servers). */
    public static void reset() {
        pending = null;
    }
}
