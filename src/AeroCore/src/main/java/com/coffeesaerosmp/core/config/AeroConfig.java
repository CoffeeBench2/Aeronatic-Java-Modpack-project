package com.coffeesaerosmp.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class AeroConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.ConfigValue<String> SERVER_IP;
    public static final ModConfigSpec.ConfigValue<String> ADMIN_USERNAME;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("CoffeesAeroSMP Core — Client Configuration");

        SERVER_IP = builder
            .comment("Server IP shown in and used by the 'Join Coffees Aero SMP' button")
            .define("serverIp", "play.coffeesaerosmp.net");

        ADMIN_USERNAME = builder
            .comment("Minecraft username that can access the Admin Settings screen")
            .define("adminUsername", "MrCoffeeBench");

        CLIENT_SPEC = builder.build();
    }

    private AeroConfig() {}
}