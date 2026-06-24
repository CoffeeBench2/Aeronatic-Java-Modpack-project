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
        if (stash.containsKey(uuid)) {
            // Already stashed (reconnect / re-entry) — DO NOT overwrite real items with the empty inv.
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
        player.getInventory().clearContent();   // drop the lobby loadout (paper)
        String encoded = stash.remove(uuid);
        if (encoded == null) {
            player.inventoryMenu.broadcastChanges();
            return false;
        }
        try {
            decodeInto(player, encoded);
        } catch (Exception e) {
            stash.put(uuid, encoded);           // keep it for a retry rather than losing items
            saveToFile();
            player.inventoryMenu.broadcastChanges();
            CoffeesAeroAuth.LOGGER.error("[LobbyStash] Restore failed for {} — kept stash for retry",
                player.getGameProfile().getName(), e);
            return false;
        }
        saveToFile();
        player.inventoryMenu.broadcastChanges();
        return true;
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

    // ── (De)serialization ──────────────────────────────────────────────────────

    private static String encode(ServerPlayer player) throws IOException {
        ListTag items = player.getInventory().save(new ListTag());
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("Items", items);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(wrapper, baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void decodeInto(ServerPlayer player, String encoded) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        CompoundTag wrapper = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        ListTag items = wrapper.getList("Items", Tag.TAG_COMPOUND);
        player.getInventory().clearContent();
        player.getInventory().load(items);
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
