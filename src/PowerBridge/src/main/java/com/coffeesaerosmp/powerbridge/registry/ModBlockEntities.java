package com.coffeesaerosmp.powerbridge.registry;

import com.coffeesaerosmp.powerbridge.CoffeesPowerBridge;
import com.coffeesaerosmp.powerbridge.blockentity.FEKineticImporterBE;
import com.coffeesaerosmp.powerbridge.blockentity.KineticFEExporterBE;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,
            CoffeesPowerBridge.MODID);

    public static final var FE_KINETIC_IMPORTER =
        BLOCK_ENTITIES.register("fe_kinetic_importer", () ->
            BlockEntityType.Builder
                .of(FEKineticImporterBE::new, ModBlocks.FE_KINETIC_IMPORTER.get())
                .build(null));

    public static final var KINETIC_FE_EXPORTER =
        BLOCK_ENTITIES.register("kinetic_fe_exporter", () ->
            BlockEntityType.Builder
                .of(KineticFEExporterBE::new, ModBlocks.KINETIC_FE_EXPORTER.get())
                .build(null));
}
