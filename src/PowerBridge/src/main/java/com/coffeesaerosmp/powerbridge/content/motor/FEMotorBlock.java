package com.coffeesaerosmp.powerbridge.content.motor;

import com.coffeesaerosmp.powerbridge.register.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * FE Electric Motor — consumes Forge Energy to drive a Create kinetic shaft.
 *
 * Physics model: electromagnetic coils energised by FE induce rotation in
 * a magnetised steel rotor. RPM is set via right-click with a wrench (or
 * defaults to 64). Conversion: fePerRpm * targetRpm FE/tick → targetRpm RPM.
 *
 * Shaft exits from the FACING face. Wrench rotates the block.
 */
public class FEMotorBlock extends BaseEntityBlock implements IRotate {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public static final MapCodec<FEMotorBlock> CODEC = simpleCodec(props -> new FEMotorBlock());

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public FEMotorBlock() {
        super(Properties.of()
            .strength(3.5f, 6.0f)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
            .noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    // ── Block state ─────────────────────────────────────────────────────────

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ── Block entity ─────────────────────────────────────────────────────────

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FEMotorBlockEntity(ModBlockEntities.FE_MOTOR.get(), pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.FE_MOTOR.get(),
            FEMotorBlockEntity::serverTick);
    }

    // ── IRotate — Create kinetic network integration ─────────────────────────

    /** The shaft exits only from the face the motor is pointing at. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    /** Rotation axis follows the FACING direction. */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }
}
