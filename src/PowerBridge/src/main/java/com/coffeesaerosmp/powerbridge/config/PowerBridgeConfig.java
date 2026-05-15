package com.coffeesaerosmp.powerbridge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class PowerBridgeConfig {

    public static final ModConfigSpec SPEC;

    // ── FE Electric Motor (FE → RPM) ───────────────────────────────────────
    public static final ModConfigSpec.IntValue MOTOR_FE_PER_RPM;
    public static final ModConfigSpec.IntValue MOTOR_MAX_RPM;
    public static final ModConfigSpec.IntValue MOTOR_BUFFER;

    // ── Kinetic Dynamo (RPM → FE) ───────────────────────────────────────────
    public static final ModConfigSpec.IntValue DYNAMO_FE_PER_RPM;
    public static final ModConfigSpec.IntValue DYNAMO_BUFFER;
    public static final ModConfigSpec.IntValue DYNAMO_MAX_TRANSFER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("FE Electric Motor — converts Forge Energy into Create rotation").push("motor");
        MOTOR_FE_PER_RPM  = b.comment("FE consumed per RPM per tick. Default 8.")
                              .defineInRange("fePerRpm",  8,   1, 512);
        MOTOR_MAX_RPM     = b.comment("Maximum RPM the motor can target (wrench-configurable in-game).")
                              .defineInRange("maxRpm",   256,  1, 512);
        MOTOR_BUFFER      = b.comment("Internal FE buffer. Motor stops if buffer empties.")
                              .defineInRange("buffer", 16000, 1000, 500000);
        b.pop();

        b.comment("Kinetic Dynamo — converts Create rotation into Forge Energy").push("dynamo");
        DYNAMO_FE_PER_RPM   = b.comment("FE generated per RPM per tick. Default 6 (75% round-trip efficiency).")
                               .defineInRange("fePerRpm",   6,   1, 512);
        DYNAMO_BUFFER       = b.comment("Internal FE output buffer size.")
                               .defineInRange("buffer", 32000, 1000, 500000);
        DYNAMO_MAX_TRANSFER = b.comment("Max FE transferred per tick to each adjacent block/wire.")
                               .defineInRange("maxTransfer", 1024, 64, 65536);
        b.pop();

        SPEC = b.build();
    }
}
