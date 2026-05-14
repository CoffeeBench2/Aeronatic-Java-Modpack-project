package com.coffeesaerosmp.powerbridge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class BridgeConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    /** FE consumed per tick per 1 RPM generated (Importer). Default: 8 FE/tick per RPM. */
    public static final ModConfigSpec.IntValue FE_PER_RPM;

    /** Maximum RPM the FE Kinetic Importer can generate. */
    public static final ModConfigSpec.IntValue MAX_IMPORT_RPM;

    /** Maximum FE buffer in the Importer before it stops accepting power. */
    public static final ModConfigSpec.IntValue IMPORTER_BUFFER;

    /** FE produced per tick per 1 RPM consumed (Exporter). Default: 6 FE/tick per RPM. */
    public static final ModConfigSpec.IntValue FE_PER_RPM_EXPORT;

    /** Maximum FE buffer in the Exporter before it stops producing. */
    public static final ModConfigSpec.IntValue EXPORTER_BUFFER;

    static {
        BUILDER.push("conversion");

        FE_PER_RPM = BUILDER
            .comment("FE consumed per tick for every 1 RPM the FE Kinetic Importer generates.",
                     "Higher = more expensive to run. Default: 8")
            .defineInRange("fePerRpmImport", 8, 1, 512);

        MAX_IMPORT_RPM = BUILDER
            .comment("Maximum RPM the FE Kinetic Importer can generate. Default: 256")
            .defineInRange("maxImportRpm", 256, 16, 1024);

        IMPORTER_BUFFER = BUILDER
            .comment("Internal FE buffer size of the FE Kinetic Importer. Default: 16000")
            .defineInRange("importerBuffer", 16000, 1000, 1000000);

        FE_PER_RPM_EXPORT = BUILDER
            .comment("FE produced per tick for every 1 RPM the Kinetic FE Exporter receives.",
                     "Lower than import rate by design — conversion loss. Default: 6")
            .defineInRange("fePerRpmExport", 6, 1, 512);

        EXPORTER_BUFFER = BUILDER
            .comment("Internal FE output buffer of the Kinetic FE Exporter. Default: 16000")
            .defineInRange("exporterBuffer", 16000, 1000, 1000000);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
