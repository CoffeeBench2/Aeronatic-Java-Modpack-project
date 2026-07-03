package com.coffeesaerosmp.skins.client;

import com.coffeesaerosmp.skins.CoffeesAeroSkins;
import com.coffeesaerosmp.skins.mixin.PlayerInfoAccessor;
import com.coffeesaerosmp.skins.network.SkinSyncPayload;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of {@code aerosmp:skin_sync}: renders the server-assigned skin for ANY player — the
 * local one included, which vanilla otherwise refuses on offline-UUID backends
 * ({@code PlayerInfo.createSkinLookup} drops non-"secure" skins for the local player).
 *
 * <p>Entries are applied by swapping the player-info entry's skin supplier (via
 * {@link PlayerInfoAccessor}); the skin itself still loads through vanilla's SkinManager under the
 * DONOR's UUID, so no local-player filtering or render-mod first-render cache can pin the default
 * skin. A payload can arrive before its player-info entry exists (login, or the entry being
 * recreated by the server's rebroadcast), so application is retried once per client tick — and every
 * applied entry is re-armed automatically if its {@code PlayerInfo} instance is ever replaced.</p>
 */
public final class SkinSyncHandler {

    private SkinSyncHandler() {}

    /** One applied skin: the payload plus the exact PlayerInfo instance we patched. */
    private record Applied(SkinSyncPayload payload, PlayerInfo patched) {}

    /** Entries waiting for their player-info entry to exist (network thread → tick thread). */
    private static final Map<UUID, SkinSyncPayload> PENDING = new ConcurrentHashMap<>();

    /** Entries already applied — watched each tick so a recreated entry gets re-patched. */
    private static final Map<UUID, Applied> APPLIED = new ConcurrentHashMap<>();

    /** Network handler (registered playToClient). Just parks the payload for the tick loop. */
    public static void onPayload(SkinSyncPayload payload, IPayloadContext context) {
        PENDING.put(payload.playerId(), payload);
        APPLIED.remove(payload.playerId());   // superseded — re-apply from scratch
        CoffeesAeroSkins.LOGGER.info("[Skins] skin_sync for {} (donor {}, {} chars).",
            payload.playerId(), payload.donorId(), payload.texturesValue().length());
    }

    /** Client tick — applies pending entries and re-arms any whose PlayerInfo was recreated. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty() && APPLIED.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;                                        // still logging in — retry

        // Re-arm: if the server (or anything else) recreated a player-info entry after we patched it,
        // the fresh entry has vanilla's filtered lookup again — queue the same payload once more.
        for (Iterator<Map.Entry<UUID, Applied>> it = APPLIED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Applied> e = it.next();
            PlayerInfo current = conn.getPlayerInfo(e.getKey());
            if (current == null) { it.remove(); continue; }              // player left
            if (current != e.getValue().patched()) {
                PENDING.putIfAbsent(e.getKey(), e.getValue().payload());
                it.remove();
            }
        }

        for (Iterator<Map.Entry<UUID, SkinSyncPayload>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, SkinSyncPayload> e = it.next();
            PlayerInfo info = conn.getPlayerInfo(e.getKey());
            if (info == null) continue;                                  // entry not synced yet — retry
            apply(mc, info, e.getValue());
            APPLIED.put(e.getKey(), new Applied(e.getValue(), info));
            it.remove();
        }
    }

    private static void apply(Minecraft mc, PlayerInfo info, SkinSyncPayload payload) {
        if (payload.texturesValue().isEmpty()) {
            // Revert to the default skin (e.g. /skin reset).
            PlayerSkin def = DefaultPlayerSkin.get(payload.playerId());
            ((PlayerInfoAccessor) info).aeroskins$setSkinLookup(() -> def);
            return;
        }
        // Resolve through vanilla's SkinManager under the skin DONOR's profile — the donor UUID is
        // never the local (offline) UUID, so nothing downstream treats it as the filtered local case.
        GameProfile donorProfile = new GameProfile(payload.donorId(), info.getProfile().getName());
        donorProfile.getProperties().put("textures", new Property("textures", payload.texturesValue()));
        CompletableFuture<PlayerSkin> future = mc.getSkinManager().getOrLoad(donorProfile);

        PlayerSkin fallback = DefaultPlayerSkin.get(payload.donorId());
        ((PlayerInfoAccessor) info).aeroskins$setSkinLookup(() -> future.getNow(fallback));
    }

    /** Clears all state when leaving a world (stale entries must not leak across servers). */
    public static void reset() {
        PENDING.clear();
        APPLIED.clear();
    }
}
