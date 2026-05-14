package com.coffeesaerosmp.powerbridge.registry;

import com.coffeesaerosmp.powerbridge.CoffeesPowerBridge;
import com.coffeesaerosmp.powerbridge.block.FEKineticImporterBlock;
import com.coffeesaerosmp.powerbridge.block.KineticFEExporterBlock;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(CoffeesPowerBridge.MODID);

    /** Accepts FE (from any source, including IE wires) → outputs Create rotation. */
    public static final DeferredBlock<Block> FE_KINETIC_IMPORTER =
        BLOCKS.register("fe_kinetic_importer", FEKineticImporterBlock::new);

    /** Accepts Create rotation → outputs FE (to any consumer, including IE wires). */
    public static final DeferredBlock<Block> KINETIC_FE_EXPORTER =
        BLOCKS.register("kinetic_fe_exporter", KineticFEExporterBlock::new);
}
