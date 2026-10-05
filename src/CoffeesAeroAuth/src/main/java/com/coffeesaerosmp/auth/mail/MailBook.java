package com.coffeesaerosmp.auth.mail;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows a letter as a real written-book screen.
 *
 * <p>The client only opens a book that is in its hand, so the server tells the client — and ONLY the
 * client — that the selected hotbar slot holds the letter, opens it, then immediately re-sends the
 * real slot. The server-side inventory is never touched, so there is nothing to duplicate or lose:
 * the worst a modified client can do is keep displaying a book it does not have.
 */
public final class MailBook {

    private static final int WIDTH = 19, LINES = 13;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault());

    private MailBook() {}

    public static void open(ServerPlayer player, String sender, long createdAt, String body) {
        String header = "§6✉ §lLetter§r\n§7from §0" + sender + "\n§7" + DATE.format(Instant.ofEpochMilli(createdAt)) + "\n\n";
        List<List<String>> textPages = MailRules.bookPages(body, WIDTH, LINES - 4);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (int i = 0; i < textPages.size(); i++) {
            String text = (i == 0 ? header : "") + "§0" + String.join("\n", textPages.get(i));
            pages.add(Filterable.passThrough(Component.literal(text)));
        }
        String title = ("From " + sender).length() > 32 ? "Letter" : "From " + sender;
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough(title), sender, 0, pages, true));

        player.closeContainer();
        int slot = 36 + player.getInventory().selected;            // hotbar slot in the inventory menu
        player.connection.send(new ClientboundContainerSetSlotPacket(0, player.inventoryMenu.incrementStateId(), slot, book));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        player.connection.send(new ClientboundContainerSetSlotPacket(0, player.inventoryMenu.incrementStateId(), slot,
            player.getInventory().getSelected().copy()));
    }
}
