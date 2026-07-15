package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.CoffeesAeroCore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First-join chat-language picker. The pack ships the client-side Translator mod (kgg.translator),
 * which can translate incoming chat into a target language — but only if the player knows to set it
 * up. This posts a one-time clickable prompt a few seconds after joining a server; clicking a
 * language runs {@code /aerolang <code>}, which points the translator at that language and turns on
 * chat auto-translation. Decoupled from the translator's internals — it writes the two config files
 * ({@code config/translator.json} "to" + {@code config/translator_option.json} auto_chat) directly
 * and best-effort reloads. A flag file makes it fire only once; players can re-run /aerolang anytime.
 */
@EventBusSubscriber(modid = CoffeesAeroCore.MOD_ID, value = Dist.CLIENT)
public final class LanguageSelect {

    private LanguageSelect() {}

    /** Display label → translator backend code (matches the default Baidu backend's code map). */
    private static final Map<String, String> LANGS = new LinkedHashMap<>();
    static {
        LANGS.put("English", "en");  LANGS.put("中文", "zh");    LANGS.put("日本語", "jp");
        LANGS.put("한국어", "kor");   LANGS.put("Français", "fra"); LANGS.put("Deutsch", "de");
        LANGS.put("Русский", "ru");  LANGS.put("Português", "pt");
    }

    private static Path cfgDir()  { return FMLPaths.CONFIGDIR.get(); }
    private static Path flag()    { return cfgDir().resolve("coffeesaerosmp_core").resolve("chat_lang.flag"); }
    private static Path transCfg(){ return cfgDir().resolve("translator.json"); }
    private static Path transOpt(){ return cfgDir().resolve("translator_option.json"); }

    private static final Gson GSON = new GsonBuilder().create();
    private static int promptTicks = -1;

    /** The whole language feature is gated on the Translator mod being installed — if it's not in the
     *  pack, no prompt, no command, nothing to configure. Removing the translator jar disables this
     *  cleanly; re-adding it re-enables. */
    private static boolean translatorPresent() {
        return net.neoforged.fml.ModList.get().isLoaded("translator");
    }

    @SubscribeEvent
    static void onJoin(ClientPlayerNetworkEvent.LoggingIn e) {
        if (!translatorPresent()) return;
        if (!Files.exists(flag())) promptTicks = 60; // ~3s after join, once the client has settled
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post e) {
        if (promptTicks < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (promptTicks-- > 0) return;
        promptTicks = -1;
        MutableComponent line = Component.literal("🌐 Pick your chat language: ").withStyle(ChatFormatting.GOLD);
        for (Map.Entry<String, String> en : LANGS.entrySet())
            line.append(button(en.getKey(), en.getValue())).append(Component.literal(" "));
        line.append(button("Later", "__later__"));
        mc.player.displayClientMessage(line, false);
    }

    private static Component button(String label, String code) {
        return Component.literal("[" + label + "]").withStyle(s -> s
            .withColor("__later__".equals(code) ? ChatFormatting.GRAY : ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aerolang " + code))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.literal("__later__".equals(code) ? "Ask me later" : "Translate chat into " + label))));
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterClientCommandsEvent e) {
        if (!translatorPresent()) return;
        e.getDispatcher().register(Commands.literal("aerolang")
            .then(Commands.argument("lang", StringArgumentType.word())
                .executes(ctx -> set(StringArgumentType.getString(ctx, "lang")))));
    }

    private static int set(String code) {
        Minecraft mc = Minecraft.getInstance();
        try { Files.createDirectories(flag().getParent()); Files.writeString(flag(), code); } catch (Exception ignored) {}

        if ("__later__".equals(code)) {
            msg(mc, "§7Okay — set your chat language anytime with §b/aerolang <en|zh|jp|kor|fra|de|ru|pt>§7.");
            return 1;
        }
        if (!LANGS.containsValue(code)) {
            msg(mc, "§cUnknown language '" + code + "'. Options: §fen zh jp kor fra de ru pt");
            return 0;
        }
        try {
            JsonObject t = readJson(transCfg());
            t.addProperty("to", code);
            writeJson(transCfg(), t);
            JsonObject o = readJson(transOpt());
            o.addProperty("auto_chat", true);   // enable incoming-chat auto-translation
            writeJson(transOpt(), o);
            reloadTranslator();
            msg(mc, "§a🌐 Chat will now translate into your language. §7Change it anytime: /aerolang");
        } catch (Exception ex) {
            msg(mc, "§cCouldn't set language: " + ex.getMessage());
        }
        return 1;
    }

    private static void msg(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), false);
    }

    private static JsonObject readJson(Path p) throws Exception {
        if (!Files.exists(p)) return new JsonObject();
        JsonObject o = GSON.fromJson(Files.readString(p), JsonObject.class);
        return o != null ? o : new JsonObject();
    }

    private static void writeJson(Path p, JsonObject o) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(o));
    }

    /** Best-effort: nudge the translator to re-read its config so the change applies without a relaunch. */
    private static void reloadTranslator() {
        try { Class.forName("kgg.translator.TranslatorConfig").getMethod("readFile").invoke(null); }
        catch (Throwable ignored) {}
    }
}
