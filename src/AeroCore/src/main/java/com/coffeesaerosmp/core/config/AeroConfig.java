package com.coffeesaerosmp.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class AeroConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ClientConfig CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        CLIENT = new ClientConfig(BUILDER);
        CLIENT_SPEC = BUILDER.build();
    }

    public static class ClientConfig {

        public final ModConfigSpec.ConfigValue<String> serverIP;
        public final ModConfigSpec.ConfigValue<String> adminUsername;

        ClientConfig(ModConfigSpec.Builder builder) {
            builder.comment("Coffees Aero SMP Core — Client Configuration").push("coffeesaerosmp");

            serverIP = builder
                .comment("The IP address of the Coffees Aero SMP server. Updated via the Admin Settings screen in-game.")
                .define("serverIP", "play.coffeesaerosmp.net");

            adminUsername = builder
                .comment("Exact Minecraft username (case-sensitive) that has admin access. " +
                         "This player sees the Singleplayer button and the Admin Settings panel.")
                .define("adminUsername", "MrCoffeeBench");

            builder.pop();
        }
    }
}
