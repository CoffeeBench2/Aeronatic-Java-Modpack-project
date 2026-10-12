package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shop, Coffee Beans and cosmetics are for PREMIUM accounts only (owner 2026-10-12). An offline account that
 * opens any of it gets a friendly "get your own copy of Minecraft" message; nothing else about the server changes
 * for them.
 *
 * <p>Premium = {@code players.account_type}, which the gate sets after Mojang verified the session. The uuid can't
 * tell us (every account here is name-derived), and a premium buyer is also the only kind Tebex can deliver to.
 */
public final class PremiumGate {

    private PremiumGate() {}

    /** GameTest hook: mock players are offline accounts; tests add them here to exercise the premium paths. */
    public static final Set<UUID> TEST_PREMIUM = ConcurrentHashMap.newKeySet();

    public static boolean isPremium(ServerPlayer p) {
        if (p == null) return false;
        if (TEST_PREMIUM.contains(p.getUUID())) return true;
        var store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile prof = store == null ? null : store.get(p.getUUID());
        return prof != null && prof.isPremium();
    }

    /**
     * True when the player may use the shop. Otherwise sends the explanation and returns false.
     * Call at every entry point: /shop, /beans, /cosmetics, /buy and the GUI.
     */
    public static boolean check(ServerPlayer p) {
        if (isPremium(p)) return true;
        p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§6☕ The shop is for premium Minecraft accounts."));
        p.sendSystemMessage(Component.literal("§7Coffee Beans, ranks and cosmetics need your own copy of Minecraft. "
            + "Get one at ").append(Component.literal("§bminecraft.net").setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://www.minecraft.net/store/minecraft-java-bedrock-edition-pc"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open the official Minecraft store")))))
            .append(Component.literal("§7, then join with that account to unlock the shop.")));
        p.sendSystemMessage(Component.literal("§8Everything else on the server stays exactly the same for you."));
        return false;
    }
}
