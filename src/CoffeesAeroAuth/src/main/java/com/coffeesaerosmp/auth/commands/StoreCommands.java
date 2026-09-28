package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.store.Entitlements;
import com.coffeesaerosmp.auth.store.MojangIds;
import com.coffeesaerosmp.auth.store.Palette;
import com.coffeesaerosmp.auth.store.Rank;
import com.coffeesaerosmp.auth.store.StoreGrants;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /cosmetics} and {@code /buy} for players, and {@code /aerostore} for admins and Tebex.
 *
 * <h2>Why the grant surface is a command at all</h2>
 * Tebex delivers a purchase by <b>running a server command</b>. So {@code /aerostore grant …} is not a
 * convenience — it is the payment integration. Two consequences shape it:
 * <ul>
 *   <li>It takes a <b>Mojang uuid</b>, not a name. Tebex knows the buyer by uuid on an online-mode
 *       store, and a name would reintroduce the rename bug that makes this server's identities move.</li>
 *   <li>It must be safe to run twice. Tebex retries, so every grant is idempotent in
 *       {@link StoreGrants}.</li>
 * </ul>
 *
 * <p>All DB work is pushed onto {@link AsyncIo} — a Tebex delivery must never block the tick loop, and
 * the reply is sent back on the server thread once the write lands.
 */
public final class StoreCommands {

    private static final int OP = 2;

    private StoreCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerCosmetics(dispatcher);
        registerBuy(dispatcher);
        registerAdmin(dispatcher);
    }

    // ── player: /cosmetics ────────────────────────────────────────────────────

    private static void registerCosmetics(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cosmetics")
            .requires(src -> AuthConfig.STORE_ENABLED.get())
            .executes(ctx -> show(ctx.getSource()))
            .then(Commands.literal("colour")
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests((c, bld) -> { Palette.allColours().forEach(bld::suggest); return bld.buildFuture(); })
                    .executes(ctx -> select(ctx.getSource(),
                        Entitlements.COLOUR_PREFIX + StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("gradient")
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests((c, bld) -> { Palette.allGradients().forEach(bld::suggest); return bld.buildFuture(); })
                    .executes(ctx -> select(ctx.getSource(),
                        Entitlements.GRADIENT_PREFIX + StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("bold")
                .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                    .executes(ctx -> setBold(ctx.getSource(),
                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on")))))
            .then(Commands.literal("clear").executes(ctx -> select(ctx.getSource(), null)))
            .then(Commands.literal("title")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> submitText(ctx.getSource(), "chat_title",
                        StringArgumentType.getString(ctx, "text")))))
            .then(Commands.literal("joinmsg")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> submitText(ctx.getSource(), "join_message",
                        StringArgumentType.getString(ctx, "text")))))
        );
    }

    private static int show(CommandSourceStack src) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }

        var snap = StoreState.get(p.getUUID());
        Rank rank = snap.effectiveRank();
        var sel = snap.liveSelection();

        src.sendSystemMessage(Component.literal("§8§m                                        "));
        src.sendSystemMessage(Component.literal(" §6☕ §eYour cosmetics"));
        src.sendSystemMessage(Component.literal(" §7Rank: " + (rank == Rank.NONE
            ? "§8none" : rank.badge() + "§f" + rank.displayName())));
        if (snap.lapsed()) {
            src.sendSystemMessage(Component.literal(
                " §c⚠ Your subscription has ended §7— rank cosmetics are off, anything you bought outright is kept."));
        }
        src.sendSystemMessage(Component.literal(" §7Name style: §f"
            + (sel.nameStyle() == null ? "§8default" : sel.nameStyle())
            + (sel.bold() ? " §7+ §fbold" : "")));
        if (sel.chatTitle() != null)   src.sendSystemMessage(Component.literal(" §7Title: §f" + sel.chatTitle()));
        if (sel.joinMessage() != null) src.sendSystemMessage(Component.literal(" §7Join message: §f" + sel.joinMessage()));

        // Show what they may pick, not the whole catalogue — a list of things you cannot use reads as
        // a paywall in the middle of your own settings screen.
        StringBuilder colours = new StringBuilder();
        for (String c : Palette.allColours()) {
            if (Entitlements.mayUse(Entitlements.COLOUR_PREFIX + c, rank, snap.owned())) {
                colours.append(Palette.code(c)).append(c).append("§7 ");
            }
        }
        src.sendSystemMessage(Component.literal(" §7Colours: "
            + (colours.isEmpty() ? "§8none yet" : colours.toString())));

        StringBuilder grads = new StringBuilder();
        for (String g : Palette.allGradients()) {
            if (Entitlements.mayUse(Entitlements.GRADIENT_PREFIX + g, rank, snap.owned())) {
                grads.append("§f").append(g).append("§7 ");
            }
        }
        src.sendSystemMessage(Component.literal(" §7Gradients: "
            + (grads.isEmpty() ? "§8none yet" : grads.toString())));
        src.sendSystemMessage(Component.literal(" §8/cosmetics colour <id> §7· §8/cosmetics gradient <id> §7· §8/cosmetics clear"));
        src.sendSystemMessage(Component.literal("§8§m                                        "));
        return 1;
    }

    private static int select(CommandSourceStack src, String id) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }
        UUID local = p.getUUID();
        var snap = StoreState.get(local);

        if (id != null && !Entitlements.mayUse(id, snap.effectiveRank(), snap.owned())) {
            // Distinguish "not yours" from "not a thing", because they need different next steps.
            boolean exists = id.startsWith(Entitlements.COLOUR_PREFIX)
                ? Palette.isColour(id.substring(Entitlements.COLOUR_PREFIX.length()))
                : Palette.isGradient(id.substring(Entitlements.GRADIENT_PREFIX.length()));
            src.sendFailure(Component.literal(exists
                ? "§cYou don't have that one yet. §7See §f/buy§7 or §f/cosmetics§7 for what you can use."
                : "§cNo such cosmetic. §7Try §f/cosmetics§7 to see yours."));
            return 0;
        }

        boolean bold = snap.liveSelection().bold();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) {
                reply(p, "§cYour account isn't linked for cosmetics yet. §7Rejoin once and try again.");
                return;
            }
            boolean ok = StoreGrants.setNameSelection(mojang, id, bold);
            reply(p, ok
                ? (id == null ? "§aName style cleared." : "§aName style set to §f" + id + "§a.")
                : "§cCould not save that right now — try again in a moment.");
        });
        return 1;
    }

    private static int setBold(CommandSourceStack src, boolean on) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }
        UUID local = p.getUUID();
        var snap = StoreState.get(local);
        if (on && !Entitlements.mayUse(Entitlements.BOLD_ID, snap.effectiveRank(), snap.owned())) {
            src.sendFailure(Component.literal("§cBold isn't unlocked for you yet. §7See §f/buy§7."));
            return 0;
        }
        String style = snap.liveSelection().nameStyle();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) { reply(p, "§cYour account isn't linked for cosmetics yet."); return; }
            boolean ok = StoreGrants.setNameSelection(mojang, style, on);
            reply(p, ok ? (on ? "§aBold on." : "§aBold off.") : "§cCould not save that right now.");
        });
        return 1;
    }

    /**
     * Free text goes to moderation, never straight to the display. The player is told it is pending so
     * "nothing happened" does not read as a broken purchase.
     */
    private static int submitText(CommandSourceStack src, String which, String text) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }
        var snap = StoreState.get(p.getUUID());
        Rank rank = snap.effectiveRank();

        boolean allowed = which.equals("chat_title")
            ? Entitlements.has(rank, Entitlements.Capability.CHAT_TITLE)
            : Entitlements.mayUse(Entitlements.JOIN_MESSAGE_ID, rank, snap.owned());
        if (!allowed) {
            src.sendFailure(Component.literal("§cThat isn't unlocked for you yet. §7See §f/buy§7."));
            return 0;
        }
        int cap = which.equals("chat_title") ? 32 : 128;
        String clean = text == null ? "" : text.replace('§', ' ').trim();
        if (clean.isEmpty() || clean.length() > cap) {
            src.sendFailure(Component.literal("§cKeep it between 1 and " + cap + " characters, with no § codes."));
            return 0;
        }
        UUID local = p.getUUID();
        AsyncIo.submit(() -> {
            UUID mojang = MojangIds.toMojang(local).orElse(null);
            if (mojang == null) { reply(p, "§cYour account isn't linked for cosmetics yet."); return; }
            boolean ok = StoreGrants.submitTextForApproval(mojang, which, clean);
            reply(p, ok
                ? "§eSubmitted for approval. §7It appears once a moderator has cleared it."
                : "§cCould not submit that right now.");
        });
        return 1;
    }

    // ── player: /buy ──────────────────────────────────────────────────────────

    private static void registerBuy(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("buy")
            .requires(src -> AuthConfig.STORE_ENABLED.get())
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                ServerPlayer p;
                try { p = src.getPlayerOrException(); } catch (Exception e) { return 0; }

                String url = AuthConfig.STORE_URL.get();
                if (url == null || url.isBlank()) {
                    src.sendFailure(Component.literal("§cThe store isn't set up yet."));
                    return 0;
                }
                String email = AuthConfig.STORE_SUPPORT_EMAIL.get();

                src.sendSystemMessage(Component.literal("§8§m                                        "));
                src.sendSystemMessage(Component.literal(" §6☕ §eCoffees Aero SMP store"));
                // 🔑 Show the account the purchase must be made for. Delivery is by Mojang uuid, so
                // buying under a friend's name delivers to the friend — and that is a refund request,
                // not a fixable mistake.
                src.sendSystemMessage(Component.literal(
                    " §7Buy for: §f" + p.getGameProfile().getName()
                    + " §8— use exactly this name at checkout"));
                src.sendSystemMessage(Component.literal(" §7Everything sold is cosmetic. No gameplay advantages."));
                src.sendSystemMessage(Component.literal("")
                    .append(Component.literal(" §b§n" + url).setStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)))));
                if (email != null && !email.isBlank()) {
                    src.sendSystemMessage(Component.literal(" §8Support: " + email));
                }
                src.sendSystemMessage(Component.literal(
                    " §8NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."));
                src.sendSystemMessage(Component.literal("§8§m                                        "));
                return 1;
            }));
    }

    // ── admin + Tebex: /aerostore ─────────────────────────────────────────────

    private static void registerAdmin(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aerostore")
            .requires(src -> src.hasPermission(OP))

            .then(Commands.literal("grant")
                .then(Commands.literal("rank")
                    .then(Commands.argument("mojangUuid", StringArgumentType.word())
                        .then(Commands.argument("rank", StringArgumentType.word())
                            .suggests((c, b) -> { for (Rank r : Rank.values()) if (r != Rank.NONE) b.suggest(r.name()); return b.buildFuture(); })
                            .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(ctx -> {
                                    UUID id = parseUuid(ctx.getSource(), StringArgumentType.getString(ctx, "mojangUuid"));
                                    if (id == null) return 0;
                                    Rank r = Rank.parse(StringArgumentType.getString(ctx, "rank"));
                                    if (r == Rank.NONE) { ctx.getSource().sendFailure(Component.literal("§cUnknown rank.")); return 0; }
                                    int days = IntegerArgumentType.getInteger(ctx, "days");
                                    var src = ctx.getSource();
                                    AsyncIo.submit(() -> {
                                        var o = StoreGrants.grantRank(id, r, days, "cmd");
                                        report(src, o, r.displayName() + " x" + days + "d -> " + id);
                                    });
                                    return 1;
                                })))))
                .then(Commands.literal("cosmetic")
                    .then(Commands.argument("mojangUuid", StringArgumentType.word())
                        .then(Commands.argument("cosmeticId", StringArgumentType.word())
                            .executes(ctx -> {
                                UUID id = parseUuid(ctx.getSource(), StringArgumentType.getString(ctx, "mojangUuid"));
                                if (id == null) return 0;
                                String cid = StringArgumentType.getString(ctx, "cosmeticId");
                                var src = ctx.getSource();
                                AsyncIo.submit(() -> {
                                    var o = StoreGrants.grantCosmetic(id, cid, "BEANS", "cmd");
                                    report(src, o, cid + " -> " + id);
                                });
                                return 1;
                            })))))

            .then(Commands.literal("revoke")
                .then(Commands.literal("rank")
                    .then(Commands.argument("mojangUuid", StringArgumentType.word())
                        .executes(ctx -> {
                            UUID id = parseUuid(ctx.getSource(), StringArgumentType.getString(ctx, "mojangUuid"));
                            if (id == null) return 0;
                            var src = ctx.getSource();
                            AsyncIo.submit(() -> report(src, StoreGrants.expireRank(id), "rank expired for " + id));
                            return 1;
                        })))
                .then(Commands.literal("cosmetic")
                    .then(Commands.argument("mojangUuid", StringArgumentType.word())
                        .then(Commands.argument("cosmeticId", StringArgumentType.word())
                            .executes(ctx -> {
                                UUID id = parseUuid(ctx.getSource(), StringArgumentType.getString(ctx, "mojangUuid"));
                                if (id == null) return 0;
                                String cid = StringArgumentType.getString(ctx, "cosmeticId");
                                var src = ctx.getSource();
                                AsyncIo.submit(() -> report(src, StoreGrants.revokeCosmetic(id, cid), cid + " revoked for " + id));
                                return 1;
                            })))))

            // Replay anything parked because the buyer could not be resolved at purchase time.
            .then(Commands.literal("pending")
                .then(Commands.argument("mojangUuid", StringArgumentType.word())
                    .executes(ctx -> {
                        UUID id = parseUuid(ctx.getSource(), StringArgumentType.getString(ctx, "mojangUuid"));
                        if (id == null) return 0;
                        var src = ctx.getSource();
                        AsyncIo.submit(() -> {
                            int n = StoreGrants.applyPending(id);
                            src.getServer().execute(() -> src.sendSystemMessage(
                                Component.literal("§7Applied §f" + n + "§7 parked grant(s) for " + id)));
                        });
                        return 1;
                    })))

            // The launch-readiness number from the Phase 0 findings.
            .then(Commands.literal("status").executes(ctx -> {
                var src = ctx.getSource();
                AsyncIo.submit(() -> {
                    int unresolved = MojangIds.unresolvedPremiumCount();
                    src.getServer().execute(() -> {
                        src.sendSystemMessage(Component.literal("§7Store enabled: §f" + AuthConfig.STORE_ENABLED.get()));
                        src.sendSystemMessage(Component.literal("§7Premium rows with no mojang_uuid: §f"
                            + (unresolved < 0 ? "unknown (DB down)" : unresolved)));
                        src.sendSystemMessage(Component.literal(
                            "§8Those players cannot receive a delivery until they rejoin once."));
                    });
                });
                return 1;
            }))

            .then(Commands.literal("approve")
                .then(Commands.argument("mojangUuid", StringArgumentType.word())
                    .then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> { b.suggest("chat_title"); b.suggest("join_message"); return b.buildFuture(); })
                        .executes(ctx -> textDecision(ctx.getSource(),
                            StringArgumentType.getString(ctx, "mojangUuid"),
                            StringArgumentType.getString(ctx, "what"), true)))))
            .then(Commands.literal("reject")
                .then(Commands.argument("mojangUuid", StringArgumentType.word())
                    .then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> { b.suggest("chat_title"); b.suggest("join_message"); return b.buildFuture(); })
                        .executes(ctx -> textDecision(ctx.getSource(),
                            StringArgumentType.getString(ctx, "mojangUuid"),
                            StringArgumentType.getString(ctx, "what"), false)))))
        );
    }

    private static int textDecision(CommandSourceStack src, String rawUuid, String what, boolean approve) {
        UUID id = parseUuid(src, rawUuid);
        if (id == null) return 0;
        AsyncIo.submit(() -> {
            boolean ok = approve ? StoreGrants.approveText(id, what) : StoreGrants.rejectText(id, what);
            src.getServer().execute(() -> src.sendSystemMessage(Component.literal(
                ok ? "§a" + (approve ? "Approved" : "Rejected") + " " + what + " for " + id
                   : "§cNothing pending for " + id)));
        });
        return 1;
    }

    private static UUID parseUuid(CommandSourceStack src, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // Tebex sends a uuid; a name here means the package was configured with {name} instead of
            // {uuid}, which would silently break on the buyer's next rename. Say so explicitly.
            src.sendFailure(Component.literal(
                "§cThat is not a UUID. §7Tebex packages must deliver with the player's UUID, not their name."));
            return null;
        }
    }

    private static void report(CommandSourceStack src, StoreGrants.Outcome o, String what) {
        String msg = switch (o) {
            case APPLIED -> "§aApplied: §f" + what;
            case QUEUED  -> "§eQueued: §f" + what + " §7— the buyer has never joined as premium, so it "
                            + "will apply on their next login.";
            case FAILED  -> "§cFailed: §f" + what;
        };
        var server = src.getServer();
        if (server != null) server.execute(() -> src.sendSystemMessage(Component.literal(msg)));
    }

    private static void report(CommandSourceStack src, boolean ok, String what) {
        report(src, ok ? StoreGrants.Outcome.APPLIED : StoreGrants.Outcome.FAILED, what);
    }

    private static void reply(ServerPlayer p, String msg) {
        var server = p.getServer();
        if (server == null) return;
        server.execute(() -> {
            if (p.hasDisconnected()) return;
            p.sendSystemMessage(Component.literal(TextUtil.PREFIX + msg));
        });
    }
}
