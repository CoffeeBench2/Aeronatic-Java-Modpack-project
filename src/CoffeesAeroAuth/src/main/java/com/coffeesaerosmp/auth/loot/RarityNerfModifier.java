package com.coffeesaerosmp.auth.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Thins out high-value loot without rewriting a single loot table.
 *
 * <h2>Why a Global Loot Modifier, and why it lives here</h2>
 * <b>Lootr is not the lever.</b> Its config is entirely about container conversion, decay, refresh,
 * breaking and notifications — it has no rarity or value setting at all. {@code randomise_seed}
 * only decides whether each player rolls their own copy of the SAME table; turning it off makes
 * loot identical, not rarer. What appears in a chest comes from the LOOT TABLE, so that is what has
 * to change.
 *
 * <p>KubeJS can't do it either: KubeJS 2101.x dropped the loot events (they moved to LootJS, which
 * this pack does not ship). A plain datapack can't do it globally either, because a Global Loot
 * Modifier needs a codec registered in code — datapacks supply the JSON, a mod must supply the type.
 * Rather than add a third-party mod for one feature, the codec lives in this mod and the RULES stay
 * in a datapack: {@code data/coffees_aero_auth/loot_modifiers/rarity_nerf.json}, editable with
 * {@code /reload}.
 *
 * <h2>Behaviour</h2>
 * For each generated stack whose item is listed, roll {@code keep_chance}. On failure the stack is
 * dropped from the loot. So {@code keep_chance = 0.3} keeps roughly 30% of the diamonds that would
 * have generated — the chest still generates, it is just less often a jackpot.
 *
 * <p><b>{@code table_pattern} matters more than it looks.</b> A GLM runs on EVERY loot roll —
 * blocks, mobs, fishing, everything. Left unfiltered this would also delete an evoker's totem or a
 * dropped ore's diamond, which is not "chests are too rich", it is "the game is broken". The
 * default {@code .*chests/.*} confines it to container loot. Widen it deliberately or not at all.
 */
public class RarityNerfModifier extends LootModifier {

    public static final MapCodec<RarityNerfModifier> CODEC = RecordCodecBuilder.mapCodec(inst ->
        codecStart(inst).and(inst.group(
            com.mojang.serialization.Codec.FLOAT
                .optionalFieldOf("keep_chance", 1.0f).forGetter(m -> m.keepChance),
            com.mojang.serialization.Codec.FLOAT
                .optionalFieldOf("count_scale", 1.0f).forGetter(m -> m.countScale),
            com.mojang.serialization.Codec.STRING
                .optionalFieldOf("table_pattern", ".*chests/.*").forGetter(m -> m.tablePattern),
            BuiltInRegistries.ITEM.byNameCodec().listOf()
                .fieldOf("items").forGetter(m -> m.items)
        )).apply(inst, RarityNerfModifier::new));

    private final float      keepChance;
    private final float      countScale;
    private final String     tablePattern;
    private final List<Item> items;
    private final Pattern    compiled;

    public RarityNerfModifier(LootItemCondition[] conditions, float keepChance, float countScale,
                              String tablePattern, List<Item> items) {
        super(conditions);
        this.keepChance   = keepChance;
        this.countScale   = countScale;
        this.tablePattern = tablePattern;
        this.items        = items;
        // Compiled once at load, not per roll — doApply runs on every loot generation on the server
        // thread, and Pattern.compile there would be a real cost during chunk population.
        Pattern p;
        try {
            p = Pattern.compile(tablePattern);
        } catch (Exception e) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                "[Loot] Bad table_pattern '{}' — falling back to chests only. {}", tablePattern, e.toString());
            p = Pattern.compile(".*chests/.*");
        }
        this.compiled = p;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        if (items.isEmpty()) return loot;
        if (keepChance >= 1.0f && countScale >= 1.0f) return loot;      // nothing to do

        var tableId = context.getQueriedLootTableId();
        if (tableId == null || !compiled.matcher(tableId.toString()).matches()) return loot;

        // 1) keep_chance — drop the WHOLE stack. "Sometimes there are no diamonds at all."
        //    Rolled PER STACK, not per table, so a chest listing several valuables isn't
        //    all-or-nothing — each one independently survives at keep_chance.
        if (keepChance < 1.0f) {
            loot.removeIf(stack -> !stack.isEmpty()
                && items.contains(stack.getItem())
                && context.getRandom().nextFloat() >= keepChance);
        }

        // 2) count_scale — shrink what survives. "There are still diamonds, just fewer."
        //    Applied AFTER the drop roll so the two stack cleanly, and floored at 1 rather than 0:
        //    a scale that silently deleted stacks would be keep_chance wearing a different name,
        //    and would make 5 diamonds vanish entirely at scale 0.1.
        if (countScale < 1.0f) {
            for (ItemStack stack : loot) {
                if (stack.isEmpty() || !items.contains(stack.getItem())) continue;
                int scaled = Math.max(1, Math.round(stack.getCount() * countScale));
                if (scaled < stack.getCount()) stack.setCount(scaled);
            }
        }
        return loot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
