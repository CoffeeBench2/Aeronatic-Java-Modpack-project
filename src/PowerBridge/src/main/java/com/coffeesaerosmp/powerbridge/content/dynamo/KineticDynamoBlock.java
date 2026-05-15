package com.coffeesaerosmp.powerbridge.content.dynamo;

import com.coffeesaerosmp.powerbridge.register.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
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
 * Kinetic Dynamo — electromagnetic induction generator.
 *
 * A Create shaft spins a magnetised rotor inside copper coils, inducing
 * an EMF that charges the internal FE buffer. The buffer is pushed each
 * tick to adjacent FE-accepting blocks (including IE wire connectors).
 *
 * Shaft connects on the FACING face. Stress impact = |RPM| × 1.0 so it
 * is a real load on the kinetic network — you need enough generators to
 * drive it at the desired speed.
 */
public class KineticDynamoBlock extends BaseEntityBlock implements IRotate {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public static final MapCodec<KineticDynamoBlock> CODEC = simpleCodec(props -> new KineticDynamoBlock());

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public KineticDynamoBlock() {
        super(Properties.of()
            .strength(3.5f, 6.0f)
            .sound(SoundType.COPPER)
            .requiresCorrectToolForDrops()
            .noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    // ── Block state ──────────────────────────────────────────────────────────

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

    // ── Block entity ──────────────────────────────────────────────────────────

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KineticDynamoBlockEntity(ModBlockEntities.KINETIC_DYNAMO.get(), pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type
    ) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.KINETIC_DYNAMO.get(),
            KineticDynamoBlockEntity::serverTick);
    }

    // ── IRotate — Create kinetic network integration ──────────────────────────

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }
}
