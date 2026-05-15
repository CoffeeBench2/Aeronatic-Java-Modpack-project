package com.coffeesaerosmp.powerbridge.content.dynamo;

import com.coffeesaerosmp.powerbridge.config.PowerBridgeConfig;
import com.coffeesaerosmp.powerbridge.register.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Block entity for the Kinetic Dynamo.
 *
 * Energy flow: Create shaft rotation → FE generation → adjacent blocks / IE wires.
 *
 * Generation: fePerRpm × |RPM| FE per tick.
 * Push strategy: each server tick, the dynamo pushes up to maxTransfer FE to
 * every adjacent block that exposes an IEnergyStorage capability (NeoForge).
 * Immersive Engineering wire connectors work automatically since IE wires
 * detect any block with this capability — no IImmersiveConnectable needed.
 *
 * Stress applied to the network = |RPM| × 1.0.  This is intentionally heavy
 * — players need real Create infrastructure to keep the dynamo spinning at
 * high speed.
 *
 * Buffer back-pressure: once the buffer is ≥ 90 % full the dynamo stops
 * accepting rotation speed-updates (it cannot generate any more FE until
 * something drains the buffer), which keeps the stress model honest.
 */
public class KineticDynamoBlockEntity extends KineticBlockEntity {

    // ── State ────────────────────────────────────────────────────────────────

    private int energyStored = 0;

    // ── Energy capability interface (output-only) ────────────────────────────

    /**
     * Adjacent conduits / wires may pull FE from this face.
     * We allow extraction so IE wire connectors can drain us passively.
     */
    private final IEnergyStorage energyInterface = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int max, boolean sim) { return 0; }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extract = Math.min(energyStored, maxExtract);
            if (!simulate && extract > 0) {
                energyStored -= extract;
                setChanged();
            }
            return extract;
        }

        @Override public int  getEnergyStored()    { return energyStored; }
        @Override public int  getMaxEnergyStored() { return PowerBridgeConfig.DYNAMO_BUFFER.get(); }
        @Override public boolean canExtract()       { return true; }
        @Override public boolean canReceive()       { return false; }
    };

    // ── Constructor ──────────────────────────────────────────────────────────

    public KineticDynamoBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Capability registration ───────────────────────────────────────────────

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            ModBlockEntities.KINETIC_DYNAMO.get(),
            (be, side) -> be.energyInterface
        );
    }

    // ── Create kinetic overrides ──────────────────────────────────────────────

    /**
     * Stress applied to the network equals the absolute shaft speed.
     * Overriding here bypasses Create's config-driven table for custom blocks.
     */
    @Override
    public float calculateStressApplied() {
        return Math.abs(speed);
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    public static void serverTick(
        net.minecraft.world.level.Level level,
        BlockPos pos, BlockState state,
        KineticDynamoBlockEntity be
    ) {
        be.tick();
    }

    @Override
    public void tick() {
        if (level == null || level.isClientSide) return;
        super.tick();

        float rpm     = Math.abs(speed);
        int   maxBuf  = PowerBridgeConfig.DYNAMO_BUFFER.get();

        // Generate FE from shaft rotation
        if (rpm > 0 && energyStored < maxBuf) {
            int generated = (int) (rpm * PowerBridgeConfig.DYNAMO_FE_PER_RPM.get());
            energyStored  = Math.min(energyStored + generated, maxBuf);
            setChanged();
        }

        // Push FE to all adjacent blocks that can accept it
        if (energyStored > 0) {
            pushFEToNeighbors();
        }
    }

    /**
     * Iterates all six faces and pushes up to maxTransfer FE to each neighbour
     * that exposes an IEnergyStorage capability via NeoForge.
     *
     * IE wire connectors placed against the dynamo will auto-detect the
     * capability and drain it through the wire network — no special wiring needed.
     */
    private void pushFEToNeighbors() {
        if (level == null) return;
        int maxTransfer = PowerBridgeConfig.DYNAMO_MAX_TRANSFER.get();

        for (Direction dir : Direction.values()) {
            if (energyStored <= 0) break;

            IEnergyStorage neighbor = level.getCapability(
                Capabilities.EnergyStorage.BLOCK,
                worldPosition.relative(dir),
                dir.getOpposite()
            );

            if (neighbor == null || !neighbor.canReceive()) continue;

            int toSend    = Math.min(energyStored, maxTransfer);
            int accepted  = neighbor.receiveEnergy(toSend, false);
            if (accepted > 0) {
                energyStored -= accepted;
                setChanged();
            }
        }
    }

    // ── Goggle tooltip ────────────────────────────────────────────────────────

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        float rpm      = Math.abs(speed);
        int   genPerT  = (int) (rpm * PowerBridgeConfig.DYNAMO_FE_PER_RPM.get());
        tooltip.add(Component.literal(
            "§6Kinetic Dynamo §r| Buffer: " + energyStored + " / "
            + PowerBridgeConfig.DYNAMO_BUFFER.get() + " FE"
        ));
        tooltip.add(Component.literal(
            "  Speed: " + (int) rpm + " RPM  |  Output: " + genPerT + " FE/t"
        ));
        return super.addToGoggleTooltip(tooltip, sneaking);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        energyStored = tag.getInt("EnergyStored");
    }

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("EnergyStored", energyStored);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public int getEnergyStored() { return energyStored; }
}
