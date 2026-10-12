package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.cosmetics.CosmeticEngine;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.CosmeticCatalog.Type;
import com.coffeesaerosmp.auth.store.Entitlements;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.MojangIds;
import com.coffeesaerosmp.auth.store.PreviewSelections;
import com.coffeesaerosmp.auth.store.StoreGrants;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 1.13.30 particle cosmetics on {@code /cosmetics}: trail, contrail, leave message, hide-others, plus the
 * ops-preview plumbing the name-style commands share. Ops (permission 2+) may use everything while the shop is off
 * (owner 2026-10-12); an op with no Mojang link gets session-only choices ({@link PreviewSelections}).
 */
public final class CosmeticCommands {

    private CosmeticCommands() {}

    /** Ops preview — checked live from the op level, so it works the instant someone is opped. */
    public static boolean preview(ServerPlayer p) {
        return p != null && p.hasPermissions(2);
    }

    /**
     * Persist a selection change. Linked players: {@code dbWrite} runs off-thread. Unlinked ops: the change goes
     * to the session-only preview map. Anyone else unlinked: refused.
     */
    static void persist(ServerPlayer p, UnaryOperator<Selection> change,
                        java.util.function.Predicate<UUID> dbWrite, String okMsg) {
        UUID local = p.getUUID();
        boolean preview = preview(p);
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) {
                if (!preview) { reply(p, "§cYour account isn't linked for cosmetics yet. §7Rejoin once and try again."); return; }
                Selection cur = PreviewSelections.get(local).orElse(StoreState.selectionFor(local));
                PreviewSelections.put(local, change.apply(cur));
                reply(p, okMsg + " §8(preview — this session only)");
                return;
            }
            reply(p, dbWrite.test(mojang) ? okMsg : "§cCould not save that right now — try again in a moment.");
        });
    }

    static int setParticle(CommandSourceStack src, Type type, String raw) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }
        if (!com.coffeesaerosmp.auth.store.PremiumGate.check(p)) return 0;
        String prefix = type == Type.TRAIL ? Entitlements.TRAIL_PREFIX : Entitlements.CONTRAIL_PREFIX;
        String id = null;
        if (raw != null && !raw.equalsIgnoreCase("off")) {
            String name = raw.toLowerCase(Locale.ROOT);
            id = name.startsWith(prefix) ? name : prefix + name;
            var c = CosmeticCatalog.get(id);
            if (c.isEmpty() || c.get().type() != type) {
                src.sendFailure(Component.literal("§cNo such " + (type == Type.TRAIL ? "trail" : "contrail")
                    + ". §7Try §f/cosmetics§7 for the list."));
                return 0;
            }
            var snap = StoreState.get(p.getUUID());
            if (!Entitlements.mayUse(id, snap.effectiveRank(), snap.owned(), preview(p))) {
                src.sendFailure(Component.literal("§cYou don't have that one yet."));
                return 0;
            }
        }
        final String chosen = id;
        String label = chosen == null ? "off" : CosmeticCatalog.get(chosen).map(CosmeticCatalog.Cosmetic::label).orElse(chosen);
        String what = type == Type.TRAIL ? "Trail" : "Contrail";
        persist(p,
            s -> type == Type.TRAIL
                ? new Selection(s.nameStyle(), s.bold(), s.chatTitle(), s.joinMessage(), chosen, s.contrail(), s.leaveMessage())
                : new Selection(s.nameStyle(), s.bold(), s.chatTitle(), s.joinMessage(), s.trail(), chosen, s.leaveMessage()),
            mojang -> StoreGrants.setSlot(mojang, type == Type.TRAIL ? StoreGrants.Slot.TRAIL : StoreGrants.Slot.CONTRAIL, chosen),
            "§a" + what + ": §f" + label + (type == Type.CONTRAIL && chosen != null
                ? " §7(streams behind your ship once it flies faster than a run)" : ""));
        return 1;
    }

    static int hideOthers(CommandSourceStack src) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }
        boolean hidden = CosmeticEngine.toggleHideOthers(p.getUUID());
        src.sendSystemMessage(Component.literal(TextUtil.PREFIX + (hidden
            ? "§7Other players' trails and contrails are now §fhidden§7 for you (this session)."
            : "§7Other players' trails and contrails are §fshown§7 again.")));
        return 1;
    }

    /** Lines appended to /cosmetics: what is worn and what can be picked. */
    static void showParticles(CommandSourceStack src, ServerPlayer p) {
        Selection sel = StoreState.selectionFor(p.getUUID());
        var snap = StoreState.get(p.getUUID());
        boolean preview = preview(p);
        src.sendSystemMessage(Component.literal(" §7Trail: §f" + label(sel.trail()) + " §7· Contrail: §f" + label(sel.contrail())));
        if (sel.leaveMessage() != null) src.sendSystemMessage(Component.literal(" §7Leave message: §f" + sel.leaveMessage()));
        for (Type t : Type.values()) {
            StringBuilder sb = new StringBuilder();
            for (var c : CosmeticCatalog.all(t)) {
                if (Entitlements.mayUse(c.id(), snap.effectiveRank(), snap.owned(), preview)) {
                    sb.append("§f").append(c.id().substring(c.id().indexOf(':') + 1)).append("§7 ");
                }
            }
            src.sendSystemMessage(Component.literal(" §7" + (t == Type.TRAIL ? "Trails" : "Contrails") + ": "
                + (sb.isEmpty() ? "§8none yet" : sb.toString())));
        }
        src.sendSystemMessage(Component.literal(" §8/cosmetics trail <id|off> §7· §8contrail <id|off> §7· §8leavemsg <text> §7· §8hideothers"));
        if (preview) src.sendSystemMessage(Component.literal(" §8(op preview: everything unlocked for testing)"));
    }

    private static String label(String id) {
        if (id == null) return "§8off";
        return CosmeticCatalog.get(id).map(CosmeticCatalog.Cosmetic::label).orElse(id);
    }

    static void reply(ServerPlayer p, String msg) {
        var server = p.getServer();
        if (server == null) return;
        server.execute(() -> { if (!p.hasDisconnected()) p.sendSystemMessage(Component.literal(TextUtil.PREFIX + msg)); });
    }
}
