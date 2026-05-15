package com.coffeesaerosmp.powerbridge.content.motor;

import com.coffeesaerosmp.powerbridge.config.PowerBridgeConfig;
import com.coffeesaerosmp.powerbridge.register.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.List;

/**
 * Block entity for the FE Electric Motor.
 *
 * Energy flow: external FE source → internal buffer → shaft rotation.
 *
 * The motor exposes an IEnergyStorage capability (receive-only) so that
 * Immersive Engineering wires, conduits, and any other FE source can
 * directly pipe energy in.  The shaft RPM is configurable in-game with
 * a wrench (targetRpm clamped to [1, MOTOR_MAX_RPM]).
 *
 * Stress capacity = |targetRpm| × 1.5 — the motor can sustain full-speed
 * networks plus a 50 % overhead for burst loads.
 */
public class FEMotorBlockEntity extends GeneratingKineticBlockEntity {

    // ── State ────────────────────────────────────────────────────────────────

    private int energyStored = 0;
    private int targetRpm    = 64;

    // ── Energy capability interface (receive-only) ───────────────────────────

    private final IEnergyStorage energyInterface = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int cap     = PowerBridgeConfig.MOTOR_BUFFER.get();
            int space   = cap - energyStored;
            int receive = Math.min(space, maxReceive);
            if (!simulate && receive > 0) {
                energyStored += receive;
                setChanged();
            }
            return receive;
        }

        @Override public int extractEnergy(int max, boolean sim) { return 0; }
        @Override public int  getEnergyStored()    { return energyStored; }
        @Override public int  getMaxEnergyStored() { return PowerBridgeConfig.MOTOR_BUFFER.get(); }
        @Override public boolean canExtract()       { return false; }
        @Override public boolean canReceive()       { return true; }
    };

    // ── Constructor ──────────────────────────────────────────────────────────

    public FEMotorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Capability registration ───────────────────────────────────────────────

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            ModBlockEntities.FE_MOTOR.get(),
            (be, side) -> be.energyInterface
        );
    }

    // ── Create kinetic overrides ──────────────────────────────────────────────

    /**
     * Returns the motor's target RPM if the buffer has enough FE for this tick,
     * otherwise returns 0 (motor stalls gracefully without overstressing the
     * network — any connected kinetic block entity will simply stop).
     */
    @Override
    public float getGeneratedSpeed() {
        if (level == null || level.isClientSide) return 0;
        int feNeeded = targetRpm * PowerBridgeConfig.MOTOR_FE_PER_RPM.get();
        return (energyStored >= feNeeded && targetRpm > 0)
            ? convertToDirection(targetRpm, getBlockState().getValue(FEMotorBlock.FACING))
            : 0;
    }

    /**
     * Stress capacity scales with RPM so larger motors can drive heavier networks.
     * 1.5× overhead means the motor can handle minor speed fluctuations without
     * immediately overstressing.
     */
    @Override
    public float calculateAddedStressCapacity() {
        return Math.abs(targetRpm) * 1.5f;
    }

    // ── Server tick ──────────────────────────────────────────────────────────

    public static void serverTick(
        net.minecraft.world.level.Level level,
        BlockPos pos, BlockState state,
        FEMotorBlockEntity be
    ) {
        be.tick();
    }

    @Override
    public void tick() {
        if (level == null || level.isClientSide) return;

        int feNeeded = (int) (Math.abs(speed) * PowerBridgeConfig.MOTOR_FE_PER_RPM.get());
        if (feNeeded > 0) {
            if (energyStored >= feNeeded) {
                energyStored -= feNeeded;
                setChanged();
            } else {
                // Not enough energy — trigger a speed update so Create can stall
                updateGeneratedRotation();
            }
        }

        super.tick();
    }

    // ── Goggle / display tooltip ─────────────────────────────────────────────

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(Component.literal(
            "§bFE Motor §r| Buffer: " + energyStored + " / " + PowerBridgeConfig.MOTOR_BUFFER.get() + " FE"
        ));
        tooltip.add(Component.literal(
            "  Target: " + targetRpm + " RPM  |  Draw: "
            + (targetRpm * PowerBridgeConfig.MOTOR_FE_PER_RPM.get()) + " FE/t"
        ));
        return super.addToGoggleTooltip(tooltip, sneaking);
    }

    // ── NBT serialisation ─────────────────────────────────────────────────────

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        energyStored = tag.getInt("EnergyStored");
        targetRpm    = tag.getInt("TargetRpm");
    }

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("EnergyStored", energyStored);
        tag.putInt("TargetRpm",    targetRpm);
    }

    // ── Public API (for wrench, CC:T, etc.) ──────────────────────────────────

    public void setTargetRpm(int rpm) {
        this.targetRpm = Math.max(1, Math.min(Math.abs(rpm), PowerBridgeConfig.MOTOR_MAX_RPM.get()));
        updateGeneratedRotation();
        setChanged();
    }

    public int  getTargetRpm()    { return targetRpm; }
    public int  getEnergyStored() { return energyStored; }
}
