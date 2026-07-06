package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stashes a player's real inventory while they are in the auth waiting-room (lobby dimension) and
 * restores it when they enter the world via {@code /spawn} (or the "Teleport to Spawn" paper).
 *
 * <p>No-loss guarantees:
 * <ul>
 *   <li>The stash is <b>persisted to disk before the inventory is cleared</b>, so a crash/kill the
 *       instant after clearing cannot lose items — they are already on disk.</li>
 *   <li>Stashing is <b>idempotent</b>: a reconnect while already stashed re-applies the lobby loadout
 *       (paper) but never overwrites the persisted real inventory with the empty one.</li>
 *   <li>Restore loads items first and only then drops the stash entry; a failed restore keeps the
 *       stash for a later retry rather than discarding it.</li>
 * </ul>
 * File-backed (JSON of uuid → base64 gzipped NBT) — independent of the SQL schema.
 */
public class LobbyInventoryStash {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();
    private static final String MARKER = "AeroLobbyPaper";

    private final Path file;
    private final Map<UUID, String> stash = new ConcurrentHashMap<>();

    public LobbyInventoryStash(Path dataDir) {
        this.file = dataDir.resolve("lobby_inventory_stash.json");
    }

    public void initialize() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, String> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw != null) raw.forEach((k, v) -> {
                try { stash.put(UUID.fromString(k), v); } catch (IllegalArgumentException ignored) {}
            });
            CoffeesAeroAuth.LOGGER.info("[LobbyStash] Loaded {} stashed lobby inventories.", stash.size());
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("[LobbyStash] Failed to load stash file", e);
        }
    }

    public boolean hasStash(UUID uuid) { return stash.containsKey(uuid); }

    /**
     * Persist + clear the player's inventory and hand them the lobby loadout (the spawn paper).
     * Idempotent: if a stash already exists (reconnect), only re-applies the loadout.
     */
    public void stashAndClear(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String existing = stash.get(uuid);
        if (existing != null) {
            // Already stashed (reconnect / re-entry) — DO NOT overwrite the persisted stash. But the
            // player may be CARRYING real items: a death in the lobby respawns them at world spawn
            // WITHOUT going through /spawn-restore, and a failed restore sends them into the world
            // empty-handed with the stash kept — either way they can return holding items the loadout
            // clear below would destroy. Merge those into the stash (persisted first) so nothing is lost.
            try {
                mergeCarriedIntoStash(player, existing);
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[LobbyStash] Could not merge carried items for {} — leaving inventory intact",
                    player.getGameProfile().getName(), e);
                return;                         // never clear what we failed to persist
            }
            applyLobbyLoadout(player);
            return;
        }
        try {
            String encoded = encode(player);
            stash.put(uuid, encoded);
            saveToFile();                       // persist BEFORE we clear — the no-loss ordering
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[LobbyStash] Could not stash inventory for {} — leaving it intact",
                player.getGameProfile().getName(), e);
            return;                             // never clear what we failed to persist
        }
        applyLobbyLoadout(player);
    }

    /**
     * Restore the player's stashed inventory (and remove the paper). Returns true if items were
     * restored, false if there was nothing stashed (brand-new player → an empty inventory is correct).
     */
    public boolean restore(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String encoded = stash.get(uuid);       // peek — do NOT consume until a restore actually succeeds

        // NO STASH → the player reached the lobby without being stashed: an op using /lobby, or a second
        // lobby trip after the first /spawn already consumed the stash. Their inventory is REAL (carried
        // in), so the old unconditional clearContent() here DESTROYED it — the reported "everything gone
        // but steaks" bug. Fix: never wipe an unstashed player. Just strip the lobby paper (if any) and
        // keep everything else. A brand-new player is ALWAYS stashed (empty inv) via stashAndClear, so
        // they never fall into this branch holding a lobby loadout.
        if (encoded == null) {
            var inv = player.getInventory();
            boolean changed = false; int kept = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                if (isLobbyPaper(s)) { inv.setItem(i, ItemStack.EMPTY); changed = true; }
                else kept++;
            }
            if (changed) player.inventoryMenu.broadcastChanges();
            if (kept > 0) CoffeesAeroAuth.LOGGER.info(
                "[LobbyStash] {} left the lobby with no stash — kept {} carried stacks (no wipe).",
                player.getGameProfile().getName(), kept);
            return false;
        }

        // STASH EXISTS → the intended swap. Vendor exception: the lobby steak vendor's Cooked Beef is the
        // ONLY lobby item allowed out — collect it before the clear, re-add after the restore.
        List<ItemStack> allowedOut = new ArrayList<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(Items.COOKED_BEEF)) allowedOut.add(s.copy());
        }
        player.getInventory().clearContent();   // drop the lobby loadout (paper) + capped extras
        try {
            decodeInto(player, encoded);
        } catch (Exception e) {
            giveAll(player, allowedOut);         // stash left in place (not yet removed) for a retry
            player.inventoryMenu.broadcastChanges();
            CoffeesAeroAuth.LOGGER.error("[LobbyStash] Restore failed for {} — kept stash for retry",
                player.getGameProfile().getName(), e);
            return false;
        }
        stash.remove(uuid);                      // consume ONLY after a successful restore
        saveToFile();
        giveAll(player, allowedOut);
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private static void giveAll(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (!player.getInventory().add(s)) player.drop(s, false);
        }
    }

    // ── Lobby loadout (the "Teleport to Spawn" paper) ──────────────────────────

    private void applyLobbyLoadout(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getInventory().setItem(0, makeLobbyPaper());
        player.getInventory().selected = 0;
        player.inventoryMenu.broadcastChanges();
    }

    public static ItemStack makeLobbyPaper() {
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.set(DataComponents.CUSTOM_NAME,
            Component.literal("§b§lTeleport to Spawn").setStyle(Style.EMPTY.withItalic(false)));
        CompoundTag marker = new CompoundTag();
        marker.putBoolean(MARKER, true);
        paper.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
        return paper;
    }

    public static boolean isLobbyPaper(ItemStack stack) {
        if (stack == null || !stack.is(Items.PAPER)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(MARKER);
    }

    /**
     * Folds any real (non-paper) items the player is carrying into their existing stash. Slot layout of
     * the original stash is preserved; carried items go into an "Overflow" list that {@code decodeInto}
     * re-adds on restore (spilling at the player's feet in the world if the inventory is full). The
     * merged stash is persisted BEFORE the caller clears the inventory — same no-loss ordering as above.
     */
    private void mergeCarriedIntoStash(ServerPlayer player, String existing) throws IOException {
        List<ItemStack> carried = new ArrayList<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !isLobbyPaper(s)) carried.add(s.copy());
        }
        if (carried.isEmpty()) return;          // just the paper / empty — nothing to merge
        CompoundTag wrapper = decodeWrapper(existing);
        ListTag overflow = wrapper.getList("Overflow", Tag.TAG_COMPOUND);
        for (ItemStack s : carried) overflow.add(s.save(player.registryAccess()));
        wrapper.put("Overflow", overflow);
        stash.put(player.getUUID(), encodeWrapper(wrapper));
        saveToFile();
        CoffeesAeroAuth.LOGGER.info("[LobbyStash] Merged {} carried stacks into existing stash for {}.",
            carried.size(), player.getGameProfile().getName());
    }

    // ── (De)serialization ──────────────────────────────────────────────────────

    private static String encode(ServerPlayer player) throws IOException {
        ListTag items = player.getInventory().save(new ListTag());
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("Items", items);
        return encodeWrapper(wrapper);
    }

    private static String encodeWrapper(CompoundTag wrapper) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(wrapper, baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static CompoundTag decodeWrapper(String encoded) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
    }

    private static void decodeInto(ServerPlayer player, String encoded) throws IOException {
        CompoundTag wrapper = decodeWrapper(encoded);
        ListTag items = wrapper.getList("Items", Tag.TAG_COMPOUND);
        player.getInventory().clearContent();
        player.getInventory().load(items);
        // Overflow: items merged in from a re-entry while carrying (see mergeCarriedIntoStash).
        ListTag overflow = wrapper.getList("Overflow", Tag.TAG_COMPOUND);
        for (int i = 0; i < overflow.size(); i++) {
            ItemStack s = ItemStack.parse(player.registryAccess(), overflow.getCompound(i))
                .orElse(ItemStack.EMPTY);
            if (!s.isEmpty() && !player.getInventory().add(s)) player.drop(s, false);
        }
    }

    private synchronized void saveToFile() {
        Map<String, String> raw = new LinkedHashMap<>();
        stash.forEach((uuid, v) -> raw.put(uuid.toString(), v));
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(raw, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("[LobbyStash] Failed to save stash file", e);
        }
    }
}
