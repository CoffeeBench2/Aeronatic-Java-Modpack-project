package com.coffeesaerosmp.auth.discord.expert;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.display.HiddenOps;
import com.coffeesaerosmp.auth.discord.expert.CoffeeBotExpert.LiveReply;
import com.coffeesaerosmp.auth.discord.expert.LiveIntent.Intent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.ModList;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The live half of CoffeeBot: answers {@link LiveIntent}s from the running server. Everything that touches game
 * state runs on the server thread (via {@code server.execute}) with a short timeout; a stalled server means
 * "no live answer" and the pipeline carries on to the rules.
 *
 * <p>Privacy: player lookups use display names, say only "online now" or "last seen N ago", and treat hidden ops
 * as offline — the same information the tab list and {@code /leaderboard} already show.
 */
final class ServerLiveAnswers implements CoffeeBotExpert.LiveAnswers {

    private final MinecraftServer server;
    /** Normalised item name / path → items, built once on first use (the registry doesn't change at runtime). */
    private volatile Map<String, List<Item>> itemIndex;

    ServerLiveAnswers(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public LiveReply answer(String raw, List<String> tokens) {
        Intent intent = LiveIntent.parse(raw);
        if (intent == null) return null;
        return switch (intent.kind()) {
            case WHO_ONLINE -> onServer(this::whoOnline);
            case PLAYER_SEEN -> onServer(() -> seen(intent.subject()));
            case NEXT_RESTART -> nextRestart();
            case RECIPE -> onServer(() -> recipe(intent.subject()));
            case MOD_INFO -> modInfo(intent.subject());
        };
    }

    private <T> T onServer(java.util.function.Supplier<T> task) {
        if (server == null || !server.isRunning()) return null;
        try {
            return CompletableFuture.supplyAsync(task, server).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ── players ───────────────────────────────────────────────────────────────

    private LiveReply whoOnline() {
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (HiddenOps.isHidden(p.getUUID())) continue;
            names.add(displayName(p));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        String body = names.isEmpty()
            ? "Nobody's online right now."
            : "**" + names.size() + "** online: " + String.join(", ", names);
        return new LiveReply("Who's online", body, "You asked who's online, so I read the live player list.");
    }

    private LiveReply seen(String name) {
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return null;
        PlayerProfile found = null;
        for (PlayerProfile p : store.getAll()) {
            if (p == null) continue;
            if (name.equalsIgnoreCase(p.displayName) || name.equalsIgnoreCase(p.username)) { found = p; break; }
        }
        if (found == null) {
            return new LiveReply("Player lookup", "I don't know a player called **" + name + "** on this server.",
                "You asked about a player, and no account or display name matches '" + name + "'.");
        }
        String shown = found.displayName != null && !found.displayName.isBlank() ? found.displayName : found.username;
        ServerPlayer online = found.getUUID() == null ? null : server.getPlayerList().getPlayer(found.getUUID());
        String body;
        if (online != null && !HiddenOps.isHidden(online.getUUID())) {
            body = "**" + shown + "** is online right now.";
        } else if (found.lastSeen > 0) {
            body = "**" + shown + "** isn't online. Last seen " + ago(System.currentTimeMillis() - found.lastSeen) + ".";
        } else {
            body = "**" + shown + "** isn't online, and I don't have a last-seen time for them.";
        }
        return new LiveReply("Player lookup", body,
            "You asked about a player, so I checked the live player list and their profile's last-seen time.");
    }

    private static String displayName(ServerPlayer p) {
        var store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile prof = store == null ? null : store.get(p.getUUID());
        if (prof != null && prof.displayName != null && !prof.displayName.isBlank()) return prof.displayName;
        return p.getGameProfile().getName();
    }

    static String ago(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 1) return "just now";
        if (min < 60) return min + " minute" + (min == 1 ? "" : "s") + " ago";
        long h = min / 60;
        if (h < 48) return h + " hour" + (h == 1 ? "" : "s") + " ago";
        long d = h / 24;
        return d + " days ago";
    }

    // ── restart ───────────────────────────────────────────────────────────────

    private LiveReply nextRestart() {
        try {
            if (!AuthConfig.DAILY_RESTART_WARN_ENABLED.get()) return null;
            ZoneId zone = ZoneId.of(AuthConfig.DAILY_RESTART_TIMEZONE.get());
            LocalTime at = LocalTime.parse(AuthConfig.DAILY_RESTART_TIME.get());
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime next = now.with(at).withSecond(0).withNano(0);
            if (!next.isAfter(now)) next = next.plusDays(1);
            Duration d = Duration.between(now, next);
            String in = d.toHours() + "h " + d.toMinutesPart() + "m";
            return new LiveReply("Next restart",
                "The daily restart is at **" + at + " " + zoneLabel(zone) + "**, in about **" + in
                    + "**. There's a 10-minute warning in game. Extra restarts for updates are announced here.",
                "You asked when the server restarts, so I worked it out from the restart schedule in the server config.");
        } catch (Exception e) {
            return null;
        }
    }

    private static String zoneLabel(ZoneId z) {
        return "Asia/Colombo".equals(z.getId()) || "Asia/Kolkata".equals(z.getId()) ? "IST" : z.getId();
    }

    // ── recipes ───────────────────────────────────────────────────────────────

    private LiveReply recipe(String phrase) {
        List<Item> items = resolveItem(phrase);
        if (items.isEmpty()) return null;               // not an item: let the rules have the question
        Item item = items.get(0);
        String name = itemName(new ItemStack(item));
        var access = server.registryAccess();
        Map<String, List<String>> byType = new LinkedHashMap<>();
        int total = 0;
        for (var holder : server.getRecipeManager().getRecipes()) {
            var recipe = holder.value();
            ItemStack out;
            try { out = recipe.getResultItem(access); } catch (Exception e) { continue; }
            if (out == null || out.isEmpty() || out.getItem() != item) continue;
            total++;
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String type = typeLabel(typeId);
            List<String> lines = byType.computeIfAbsent(type, k -> new ArrayList<>());
            if (lines.size() >= 2) continue;
            String ins = ingredients(recipe.getIngredients());
            lines.add(ins.isEmpty() ? "(special recipe, see it in game)" : ins + (out.getCount() > 1 ? " → " + out.getCount() : ""));
        }
        StringBuilder sb = new StringBuilder();
        if (total == 0) {
            sb.append("Nothing crafts **").append(name).append("**, so it comes from the world: mining, mobs, loot or trading.");
        } else {
            sb.append("**").append(name).append("** is made with:");
            int shown = 0;
            for (var e : byType.entrySet()) {
                if (shown++ >= 4) break;
                for (String l : e.getValue()) sb.append("\n• **").append(e.getKey()).append("**: ").append(l);
            }
            if (byType.size() > 4) sb.append("\n• …and ").append(byType.size() - 4).append(" more recipe types");
        }
        sb.append("\nIn game, hover the item in EMI and press **R** for every recipe, or **U** for what it's used in.");
        if (items.size() > 1) {
            List<String> others = new ArrayList<>();
            for (int i = 1; i < Math.min(4, items.size()); i++) others.add(itemName(new ItemStack(items.get(i))));
            sb.append("\n-# Also matched: ").append(String.join(", ", others));
        }
        return new LiveReply("How to get " + name, sb.toString(),
            "You asked how to make something, so I found the item *" + name + "* in the server's item registry and read "
                + "every recipe the server has loaded that produces it.");
    }

    private static String ingredients(List<Ingredient> list) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : list) {
            if (ing == null || ing.isEmpty()) continue;
            ItemStack[] options = ing.getItems();
            if (options.length == 0) continue;
            String n = itemName(options[0]) + (options.length > 1 ? " (or similar)" : "");
            counts.merge(n, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((n, c) -> parts.add(c > 1 ? c + "× " + n : n));
        return String.join(", ", parts);
    }

    static String typeLabel(ResourceLocation id) {
        if (id == null) return "Recipe";
        String path = id.getPath();
        if (id.getNamespace().equals("minecraft")) {
            return switch (path) {
                case "crafting" -> "Crafting table";
                case "smelting" -> "Furnace";
                case "blasting" -> "Blast furnace";
                case "smoking" -> "Smoker";
                case "campfire_cooking" -> "Campfire";
                case "stonecutting" -> "Stonecutter";
                case "smithing" -> "Smithing table";
                default -> pretty(path);
            };
        }
        return pretty(id.getNamespace()) + ": " + pretty(path);
    }

    /** Best item for a phrase: exact display name, then exact registry path, then all words in the name. */
    List<Item> resolveItem(String phrase) {
        Map<String, List<Item>> idx = itemIndex;
        if (idx == null) { idx = buildIndex(); itemIndex = idx; }
        String key = norm(phrase);
        List<Item> exact = idx.get(key);
        if (exact == null && key.endsWith("s")) exact = idx.get(key.substring(0, key.length() - 1));
        if (exact == null && key.endsWith("es")) exact = idx.get(key.substring(0, key.length() - 2));
        if (exact != null) return sortByNamespace(exact);
        return List.of();
    }

    private Map<String, List<Item>> buildIndex() {
        Map<String, List<Item>> m = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            Set<String> keys = new LinkedHashSet<>();
            keys.add(norm(id.getPath()));
            String shown = itemName(new ItemStack(item));
            keys.add(norm(shown));
            for (String k : keys) if (!k.isEmpty()) m.computeIfAbsent(k, x -> new ArrayList<>()).add(item);
        }
        return m;
    }

    /** Vanilla and Create first, then everything else alphabetically, so "andesite alloy" picks Create's. */
    private static List<Item> sortByNamespace(List<Item> items) {
        List<Item> out = new ArrayList<>(items);
        out.sort((a, b) -> {
            String na = BuiltInRegistries.ITEM.getKey(a).getNamespace(), nb = BuiltInRegistries.ITEM.getKey(b).getNamespace();
            return Integer.compare(rank(na), rank(nb)) != 0 ? Integer.compare(rank(na), rank(nb)) : na.compareTo(nb);
        });
        return out;
    }

    private static int rank(String ns) {
        return ns.equals("create") ? 0 : ns.equals("minecraft") ? 1 : ns.startsWith("create") ? 2 : 3;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    /** The English name if the server has the language entry, else the registry path made readable. */
    static String itemName(ItemStack stack) {
        String n = stack.getHoverName().getString();
        if (n.contains(".") && !n.contains(" ")) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? n : pretty(id.getPath());
        }
        return n;
    }

    static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String w : path.replace('/', ' ').split("[_ ]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ── mods ──────────────────────────────────────────────────────────────────

    private LiveReply modInfo(String phrase) {
        String key = norm(phrase).replace(" ", "");
        if (key.length() < 3) return null;
        for (var info : ModList.get().getMods()) {
            String id = info.getModId();
            String name = info.getDisplayName();
            if (id.equals("minecraft") || id.equals("neoforge")) continue;
            if (!key.equals(norm(name).replace(" ", "")) && !key.equals(norm(id).replace(" ", ""))) continue;
            String version = String.valueOf(info.getVersion());
            boolean realVersion = !version.contains("NONE") && !version.startsWith("0.0");
            // Our own server mods describe internals (auth, identity, protection); say what they are, not how.
            if (id.startsWith("coffees")) {
                return new LiveReply(name, "**" + name + "** is one of Coffee's Aero SMP's own server mods.",
                    "You asked about a mod, and it's one of the server's own, so I kept the description short.");
            }
            String desc = info.getDescription() == null ? "" : info.getDescription().replaceAll("\\s+", " ").trim();
            if (desc.length() > 420) desc = desc.substring(0, 417).trim() + "…";
            String body = "**" + name + "** is in the pack" + (realVersion ? " (version " + version + ")" : "") + "."
                + (desc.isEmpty() || desc.equalsIgnoreCase("mod") ? "" : "\n" + desc);
            return new LiveReply(name, body,
                "You asked about a mod, so I looked it up in the list of mods the server has loaded and quoted its own description.");
        }
        return null;
    }
}
