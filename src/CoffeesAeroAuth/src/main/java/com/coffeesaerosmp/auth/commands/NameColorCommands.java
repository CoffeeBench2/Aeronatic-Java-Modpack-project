package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.NameStyles;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Function;

/**
 * {@code /namecolor} — name customization, <b>ADMIN-CONTROLLED since 2026-07-27</b>.
 *
 * <p><b>Policy change (user decision 2026-07-27):</b> players no longer set their own name colours.
 * Previously only {@code rainbow} and {@code magic} carried {@code hasPermission(2)} while
 * {@code hex}, {@code style}, {@code preset}, {@code reset} and the bare colour name were open to
 * everyone — a partial gate that let any player recolour themselves. Now <b>every mutating branch
 * requires permission level 2</b>, and ops can apply <i>or remove</i> a colour <b>for another
 * player</b> via {@code /namecolor player &lt;target&gt; …}.</p>
 *
 * <p>{@code /namecolor show} stays open to everyone — it is read-only and only previews your own
 * name, which is a courtesy rather than a customization.</p>
 *
 * <p><b>Explicitly out of scope:</b> this does NOT touch {@code /clan color} (the team/clan tag
 * colour in {@link ProfileCommands}), which remains gated to the party owner. The restriction was
 * scoped to <i>individual</i> names, not team names.</p>
 *
 * <pre>
 *   /namecolor show                                    (anyone — preview your own name)
 *
 *   OP ONLY — act on yourself:
 *   /namecolor &lt;color&gt;                                 red, gold, aqua … (16 vanilla colours)
 *   /namecolor hex &lt;rrggbb&gt;                            any colour, e.g. ff8800
 *   /namecolor style &lt;flag&gt; &lt;on|off&gt;                   bold | italic | underline | strikethrough
 *   /namecolor magic &lt;on|off&gt;                          §k scramble
 *   /namecolor rainbow                                 animated RGB
 *   /namecolor preset save|load|delete &lt;name&gt; | list
 *   /namecolor reset                                   remove all styling
 *
 *   OP ONLY — act on another player:
 *   /namecolor player &lt;target&gt; &lt;color&gt;
 *   /namecolor player &lt;target&gt; hex &lt;rrggbb&gt;
 *   /namecolor player &lt;target&gt; style &lt;flag&gt; &lt;on|off&gt;
 *   /namecolor player &lt;target&gt; magic &lt;on|off&gt;
 *   /namecolor player &lt;target&gt; rainbow
 *   /namecolor player &lt;target&gt; reset                   REMOVE that player's colour
 *   /namecolor player &lt;target&gt; show
 * </pre>
 */
public final class NameColorCommands {

    private NameColorCommands() {}

    /** Permission level required for every mutating branch. */
    private static final int OP = 2;

    private static final List<String> STYLE_FLAGS =
        List.of("bold", "italic", "underline", "strikethrough");
    private static final List<String> ON_OFF = List.of("on", "off");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("namecolor")

            // ── open to everyone: read-only preview of your OWN name ──
            .then(Commands.literal("show")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!requireAuth(p)) return 0;
                    p.sendSystemMessage(preview(p));
                    return 1;
                }))

            // ── OP ONLY: act on another player ──
            .then(Commands.literal("player")
                .requires(src -> src.hasPermission(OP))
                .then(targetBranches()))

            // ── OP ONLY: act on yourself ──
            .then(Commands.literal("hex")
                .requires(src -> src.hasPermission(OP))
                .then(Commands.argument("rrggbb", StringArgumentType.word())
                    .executes(ctx -> runSelf(ctx.getSource(), p ->
                        NameStyles.setHex(p.getUUID(), StringArgumentType.getString(ctx, "rrggbb"))))))

            .then(Commands.literal("rainbow")
                .requires(src -> src.hasPermission(OP))
                .executes(ctx -> runSelf(ctx.getSource(), p -> {
                    NameStyles.setRainbow(p.getUUID());
                    return null;
                })))

            .then(Commands.literal("magic")
                .requires(src -> src.hasPermission(OP))
                .then(Commands.literal("on")
                    .executes(ctx -> runSelf(ctx.getSource(), p -> NameStyles.setFlag(p.getUUID(), "magic", true))))
                .then(Commands.literal("off")
                    .executes(ctx -> runSelf(ctx.getSource(), p -> NameStyles.setFlag(p.getUUID(), "magic", false)))))

            .then(Commands.literal("style")
                .requires(src -> src.hasPermission(OP))
                .then(Commands.argument("flag", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(STYLE_FLAGS, b))
                    .then(Commands.argument("state", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(ON_OFF, b))
                        .executes(ctx -> runSelf(ctx.getSource(), p ->
                            applyStyle(p, StringArgumentType.getString(ctx, "flag"),
                                          StringArgumentType.getString(ctx, "state")))))))

            .then(Commands.literal("preset")
                .requires(src -> src.hasPermission(OP))
                .then(Commands.literal("save")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> runSelf(ctx.getSource(), p ->
                            NameStyles.savePreset(p.getUUID(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("load")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            ServerPlayer p = ctx.getSource().getPlayer();
                            if (p != null) SharedSuggestionProvider.suggest(
                                java.util.Arrays.asList(NameStyles.presetList(p.getUUID()).split(", ")), b);
                            return b.buildFuture();
                        })
                        .executes(ctx -> runSelf(ctx.getSource(), p ->
                            NameStyles.loadPreset(p.getUUID(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("delete")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> runSelf(ctx.getSource(), p ->
                            NameStyles.deletePreset(p.getUUID(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        if (!requireAuth(p)) return 0;
                        p.sendSystemMessage(TextUtil.info("Your presets: §e" + NameStyles.presetList(p.getUUID())));
                        return 1;
                    })))

            .then(Commands.literal("reset")
                .requires(src -> src.hasPermission(OP))
                .executes(ctx -> runSelf(ctx.getSource(), p -> {
                    NameStyles.reset(p.getUUID());
                    return null;
                })))

            // Bare colour name LAST so the literals above always win the parse.
            .then(Commands.argument("color", StringArgumentType.word())
                .requires(src -> src.hasPermission(OP))
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(NameStyles.colorNames(), b))
                .executes(ctx -> runSelf(ctx.getSource(), p ->
                    NameStyles.setColor(p.getUUID(), StringArgumentType.getString(ctx, "color")))))
        );
    }

    /**
     * {@code /namecolor player <target> …} — the whole mutating set again, applied to the argument
     * player instead of the sender. A {@code player} literal is used (rather than making the target
     * the first argument) so it can never be ambiguous with the bare {@code <color>} argument.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> targetBranches() {
        return Commands.argument("target", EntityArgument.player())

            .then(Commands.literal("hex")
                .then(Commands.argument("rrggbb", StringArgumentType.word())
                    .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t ->
                        NameStyles.setHex(t.getUUID(), StringArgumentType.getString(ctx, "rrggbb"))))))

            .then(Commands.literal("rainbow")
                .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t -> {
                    NameStyles.setRainbow(t.getUUID());
                    return null;
                })))

            .then(Commands.literal("magic")
                .then(Commands.literal("on")
                    .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t ->
                        NameStyles.setFlag(t.getUUID(), "magic", true))))
                .then(Commands.literal("off")
                    .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t ->
                        NameStyles.setFlag(t.getUUID(), "magic", false)))))

            .then(Commands.literal("style")
                .then(Commands.argument("flag", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(STYLE_FLAGS, b))
                    .then(Commands.argument("state", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(ON_OFF, b))
                        .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t ->
                            applyStyle(t, StringArgumentType.getString(ctx, "flag"),
                                          StringArgumentType.getString(ctx, "state")))))))

            // REMOVE a player's colour entirely.
            .then(Commands.literal("reset")
                .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t -> {
                    NameStyles.reset(t.getUUID());
                    return null;
                })))

            .then(Commands.literal("show")
                .executes(ctx -> {
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "target");
                    ctx.getSource().sendSystemMessage(
                        TextUtil.info(t.getGameProfile().getName() + " looks like: ").copy().append(previewName(t)));
                    return 1;
                }))

            // Bare colour name LAST.
            .then(Commands.argument("color", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(NameStyles.colorNames(), b))
                .executes(ctx -> runOn(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), t ->
                    NameStyles.setColor(t.getUUID(), StringArgumentType.getString(ctx, "color")))));
    }

    private static String applyStyle(ServerPlayer p, String flag, String state) {
        if (flag.equalsIgnoreCase("magic"))            // magic has its own branch
            return "Use /namecolor magic on|off.";
        return NameStyles.setFlag(p.getUUID(), flag, state.equalsIgnoreCase("on"));
    }

    /** Auth-gate + apply to the SENDER + live preview in the confirmation line. */
    private static int runSelf(CommandSourceStack src, Function<ServerPlayer, String> action)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        if (!requireAuth(p)) return 0;
        String err = action.apply(p);
        if (err != null) {
            p.sendSystemMessage(TextUtil.info("§c" + err));
            return 0;
        }
        p.sendSystemMessage(preview(p));
        return 1;
    }

    /**
     * Apply to a TARGET player on an op's behalf. Reports to the sender (which may be console) and
     * also tells the target, so their name never changes invisibly to them.
     */
    private static int runOn(CommandSourceStack src, ServerPlayer target,
                             Function<ServerPlayer, String> action) {
        String err = action.apply(target);
        if (err != null) {
            src.sendSystemMessage(TextUtil.info("§c" + err));
            return 0;
        }
        src.sendSystemMessage(TextUtil.info("Updated §e" + target.getGameProfile().getName() + "§7 → ")
            .copy().append(previewName(target)));
        target.sendSystemMessage(TextUtil.info("An admin updated your name colour: ")
            .copy().append(previewName(target)));
        return 1;
    }

    private static Component preview(ServerPlayer p) {
        return TextUtil.info("Your name now looks like: ").copy().append(previewName(p));
    }

    /** Renders the player's styled display name from their current profile + style. */
    private static Component previewName(ServerPlayer p) {
        var profile = CoffeesAeroAuth.AUTH_MANAGER != null
            ? CoffeesAeroAuth.AUTH_MANAGER.getStore().get(p.getUUID()) : null;
        String display = profile != null && profile.displayName != null ? profile.displayName
            : (profile != null && profile.username != null ? profile.username : p.getGameProfile().getName());
        String username = profile != null ? profile.username : null;
        NameStyles.NameStyle s = NameStyles.current(p.getUUID(), username);
        return NameStyles.render(s, display);
    }

    private static boolean requireAuth(ServerPlayer player) {
        if (CoffeesAeroAuth.AUTH_MANAGER != null
            && CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) return true;
        player.sendSystemMessage(Component.literal(
            TextUtil.PREFIX + "§cLog in first: §a/login <password>"));
        return false;
    }
}
