package com.coffeesaerosmp.core.cosmetics;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code aerosmp:cosmetics} — server → client: who wears what. AeroCore draws every trail, contrail, wing and aura
 * itself from this, attached to the player as the client sees them, so nothing streams per tick.
 *
 * <p>{@code full} = replace everything the client knows; otherwise the entries are upserts and {@code removed}
 * are players to forget. An entry with {@code self} set is the receiver's private try-on preview of their own
 * look; it overrides their public entry until a later {@code self} entry clears it (all ids null).
 * {@code flags}: bit 0 trails on, bit 1 contrails on, bit 2 paused for server load.
 *
 * <p>Mirror of CoffeesAeroAuth {@code network.CosmeticsPayload}. Field order is the wire format.
 */
public record CosmeticsPayload(boolean full, int flags, List<Entry> entries, List<UUID> removed, List<ShipFit> ships)
        implements CustomPacketPayload {

    /** A contrail fitted to a ship (by its owner): shown whenever that ship flies, whoever is aboard. */
    public record ShipFit(UUID ship, String contrail) {}

    /** Without ship fits: {@code ships == null} means "unchanged since the last payload". */
    public CosmeticsPayload(boolean full, int flags, List<Entry> entries, List<UUID> removed) {
        this(full, flags, entries, removed, null);
    }

    public static final int TRAILS_ON = 1, CONTRAILS_ON = 2, PAUSED = 4;

    /** One player's look. Ids are catalog ids or null. */
    public record Entry(UUID player, String trail, String contrail, String wings, String aura, boolean hidden, boolean self) {}

    public static final Type<CosmeticsPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("aerosmp", "cosmetics"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CosmeticsPayload> STREAM_CODEC =
        StreamCodec.of(CosmeticsPayload::write, CosmeticsPayload::read);

    private static void write(RegistryFriendlyByteBuf b, CosmeticsPayload p) {
        b.writeBoolean(p.full);
        b.writeVarInt(p.flags);
        b.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            b.writeUUID(e.player());
            str(b, e.trail());
            str(b, e.contrail());
            str(b, e.wings());
            str(b, e.aura());
            b.writeByte((e.hidden() ? 1 : 0) | (e.self() ? 2 : 0));
        }
        b.writeVarInt(p.removed.size());
        for (UUID u : p.removed) b.writeUUID(u);
        b.writeBoolean(p.ships != null);                  // the full list of ship fits, when it changed
        if (p.ships != null) {
            b.writeVarInt(p.ships.size());
            for (ShipFit f : p.ships) { b.writeUUID(f.ship()); str(b, f.contrail()); }
        }
    }

    private static CosmeticsPayload read(RegistryFriendlyByteBuf b) {
        boolean full = b.readBoolean();
        int flags = b.readVarInt();
        int n = Math.min(b.readVarInt(), 4096);
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID u = b.readUUID();
            String t = str(b), c = str(b), w = str(b), a = str(b);
            int bits = b.readByte();
            entries.add(new Entry(u, t, c, w, a, (bits & 1) != 0, (bits & 2) != 0));
        }
        int r = Math.min(b.readVarInt(), 4096);
        List<UUID> removed = new ArrayList<>(r);
        for (int i = 0; i < r; i++) removed.add(b.readUUID());
        List<ShipFit> ships = null;
        if (b.readBoolean()) {
            int sn = Math.min(b.readVarInt(), 8192);
            ships = new ArrayList<>(sn);
            for (int i = 0; i < sn; i++) ships.add(new ShipFit(b.readUUID(), str(b)));
        }
        return new CosmeticsPayload(full, flags, entries, removed, ships);
    }

    private static void str(RegistryFriendlyByteBuf b, String s) { b.writeUtf(s == null ? "" : s, 64); }

    private static String str(RegistryFriendlyByteBuf b) {
        String s = b.readUtf(64);
        return s.isEmpty() ? null : s;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
