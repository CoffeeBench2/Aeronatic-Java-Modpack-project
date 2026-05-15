package com.coffeesaerosmp.powerbridge.register;

import com.coffeesaerosmp.powerbridge.PowerBridgeMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(PowerBridgeMod.MOD_ID);

    // ── Crafting ingredients ────────────────────────────────────────────────

    /** Iron ingot magnetized via strong Create shaft — core ingredient. */
    public static final DeferredItem<Item> MAGNETIZED_STEEL =
        ITEMS.register("magnetized_steel", () -> new Item(new Item.Properties()));

    /** Copper wire wound around a magnetized steel core — used in both blocks. */
    public static final DeferredItem<Item> ELECTROMAGNETIC_COIL =
        ITEMS.register("electromagnetic_coil", () -> new Item(new Item.Properties()));

    // ── Block items ─────────────────────────────────────────────────────────

    public static final DeferredItem<BlockItem> FE_MOTOR_ITEM =
        ITEMS.registerSimpleBlockItem("fe_motor", ModBlocks.FE_MOTOR);

    public static final DeferredItem<BlockItem> KINETIC_DYNAMO_ITEM =
        ITEMS.registerSimpleBlockItem("kinetic_dynamo", ModBlocks.KINETIC_DYNAMO);
}
