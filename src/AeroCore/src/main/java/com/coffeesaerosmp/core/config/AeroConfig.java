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

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("CoffeesAeroSMP Core — Client Configuration");

        SERVER_IP = builder
            .comment("Server IP shown in and used by the 'Join Coffees Aero SMP' button")
            .define("serverIp", "play.coffeesaerosmp.net");

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

        CLIENT_SPEC = builder.build();
    }

    private AeroConfig() {}
}