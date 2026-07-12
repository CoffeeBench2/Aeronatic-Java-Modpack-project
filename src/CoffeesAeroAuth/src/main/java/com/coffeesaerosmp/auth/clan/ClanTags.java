package com.coffeesaerosmp.auth.clan;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.NameVisibility;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clan tags (2026-07-12 player request): a short {@code [TAG]} rendered before the player's name
 * in chat, the tab list and the nametag. The "clan" itself IS the player's FTB Teams party — the
 * same team that already backs land claims, rail auto-claims and AeroClaims ship claims — so
 * membership can never diverge from the systems players actually share.
 *
 * <p>Tags live in the DISPLAY layer only (scoreboard-team prefixes + chat components) and are
 * stored per FTB-team-id in {@code clan_tags.json}. They must NEVER be baked into the GameProfile
 * name — that field has a hard 16-char packet limit and overflowing it mass-kicks (see 1.6.10).</p>
 */
public final class ClanTags {

    /** 2–5 chars, alphanumeric — short enough that badge+tag+name stays readable everywhere. */
    private static final String TAG_REGEX = "[A-Za-z0-9]{2,5}";

    /** Tag colors players may pick — Minecraft color names → § codes. */
    public static final Map<String, String> COLORS = Map.ofEntries(
        Map.entry("aqua", "§b"),        Map.entry("blue", "§9"),
        Map.entry("dark_aqua", "§3"),   Map.entry("dark_blue", "§1"),
        Map.entry("dark_green", "§2"),  Map.entry("dark_red", "§4"),
        Map.entry("gold", "§6"),        Map.entry("gray", "§7"),
        Map.entry("green", "§a"),       Map.entry("pink", "§d"),
        Map.entry("purple", "§5"),      Map.entry("red", "§c"),
        Map.entry("white", "§f"),       Map.entry("yellow", "§e"));
    private static final String DEFAULT_COLOR = "§9";   // blue — every tag renders blue until the party OWNER picks a color

    private record Entry(String tag, String colorCode) {}

    private static final Map<UUID, Entry> TAGS = new ConcurrentHashMap<>();   // FTB team id → tag+color
    private static volatile Path file;

    private ClanTags() {}

    public static void initialize(Path dataDir) {
        file = dataDir.resolve("clan_tags.json");
        TAGS.clear();
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            o.entrySet().forEach(e -> {
                UUID id = UUID.fromString(e.getKey());
                if (e.getValue().isJsonObject()) {
                    JsonObject v = e.getValue().getAsJsonObject();
                    TAGS.put(id, new Entry(v.get("tag").getAsString(),
                        v.has("color") ? v.get("color").getAsString() : DEFAULT_COLOR));
                } else {
                    TAGS.put(id, new Entry(e.getValue().getAsString(), DEFAULT_COLOR)); // pre-color format
                }
            });
            CoffeesAeroAuth.LOGGER.info("[Clan] Loaded {} clan tags.", TAGS.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Clan] clan_tags.json load failed: {}", e.getMessage());
        }
    }

    /** The player's clan tag (plain text, e.g. for Discord), or {@code null}. */
    public static String tagFor(ServerPlayer player) {
        Entry e = entryFor(player);
        return e == null ? null : e.tag();
    }

    /** The § color code for the player's clan tag (default aqua). */
    public static String colorFor(ServerPlayer player) {
        Entry e = entryFor(player);
        return e == null ? DEFAULT_COLOR : e.colorCode();
    }

    private static Entry entryFor(ServerPlayer player) {
        try {
            Optional<Team> team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player);
            if (team.isEmpty() || !team.get().isPartyTeam()) return null;
            return TAGS.get(team.get().getId());
        } catch (Exception e) {
            return null;   // FTB Teams not ready yet — render untagged rather than break login
        }
    }

    /** Sets/replaces the tag for the player's party. Returns a user-facing error, null on success. */
    public static String setTag(ServerPlayer player, String rawTag) {
        String tag = rawTag == null ? "" : rawTag.trim();
        if (!tag.matches(TAG_REGEX))
            return "Tag must be 2-5 letters/numbers (e.g. AERO).";
        String banned = AuthConfig.BANNED_WORDS.get();
        if (banned != null && !banned.isBlank()) {
            String lower = tag.toLowerCase(Locale.ROOT);
            for (String w : banned.split(",")) {
                if (!w.isBlank() && lower.contains(w.trim().toLowerCase(Locale.ROOT)))
                    return "That tag contains a banned word.";
            }
        }
        Team team = requireOfficerParty(player);
        if (team == null)
            return "You need your own party first (/ftbteams party create <name>), and officer rank to set its tag.";
        Entry prev = TAGS.get(team.getId());
        TAGS.put(team.getId(), new Entry(tag, prev != null ? prev.colorCode() : DEFAULT_COLOR));
        persist();
        reapplyOnlineMembers(player.getServer(), team);
        return null;
    }

    /** Sets the tag COLOR for the player's party — party OWNER only (officers may set the tag
     *  text, but color is the owner's call; untouched tags stay the default blue).
     *  Returns a user-facing error, null on success. */
    public static String setColor(ServerPlayer player, String colorName) {
        String code = COLORS.get(colorName == null ? "" : colorName.toLowerCase(Locale.ROOT));
        if (code == null)
            return "Unknown color. Pick one of: " + String.join(", ", COLORS.keySet().stream().sorted().toList());
        Team team;
        try {
            Optional<Team> t = FTBTeamsAPI.api().getManager().getTeamForPlayer(player);
            if (t.isEmpty() || !t.get().isPartyTeam()) return "You need to be in a party to color its tag.";
            if (!t.get().getRankForPlayer(player.getUUID()).isOwner())
                return "Only the party OWNER can change the tag color.";
            team = t.get();
        } catch (Exception e) {
            return "Teams are not available right now — try again in a moment.";
        }
        Entry prev = TAGS.get(team.getId());
        if (prev == null) return "Set a tag first: /clan tag <tag>";
        TAGS.put(team.getId(), new Entry(prev.tag(), code));
        persist();
        reapplyOnlineMembers(player.getServer(), team);
        return null;
    }

    /** Clears the tag for the player's party. Returns a user-facing error, null on success. */
    public static String clearTag(ServerPlayer player) {
        Team team = requireOfficerParty(player);
        if (team == null)
            return "You need to be in a party (and officer rank) to clear its tag.";
        if (TAGS.remove(team.getId()) == null) return "Your party has no tag set.";
        persist();
        reapplyOnlineMembers(player.getServer(), team);
        return null;
    }

    private static Team requireOfficerParty(ServerPlayer player) {
        try {
            Optional<Team> team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player);
            if (team.isEmpty() || !team.get().isPartyTeam()) return null;
            return team.get().getRankForPlayer(player.getUUID()).isOfficerOrBetter() ? team.get() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Refreshes badges/prefixes for every ONLINE member of the team (server thread). */
    private static void reapplyOnlineMembers(MinecraftServer server, Team team) {
        if (server == null) return;
        for (UUID member : team.getMembers()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online == null) continue;
            if (CoffeesAeroAuth.AUTH_MANAGER == null
                || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(member)) continue;   // pre-auth stays hidden
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
                ? CoffeesAeroAuth.PROFILE_STORE.get(member) : null;
            boolean premium = p != null && p.getAccountType() == PlayerProfile.AccountType.PREMIUM;
            NameVisibility.reveal(online, premium);
        }
    }

    private static void persist() {
        Path f = file;
        if (f == null) return;
        JsonObject o = new JsonObject();
        TAGS.forEach((id, e) -> {
            JsonObject v = new JsonObject();
            v.addProperty("tag", e.tag());
            v.addProperty("color", e.colorCode());
            o.add(id.toString(), v);
        });
        String json = o.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Clan] tag save failed: {}", e.getMessage()); }
        });
    }
}
