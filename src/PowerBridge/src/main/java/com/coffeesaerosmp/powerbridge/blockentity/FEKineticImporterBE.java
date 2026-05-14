package com.coffeesaerosmp.powerbridge.blockentity;

import com.coffeesaerosmp.powerbridge.config.BridgeConfig;
import com.coffeesaerosmp.powerbridge.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * FE Kinetic Importer — pulls FE from adjacent blocks or IE wires,
 * converts it to Create rotational energy (SU) and injects it into
 * the connected shaft network.
 *
 * Conversion: [FE_PER_RPM config] FE/tick consumed per 1 RPM generated.
 * The importer scales RPM linearly with the FE it has buffered,
 * up to MAX_IMPORT_RPM.
 */
public class FEKineticImporterBE extends GeneratingKineticBlockEntity {

    private final InternalEnergyStorage energyStorage;
    private float currentRPM = 0f;

    public FEKineticImporterBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FE_KINETIC_IMPORTER.get(), pos, state);
        this.energyStorage = new InternalEnergyStorage(BridgeConfig.IMPORTER_BUFFER.get());
    }

    // ----- Create Kinetic API -----

    @Override
    public float getGeneratedSpeed() {
        return currentRPM;
    }

    // ----- Tick -----

    public void tick() {
        if (level == null || level.isClientSide) return;

        int feAvailable = energyStorage.getEnergyStored();
        int fePerRpm    = BridgeConfig.FE_PER_RPM.get();
        int maxRpm      = BridgeConfig.MAX_IMPORT_RPM.get();

        // Target RPM = min(available FE / fePerRpm, maxRpm)
        float targetRPM = feAvailable > 0
            ? Math.min((float) feAvailable / fePerRpm, maxRpm)
            : 0f;

        // Consume FE proportional to target RPM this tick
        if (targetRPM > 0) {
            int feConsumed = (int) (targetRPM * fePerRpm);
            energyStorage.internalExtract(feConsumed);
        }

        if (Math.abs(targetRPM - currentRPM) > 0.5f) {
            currentRPM = targetRPM;
            updateGeneratedRotation();
            sendData();
            setChanged();
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
        tag.putFloat("CurrentRPM", currentRPM);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        energyStorage.setEnergy(tag.getInt("StoredFE"));
        currentRPM = tag.getFloat("CurrentRPM");
    }

    // ----- Inner energy buffer -----

    private static class InternalEnergyStorage extends EnergyStorage {

        InternalEnergyStorage(int capacity) {
            super(capacity, capacity, 0); // receive-only externally; extract is internal
        }

        /** Internal extract — bypasses the canExtract() guard. */
        void internalExtract(int amount) {
            energy = Math.max(0, energy - amount);
        }

        void setEnergy(int value) {
            energy = Math.min(value, capacity);
        }

        @Override
        public boolean canExtract() {
            return false; // FE only flows IN; Create SU flows OUT
        }
    }
}
