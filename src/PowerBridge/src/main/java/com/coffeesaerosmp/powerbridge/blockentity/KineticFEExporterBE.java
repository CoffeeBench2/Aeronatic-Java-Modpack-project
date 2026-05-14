package com.coffeesaerosmp.powerbridge.blockentity;

import com.coffeesaerosmp.powerbridge.config.BridgeConfig;
import com.coffeesaerosmp.powerbridge.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Kinetic FE Exporter — takes Create rotational energy (SU) from the
 * connected shaft network and converts it to FE, pushing it into
 * adjacent blocks (IE wires, Crafts & Additions cables, etc.).
 *
 * Conversion: [FE_PER_RPM_EXPORT config] FE/tick produced per 1 RPM consumed.
 * Intentionally slightly lower than import rate to represent conversion loss.
 */
public class KineticFEExporterBE extends KineticBlockEntity {

    private final OutputEnergyStorage energyStorage;

    public KineticFEExporterBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.KINETIC_FE_EXPORTER.get(), pos, state);
        this.energyStorage = new OutputEnergyStorage(BridgeConfig.EXPORTER_BUFFER.get());
    }

    // ----- Tick -----

    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;

        float rpm = Math.abs(getSpeed());
        if (rpm < 1f) return;

        // Generate FE proportional to current speed
        int feGenerated = (int) (rpm * BridgeConfig.FE_PER_RPM_EXPORT.get());
        energyStorage.internalReceive(feGenerated);

        // Push FE to all adjacent blocks
        pushFEToNeighbors();
    }

    private void pushFEToNeighbors() {
        if (energyStorage.getEnergyStored() == 0) return;

        for (Direction side : Direction.values()) {
            if (energyStorage.getEnergyStored() == 0) break;

            var neighborBE = level.getBlockEntity(worldPosition.relative(side));
            if (neighborBE == null) continue;

            var neighborCap = level.getCapability(
                Capabilities.EnergyStorage.BLOCK,
                worldPosition.relative(side),
                side.getOpposite()
            );

            if (neighborCap != null && neighborCap.canReceive()) {
                int toSend = Math.min(energyStorage.getEnergyStored(), 1000);
                int accepted = neighborCap.receiveEnergy(toSend, false);
                energyStorage.internalExtract(accepted);
            }
        }
    }

    // ----- Energy Storage accessor for capability registration -----

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    // ----- NBT persistence -----

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putInt("StoredFE", energyStorage.getEnergyStored());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        energyStorage.setEnergy(tag.getInt("StoredFE"));
    }

    // ----- Inner output energy buffer -----

    private static class OutputEnergyStorage extends EnergyStorage {

        OutputEnergyStorage(int capacity) {
            super(capacity, 0, capacity); // extract-only externally; receive is internal
        }

        void internalReceive(int amount) {
            energy = Math.min(capacity, energy + amount);
        }

        void internalExtract(int amount) {
            energy = Math.max(0, energy - amount);
        }

        void setEnergy(int value) {
            energy = Math.min(value, capacity);
        }

        @Override
        public boolean canReceive() {
            return false; // FE only flows OUT; Create SU flows IN
        }
    }
}
