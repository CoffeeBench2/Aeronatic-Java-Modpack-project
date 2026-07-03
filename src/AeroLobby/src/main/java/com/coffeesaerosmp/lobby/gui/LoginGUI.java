package com.coffeesaerosmp.lobby.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * Custom 3-row inventory GUI shown to cracked players on join.
 *
 * Layout (27 slots, rows 0-2):
 *   Row 0: [glass][glass][glass][glass][glass][glass][glass][glass][glass]
 *   Row 1: [glass][glass][glass][LOGIN][skull][REGISTER][glass][glass][glass]
 *   Row 2: [glass][glass][glass][glass][glass][glass][glass][glass][glass]
 *
 * LOGIN slot = 12, skull (player head) = 13, REGISTER slot = 14
 */
public class LoginGUI {

    public static final String TITLE_LOGIN = "aerogui:login";
    public static final String TITLE_WELCOME = "aerogui:welcome";
    public static final int SLOT_LOGIN = 12;
    public static final int SLOT_REGISTER = 14;
    public static final int SLOT_ENTER = 13;

    public static Inventory buildLoginGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27,
            Component.text("Coffees Aero SMP", NamedTextColor.AQUA)
                     .decoration(TextDecoration.BOLD, true));

        fillBorder(inv);

        // Player head in centre
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
        skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(player.getUniqueId()));
        skullMeta.displayName(Component.text(player.getName(), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        skull.setItemMeta(skullMeta);
        inv.setItem(13, skull);

        // LOGIN button
        ItemStack loginBtn = new ItemStack(Material.EMERALD);
        ItemMeta loginMeta = loginBtn.getItemMeta();
        loginMeta.displayName(Component.text("▶  Login", NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        loginMeta.lore(List.of(
            Component.text("Welcome back!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Click to enter your password.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        loginBtn.setItemMeta(loginMeta);
        inv.setItem(SLOT_LOGIN, loginBtn);

        // REGISTER button
        ItemStack regBtn = new ItemStack(Material.NAME_TAG);
        ItemMeta regMeta = regBtn.getItemMeta();
        regMeta.displayName(Component.text("✦  Create Account", NamedTextColor.YELLOW)
            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        regMeta.lore(List.of(
            Component.text("First time here?", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Pick a display name and password.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        regBtn.setItemMeta(regMeta);
        inv.setItem(SLOT_REGISTER, regBtn);

        return inv;
    }

    /** Shown to premium players — no login needed, just a welcome + join button. */
    public static Inventory buildPremiumWelcomeGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27,
            Component.text("Welcome back, " + player.getName() + "!", NamedTextColor.GOLD)
                     .decoration(TextDecoration.BOLD, true));

        fillBorder(inv);

        // Player head
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
        skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(player.getUniqueId()));
        skullMeta.displayName(Component.text("⭐ Premium Account", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        skull.setItemMeta(skullMeta);
        inv.setItem(13, skull);

        // Enter world button
        ItemStack enterBtn = new ItemStack(Material.NETHER_STAR);
        ItemMeta enterMeta = enterBtn.getItemMeta();
        enterMeta.displayName(Component.text("► Enter the World", NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        enterMeta.lore(List.of(
            Component.text("Mojang account verified ✓", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Click to join the server.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        enterBtn.setItemMeta(enterMeta);
        inv.setItem(SLOT_ENTER, enterBtn);

        return inv;
    }

    private static void fillBorder(Inventory inv) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        pane.setItemMeta(meta);

        int[] border = {0,1,2,3,4,5,6,7,8, 9,10,11,15,16,17, 18,19,20,21,22,23,24,25,26};
        for (int slot : border) inv.setItem(slot, pane.clone());
    }
}
