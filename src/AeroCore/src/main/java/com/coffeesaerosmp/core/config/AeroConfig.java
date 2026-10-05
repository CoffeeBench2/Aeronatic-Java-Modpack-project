package com.coffeesaerosmp.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class AeroConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.ConfigValue<String> SERVER_IP;
    public static final ModConfigSpec.ConfigValue<String> ADMIN_USERNAME;
    public static final ModConfigSpec.ConfigValue<String> PACK_VERSION;
    public static final ModConfigSpec.ConfigValue<String> UPDATE_URL;
    public static final ModConfigSpec.ConfigValue<String> VERSION_CHECK_URL;
    public static final ModConfigSpec.ConfigValue<String> PACK_TOML_URL;
    public static final ModConfigSpec.ConfigValue<String> NEWS_URL;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_URL;
    public static final ModConfigSpec.BooleanValue MANUAL_UPDATE_ONLY;
    public static final ModConfigSpec.ConfigValue<String> CURSEFORGE_URL;
    public static final ModConfigSpec.ConfigValue<String> MODRINTH_URL;
    public static final ModConfigSpec.BooleanValue ANALOG_AUDIO_PROMPT_SHOWN;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> MANUAL_MODS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("CoffeesAeroSMP Core — Client Configuration");

        SERVER_IP = builder
            .comment("Server IP shown in and used by the 'Join Coffees Aero SMP' button.",
                     "\"managed\" = use the address built into CoffeesAeroCore. Any other value is a verbatim override.")
            .define("serverIp", "managed");

        ADMIN_USERNAME = builder
            .comment("Minecraft username that can access the Admin Settings screen")
            .define("adminUsername", "MrCoffeeBench");

        PACK_VERSION = builder
            .comment("Bundled pack version — auto-stamped by the build script every build. Do not edit by hand.")
            .define("packVersion", "0.0.0");

        UPDATE_URL = builder
            .comment("Where players download the latest pack (shown when their pack is outdated).")
            .define("updateUrl", "https://github.com/CoffeeBench2/Aeronatic-Java-Modpack-project");

        VERSION_CHECK_URL = builder
            .comment("Raw URL of version.json the client polls for the latest pack version. Blank = disable the check.")
            .define("versionCheckUrl",
                "https://raw.githubusercontent.com/CoffeeBench2/Aeronatic-Java-Modpack-project/main/version.json");

        PACK_TOML_URL = builder
            .comment("Raw URL of the packwiz pack.toml. Used by the in-game 'Update Now' button to run",
                     "packwiz-installer (downloads only changed files) after the game closes. Blank = disable in-game update.")
            .define("packTomlUrl",
                "https://raw.githubusercontent.com/CoffeeBench2/Aeronatic-Java-Modpack-project/main/pack.toml");

        MANUAL_UPDATE_ONLY = builder
            .comment("Detect updates but DO NOT download them — point the player at CurseForge/Modrinth instead.",
                     "Set true for the CurseForge import build. Those installs sit several pack versions",
                     "behind, so the in-client updater has to fetch hundreds of changed files; every request",
                     "is cache-busted and therefore hits GitHub's origin, which answers 429 Too Many Requests",
                     "part-way through. The update then fails silently. Re-downloading the pack from the store",
                     "is both faster and something the launcher already knows how to do.")
            .define("manualUpdateOnly", false);

        CURSEFORGE_URL = builder
            .comment("Store page shown when manualUpdateOnly is on.")
            .define("curseforgeUrl", "https://www.curseforge.com/minecraft/modpacks/coffees-create-aeronautics-smp");

        MODRINTH_URL = builder
            .comment("Store page shown when manualUpdateOnly is on.")
            .define("modrinthUrl", "https://modrinth.com/modpack/coffees-create-aeronautics-smp");

        DISCORD_URL = builder
            .comment("Discord invite opened by the Discord button on the title screen.",
                     "Blank = hide the button entirely rather than show one that goes nowhere.")
            .define("discordUrl", "https://discord.gg/AnFUh5vTz6");

        NEWS_URL = builder
            .comment("Raw URL of the News/Announcements JSON, fetched live when the title screen opens so news",
                     "can be changed on GitHub without a pack rebuild or version bump. Falls back to the bundled",
                     "config/coffees_aero_announcements.json (then the in-jar copy) when offline. Blank = local only.")
            .define("newsUrl",
                "https://raw.githubusercontent.com/CoffeeBench2/Aeronatic-Java-Modpack-project/main/overrides/config/coffees_aero_announcements.json");

        ANALOG_AUDIO_PROMPT_SHOWN = builder
            .comment("Internal flag. Set true once the player has permanently dismissed the one-time",
                     "'install Analog Audio' helper. That helper appears ONLY on builds that don't bundle",
                     "the mod (the CurseForge-website download strips it); Modrinth/GitHub builds include",
                     "it, so this is always ignored there. Not meant to be edited by hand.")
            .define("analogAudioPromptShown", false);

        MANUAL_MODS = builder
            .comment("Mods the server REQUIRES that a store download cannot ship (CurseForge will not host or",
                     "allow them in a pack zip). When one is not loaded, the title screen shows a one-time-per-",
                     "launch helper with a link to the mod's page and a shortcut to the mods folder. It never",
                     "downloads anything itself. Format: \"modId|Display Name|https://page|what it does\".",
                     "Builds that bundle a mod never see its entry, so listing extra mods is harmless.")
            .defineListAllowEmpty("manualMods", java.util.List.of(
                    "analogaudio|Analog Audio|https://modrinth.com/mod/analog-audio|the in-game radios and cassettes",
                    "ssrd|Separate Sable Render Distance|https://modrinth.com/mod/ssrd/version/1.8.7|how far away ships render"),
                o -> o instanceof String str && str.split("\\|").length >= 3);

        CLIENT_SPEC = builder.build();
    }

    private AeroConfig() {}
}