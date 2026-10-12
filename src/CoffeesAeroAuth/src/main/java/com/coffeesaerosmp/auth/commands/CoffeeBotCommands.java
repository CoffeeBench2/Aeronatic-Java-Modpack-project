package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.discord.expert.CoffeeBot;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * {@code /aerobot} — staff tools for the CoffeeBot expert system:
 * <ul>
 *   <li>{@code ask <question>} — what the bot would answer in Discord, shown here (test without Discord)</li>
 *   <li>{@code unanswered} — the latest questions it couldn't answer, numbered: what to teach it next</li>
 *   <li>{@code learn <n> <topicId>} — teach unanswered question #n in one step</li>
 *   <li>{@code teach <topicId> <question>} — "a question like this means that topic" (saved to
 *       {@code config/coffeebot-learned.json})</li>
 *   <li>{@code topics [filter]} — topic ids to teach against</li>
 *   <li>{@code reload} — re-read the knowledge base after editing {@code config/coffeebot-kb.json}</li>
 *   <li>{@code status}</li>
 * </ul>
 * All of them work from the Discord admin channel too (console bridge): type {@code aerobot learn 1 rtp} there.
 */
public final class CoffeeBotCommands {

    private CoffeeBotCommands() {}

    private static final SuggestionProvider<CommandSourceStack> TOPIC_IDS = (c, b) -> {
        var kb = CoffeeBot.knowledge();
        if (kb != null) kb.topics().forEach(t -> b.suggest(t.id()));
        return b.buildFuture();
    };

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
                                + (answer == null ? "§8(nothing: you asked again within 2.5 s)" : toChat(answer))))));
                        return 1;
                    })))
            .then(Commands.literal("unanswered").executes(ctx -> {
                var list = CoffeeBot.unanswered();
                StringBuilder sb = new StringBuilder(TextUtil.PREFIX
                    + "§eUnanswered questions §7(newest first, since boot; teach one with §f/aerobot learn <n> <topicId>§7):");
                if (list.isEmpty()) sb.append("\n §8none yet");
                for (int i = 0; i < Math.min(15, list.size()); i++) sb.append("\n §7").append(i + 1).append(". §f").append(list.get(i));
                ctx.getSource().sendSystemMessage(Component.literal(sb.toString()));
                return 1;
            }))
            .then(Commands.literal("learn")
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 50))
                    .then(Commands.argument("topicId", StringArgumentType.word())
                        .suggests(TOPIC_IDS)
                        .executes(ctx -> {
                            int n = IntegerArgumentType.getInteger(ctx, "n");
                            var list = CoffeeBot.unanswered();
                            if (n > list.size()) {
                                ctx.getSource().sendFailure(Component.literal("§cThere are only " + list.size() + " unanswered questions."));
                                return 0;
                            }
                            String q = list.get(n - 1);
                            String topic = StringArgumentType.getString(ctx, "topicId");
                            String err = CoffeeBot.teach(topic, q);
                            ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX + (err == null
                                ? "§aLearned: §f\"" + q + "\" §7→ §f" + topic : "§c" + err)));
                            return err == null ? 1 : 0;
                        }))))
            .then(Commands.literal("teach")
                .then(Commands.argument("topicId", StringArgumentType.word())
                    .suggests(TOPIC_IDS)
                    .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String err = CoffeeBot.teach(StringArgumentType.getString(ctx, "topicId"),
                                StringArgumentType.getString(ctx, "question"));
                            ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX + (err == null
                                ? "§aLearned. §7" + CoffeeBot.learnedCount() + " taught phrasings now." : "§c" + err)));
                            return err == null ? 1 : 0;
                        }))))
            .then(Commands.literal("topics")
                .executes(ctx -> topics(ctx.getSource(), ""))
                .then(Commands.argument("filter", StringArgumentType.word())
                    .executes(ctx -> topics(ctx.getSource(), StringArgumentType.getString(ctx, "filter")))))
            .then(Commands.literal("reload").executes(ctx -> {
                String err = CoffeeBot.reload();
                ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX + (err == null
                    ? "§aKnowledge base reloaded: §f" + CoffeeBot.topicCount() + " topics, " + CoffeeBot.learnedCount() + " taught phrasings."
                    : "§cReload failed, the old knowledge base is still in use: §f" + err)));
                return err == null ? 1 : 0;
            }))
            .then(Commands.literal("status").executes(ctx -> {
                ctx.getSource().sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7CoffeeBot expert: "
                    + (CoffeeBot.active() ? "§aanswering" : "§coff (disabled, lobby role, or not loaded)")
                    + " §7· " + CoffeeBot.topicCount() + " topics · " + CoffeeBot.learnedCount() + " taught phrasings · "
                    + CoffeeBot.unanswered().size() + " unanswered since boot"));
                return 1;
            })));
    }

    private static int topics(CommandSourceStack src, String filter) {
        var kb = CoffeeBot.knowledge();
        if (kb == null) { src.sendFailure(Component.literal("§cCoffeeBot isn't loaded.")); return 0; }
        String f = filter.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(TextUtil.PREFIX + "§eTopics §7(id §8- §7title):");
        int n = 0;
        for (var t : kb.topics()) {
            if (!f.isEmpty() && !t.id().contains(f) && !t.title().toLowerCase(Locale.ROOT).contains(f)) continue;
            if (n++ >= 40) { sb.append("\n §8…narrow it with a filter"); break; }
            sb.append("\n §f").append(t.id()).append(" §8- §7").append(t.title());
        }
        src.sendSystemMessage(Component.literal(sb.toString()));
        return 1;
    }

    /** Discord markdown → chat: drop bold/italic/code markers and the -# subtext prefix. */
    static String toChat(String md) {
        return md.replace("**", "").replace("`", "").replace("-# ", "§8").replaceAll("(?<![a-z])\\*(?=\\S)|(?<=\\S)\\*(?![a-z])", "");
    }
}
