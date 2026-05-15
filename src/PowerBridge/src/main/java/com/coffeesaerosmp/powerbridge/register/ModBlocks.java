package com.coffeesaerosmp.powerbridge.register;

import com.coffeesaerosmp.powerbridge.PowerBridgeMod;
import com.coffeesaerosmp.powerbridge.content.dynamo.KineticDynamoBlock;
import com.coffeesaerosmp.powerbridge.content.motor.FEMotorBlock;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(PowerBridgeMod.MOD_ID);

    public static final DeferredBlock<FEMotorBlock> FE_MOTOR =
        BLOCKS.register("fe_motor", FEMotorBlock::new);

    public static final DeferredBlock<KineticDynamoBlock> KINETIC_DYNAMO =
        BLOCKS.register("kinetic_dynamo", KineticDynamoBlock::new);
}
