package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.cosmetics.TryOn;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** GameTest access to the shop's package-private model. */
public final class ShopGuiProbe {

    private ShopGuiProbe() {}

    /** "id=RANK" (or "=none") for every trail, as the shop computes it. */
    public static List<String> trailRanks(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (var e : ShopGui.entries(p, ShopGui.Category.TRAILS)) {
            out.add(e.id() + "=" + (e.rankNeeded() == null ? "none" : e.rankNeeded().name()));
        }
        return out;
    }

    public static long tryOnPackets() {
        return TryOn.packetsSent();
    }
}
