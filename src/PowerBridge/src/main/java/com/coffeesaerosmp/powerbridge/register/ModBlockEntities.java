package com.coffeesaerosmp.powerbridge.register;

import com.coffeesaerosmp.powerbridge.PowerBridgeMod;
import com.coffeesaerosmp.powerbridge.content.dynamo.KineticDynamoBlockEntity;
import com.coffeesaerosmp.powerbridge.content.motor.FEMotorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, PowerBridgeMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FEMotorBlockEntity>> FE_MOTOR =
        BLOCK_ENTITIES.register("fe_motor", () ->
            BlockEntityType.Builder.<FEMotorBlockEntity>of(
                (pos, state) -> new FEMotorBlockEntity(ModBlockEntities.FE_MOTOR.get(), pos, state),
                ModBlocks.FE_MOTOR.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KineticDynamoBlockEntity>> KINETIC_DYNAMO =
        BLOCK_ENTITIES.register("kinetic_dynamo", () ->
            BlockEntityType.Builder.<KineticDynamoBlockEntity>of(
                (pos, state) -> new KineticDynamoBlockEntity(ModBlockEntities.KINETIC_DYNAMO.get(), pos, state),
                ModBlocks.KINETIC_DYNAMO.get()
            ).build(null));
}
