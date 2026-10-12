package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.discord.expert.CoffeeBot;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /aerobot} — staff tools for the CoffeeBot expert system:
 * <ul>
 *   <li>{@code ask <question>} — what the bot would answer in Discord, shown here (test without Discord)</li>
 *   <li>{@code unanswered} — the latest questions it couldn't answer, i.e. what to teach it next</li>
 *   <li>{@code reload} — re-read the knowledge base after editing {@code config/coffeebot-kb.json}</li>
 *   <li>{@code status}</li>
 * </ul>
 */
public final class CoffeeBotCommands {

    private CoffeeBotCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("aerobot")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("ask")
                .then(Commands.argument("question", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        var src = ctx.getSource();
                        String q = StringArgumentType.getString(ctx, "question");
                        // "game:" ids never match a Discord link, so personal questions show the unlinked answer.
                        String who = "game:" + (src.getEntity() == null ? "console" : src.getEntity().getStringUUID());
                        CoffeeBot.ask(who, q, answer -> src.getServer().execute(() -> src.sendSystemMessage(
                            Component.literal(TextUtil.PREFIX + "§7CoffeeBot would say:\n§f"
                                + (answer == null ? "§8(small talk, so the banter table answers)" : toChat(answer))))));
                        return 1;
                    })))
            .then(Commands.literal("unanswered").executes(ctx -> {
                var list = CoffeeBot.unanswered();
                StringBuilder sb = new StringBuilder(TextUtil.PREFIX + "§eUnanswered questions §7(newest first, since boot; "
                    + "all of them are also in coffeebot-unanswered.log):");
                if (list.isEmpty()) sb.append("\n §8none yet");
                for (int i = 0; i < Math.min(15, list.size()); i++) sb.append("\n §7- §f").append(list.get(i));
                ctx.getSource().sendSystemMessage(Component.literal(sb.toString()));
                return 1;
            }))
            .then(Commands.literal("reload").executes(ctx -> {
                String err = CoffeeBot.reload();
                ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX + (err == null
                    ? "§aKnowledge base reloaded: §f" + CoffeeBot.topicCount() + " topics."
                    : "§cReload failed, the old knowledge base is still in use: §f" + err)));
                return err == null ? 1 : 0;
            }))
            .then(Commands.literal("status").executes(ctx -> {
                ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7CoffeeBot expert: "
                    + (CoffeeBot.active() ? "§aanswering" : "§coff (disabled, lobby role, or not loaded)")
                    + " §7· " + CoffeeBot.topicCount() + " topics · " + CoffeeBot.unanswered().size() + " unanswered since boot"));
                return 1;
            })));
    }

    /** Discord markdown → chat: drop bold/italic/code markers and the -# subtext prefix. */
    static String toChat(String md) {
        return md.replace("**", "").replace("`", "").replace("-# ", "§8").replaceAll("(?<![a-z])\\*(?=\\S)|(?<=\\S)\\*(?![a-z])", "");
    }
}
