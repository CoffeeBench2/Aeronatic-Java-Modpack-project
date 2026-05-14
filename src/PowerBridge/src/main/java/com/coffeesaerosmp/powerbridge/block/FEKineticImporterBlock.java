package com.coffeesaerosmp.powerbridge.block;

import com.coffeesaerosmp.powerbridge.registry.ModBlockEntities;
import com.coffeesaerosmp.powerbridge.blockentity.FEKineticImporterBE;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

public class FEKineticImporterBlock extends Block implements EntityBlock {

    public FEKineticImporterBlock() {
        super(Properties.of()
            .mapColor(MapColor.METAL)
            .strength(3.5f, 6.0f)
            .sound(SoundType.COPPER)
            .requiresCorrectToolForDrops());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FEKineticImporterBE(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        net.minecraft.world.level.Level level,
        BlockState state,
        BlockEntityType<T> type
    ) {
        return type == ModBlockEntities.FE_KINETIC_IMPORTER.get()
            ? (lvl, pos, st, be) -> ((FEKineticImporterBE) be).tick()
            : null;
    }
}
