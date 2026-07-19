package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.NameStyles;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /namecolor} — player-facing name customization (2026-07-20, the generalization RainbowText
 * promised). Everyone (authenticated) gets the 16 vanilla colors, any hex color, format flags and
 * per-player presets. The flashy stuff — the animated RGB rainbow and the §k "magic" scramble
 * (whole name renders as cycling enchantment-glyphs) — is op-only: unreadable/animated names are a
 * moderation call, not a free cosmetic.
 *
 * <pre>
 *   /namecolor &lt;color&gt;                 red, gold, aqua … (16 vanilla colors)
 *   /namecolor hex &lt;rrggbb&gt;            any color, e.g. ff8800
 *   /namecolor style &lt;flag&gt; &lt;on|off&gt;   bold | italic | underline | strikethrough
 *   /namecolor magic &lt;on|off&gt;          §k scramble          (op)
 *   /namecolor rainbow                 animated RGB          (op)
 *   /namecolor preset save|load|delete &lt;name&gt;, preset list
 *   /namecolor show | reset
 * </pre>
 */
public final class NameColorCommands {

    private NameColorCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("namecolor")

            .then(Commands.literal("hex")
                .then(Commands.argument("rrggbb", StringArgumentType.word())
                    .executes(ctx -> run(ctx.getSource(), p ->
                        NameStyles.setHex(p.getUUID(), StringArgumentType.getString(ctx, "rrggbb"))))))

            .then(Commands.literal("rainbow")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> run(ctx.getSource(), p -> {
                    NameStyles.setRainbow(p.getUUID());
                    return null;
                })))

            .then(Commands.literal("magic")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("on")
                    .executes(ctx -> run(ctx.getSource(), p -> NameStyles.setFlag(p.getUUID(), "magic", true))))
                .then(Commands.literal("off")
                    .executes(ctx -> run(ctx.getSource(), p -> NameStyles.setFlag(p.getUUID(), "magic", false)))))

            .then(Commands.literal("style")
                .then(Commands.argument("flag", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        java.util.List.of("bold", "italic", "underline", "strikethrough"), b))
                    .then(Commands.argument("state", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(java.util.List.of("on", "off"), b))
                        .executes(ctx -> run(ctx.getSource(), p -> {
                            String flag = StringArgumentType.getString(ctx, "flag");
                            if (flag.equalsIgnoreCase("magic"))   // /namecolor magic is the op path
                                return "Use /namecolor magic on|off (op only).";
                            return NameStyles.setFlag(p.getUUID(), flag,
                                StringArgumentType.getString(ctx, "state").equalsIgnoreCase("on"));
                        })))))

            .then(Commands.literal("preset")
                .then(Commands.literal("save")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> run(ctx.getSource(), p ->
                            NameStyles.savePreset(p.getUUID(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("load")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            ServerPlayer p = ctx.getSource().getPlayer();
                            if (p != null) SharedSuggestionProvider.suggest(
                                java.util.Arrays.asList(NameStyles.presetList(p.getUUID()).split(", ")), b);
                            return b.buildFuture();
                        })
                        .executes(ctx -> run(ctx.getSource(), p ->
                            NameStyles.loadPreset(p.getUUID(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("delete")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> run(ctx.getSource(), p ->
                            NameStyles.deletePreset(p.getUUID(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        if (!requireAuth(p)) return 0;
                        p.sendSystemMessage(TextUtil.info("Your presets: §e" + NameStyles.presetList(p.getUUID())));
                        return 1;
                    })))

            .then(Commands.literal("reset")
                .executes(ctx -> run(ctx.getSource(), p -> {
                    NameStyles.reset(p.getUUID());
                    return null;
                })))

            .then(Commands.literal("show")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!requireAuth(p)) return 0;
                    p.sendSystemMessage(preview(p));
                    return 1;
                }))

            // Bare color name LAST so the literals above always win the parse.
            .then(Commands.argument("color", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(NameStyles.colorNames(), b))
                .executes(ctx -> run(ctx.getSource(), p ->
                    NameStyles.setColor(p.getUUID(), StringArgumentType.getString(ctx, "color")))))
        );
    }

    /** Auth-gate + apply + live preview in the confirmation line. */
    private static int run(CommandSourceStack src,
                           java.util.function.Function<ServerPlayer, String> action)
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

    private static Component preview(ServerPlayer p) {
        var profile = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(p.getUUID());
        String display = profile != null && profile.displayName != null ? profile.displayName
            : (profile != null && profile.username != null ? profile.username : p.getGameProfile().getName());
        String username = profile != null ? profile.username : null;
        NameStyles.NameStyle s = NameStyles.current(p.getUUID(), username);
        return TextUtil.info("Your name now looks like: ")
            .copy().append(NameStyles.render(s, display));
    }

    private static boolean requireAuth(ServerPlayer player) {
        if (CoffeesAeroAuth.AUTH_MANAGER != null
            && CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) return true;
        player.sendSystemMessage(Component.literal(
            TextUtil.PREFIX + "§cLog in first: §a/login <password>"));
        return false;
    }
}
