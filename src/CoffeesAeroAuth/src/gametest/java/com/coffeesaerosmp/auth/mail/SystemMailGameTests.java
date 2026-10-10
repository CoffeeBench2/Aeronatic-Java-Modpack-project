package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /mail system}, driven through the REAL window by a mock staff member: shift-click a Book & Quill
 * and an item in, press the buttons, send — then read the row back from MySQL (run-gametest/.env = the S3
 * BETA test DB, never live) and delete it. Every mail these tests write has "GameTest" in its subject.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class SystemMailGameTests {

    private static final int HOTBAR_0 = SystemMailMenu.SIZE + 27;   // menu index of hotbar slot 0

    private static ItemStack book(String... pages) {
        ItemStack b = new ItemStack(Items.WRITABLE_BOOK);
        b.set(DataComponents.WRITABLE_BOOK_CONTENT,
            new WritableBookContent(java.util.Arrays.stream(pages).map(Filterable::passThrough).toList()));
        return b;
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static SystemMailMenu openWith(GameTestHelper h, ServerPlayer p) {
        SystemMailCompose.open(p);
        if (!(p.containerMenu instanceof SystemMailMenu m)) throw new GameTestAssertException("window did not open");
        return m;
    }

    private static void click(SystemMailMenu m, ServerPlayer p, int slot) {
        m.clicked(slot, 0, ClickType.PICKUP, p);
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void sendsAsTheServerWithTheBookAndCopiedItems(GameTestHelper h) {
        if (!MailStoreAccess.available()) { h.fail("DB down (is run-gametest/.env set?)"); return; }
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "mailtest");
        p.getInventory().setItem(0, book("GameTest patch notes line one", "", "Second page here"));
        p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));

        SystemMailMenu m = openWith(h, p);
        click(m, p, SystemMailCompose.B_TYPE);                        // UPDATE -> PATCH_NOTES
        m.quickMoveStack(p, HOTBAR_0);                                // book -> book slot
        m.quickMoveStack(p, HOTBAR_0 + 1);                            // diamonds -> attachments
        SystemMailCompose.Draft d = SystemMailCompose.DRAFTS.get(p.getUUID());
        if (d == null) { h.fail("no draft"); return; }
        if (d.type != SystemMail.Type.PATCH_NOTES) h.fail("type button did not cycle: " + d.type);
        if (!"GameTest patch notes line one\n\nSecond page here".equals(SystemMailCompose.body(d)))
            h.fail("book not read: " + SystemMailCompose.body(d));
        if (count(p, Items.WRITABLE_BOOK) != 0 || count(p, Items.DIAMOND) != 0) h.fail("items did not move into the window");

        // Fields typed in chat in real use — set directly here.
        d.subject = "GameTest notes";
        d.audience = SystemMail.Audience.ONE;
        d.playerUuid = p.getUUID();
        d.playerName = p.getGameProfile().getName();
        click(m, p, SystemMailCompose.B_SEND);

        if (SystemMailCompose.DRAFTS.containsKey(p.getUUID())) h.fail("draft not cleared after send");
        if (count(p, Items.WRITABLE_BOOK) != 1) h.fail("book did not come back");
        if (count(p, Items.DIAMOND) != 3) h.fail("attachments are COPIED: the originals must come back");

        h.succeedWhen(() -> {
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT sender, subject, body, items, sender_uuid FROM mail WHERE uuid=?")) {
                ps.setString(1, p.getUUID().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new GameTestAssertException("waiting for the mail row");
                    if (!MailService.SYSTEM.equals(rs.getString(1))) h.fail("sender is " + rs.getString(1) + ", not the server");
                    if (!"📜 Patch notes: GameTest notes".equals(rs.getString(2))) h.fail("subject: " + rs.getString(2));
                    String body = rs.getString(3);
                    if (!body.startsWith(SystemMail.Type.PATCH_NOTES.header()) || !body.endsWith("Second page here"))
                        h.fail("body: " + body);
                    if (rs.getString(4) == null || !rs.getString(4).contains("diamond")) h.fail("items: " + rs.getString(4));
                    if (rs.getString(5) != null) h.fail("System mail must carry no sender_uuid (no return-to-sender)");
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM mail WHERE uuid=?")) {
                    del.setString(1, p.getUUID().toString());
                    del.executeUpdate();
                }
            } catch (java.sql.SQLException e) {
                h.fail("DB: " + e.getMessage());
            }
            GameTestPlayers.remove(h, p);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void typingTheSubjectKeepsTheItemsAndLogoutReturnsThem(GameTestHelper h) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "mailtest");
        p.getInventory().setItem(0, book("Hello"));
        p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 2));
        SystemMailMenu m = openWith(h, p);
        m.quickMoveStack(p, HOTBAR_0);
        m.quickMoveStack(p, HOTBAR_0 + 1);
        click(m, p, SystemMailCompose.B_SUBJECT);                     // closes the window to type in chat
        if (p.containerMenu instanceof SystemMailMenu) h.fail("window should close for chat input");
        if (count(p, Items.WRITABLE_BOOK) != 0 || count(p, Items.DIAMOND) != 0)
            h.fail("items left the draft while the subject was being typed");
        SystemMailCompose.open(p);                                    // /mail system again: same draft
        if (!(p.containerMenu instanceof SystemMailMenu m2) || m2.attachments().size() != 1
                || !"Hello".equals(SystemMailCompose.body(SystemMailCompose.DRAFTS.get(p.getUUID()))))
            h.fail("reopened window lost the book or attachments");
        p.closeContainer();                                           // a plain close returns everything...
        if (count(p, Items.WRITABLE_BOOK) != 1 || count(p, Items.DIAMOND) != 2) h.fail("plain close did not return the items");

        // ...and a draft parked for input is returned at logout.
        SystemMailMenu m3 = openWith(h, p);
        m3.quickMoveStack(p, HOTBAR_0);
        click(m3, p, SystemMailCompose.B_SUBJECT);
        SystemMailCompose.onLogout(p);
        if (count(p, Items.WRITABLE_BOOK) != 1) h.fail("logout did not return the parked book");
        if (SystemMailCompose.DRAFTS.containsKey(p.getUUID())) h.fail("draft survived logout");
        GameTestPlayers.remove(h, p);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancelReturnsEverythingAndSendsNothing(GameTestHelper h) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "mailtest");
        p.getInventory().setItem(0, book("Hello"));
        SystemMailMenu m = openWith(h, p);
        m.quickMoveStack(p, HOTBAR_0);
        click(m, p, SystemMailCompose.B_CANCEL);
        if (count(p, Items.WRITABLE_BOOK) != 1) h.fail("cancel did not return the book");
        if (SystemMailCompose.DRAFTS.containsKey(p.getUUID())) h.fail("draft survived cancel");
        GameTestPlayers.remove(h, p);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aBroadcastNeedsASecondClick(GameTestHelper h) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "mailtest");
        p.getInventory().setItem(0, book("GameTest broadcast"));
        SystemMailMenu m = openWith(h, p);
        m.quickMoveStack(p, HOTBAR_0);
        SystemMailCompose.Draft d = SystemMailCompose.DRAFTS.get(p.getUUID());
        d.subject = "GameTest broadcast";
        d.audience = SystemMail.Audience.ONLINE;
        click(m, p, SystemMailCompose.B_SEND);                        // first click only arms
        if (d.sending || !SystemMailCompose.DRAFTS.containsKey(p.getUUID())) h.fail("a single click sent a broadcast");
        if (!m.getSlot(SystemMailCompose.B_SEND).getItem().is(Items.RED_CONCRETE)) h.fail("send button did not arm");
        click(m, p, SystemMailCompose.B_CANCEL);                      // don't actually mail the test server
        GameTestPlayers.remove(h, p);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sendRefusesAnIncompleteDraft(GameTestHelper h) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "mailtest");
        SystemMailMenu m = openWith(h, p);
        click(m, p, SystemMailCompose.B_SEND);                        // no subject, no book
        if (!SystemMailCompose.DRAFTS.containsKey(p.getUUID())) h.fail("an empty draft was sent");
        click(m, p, SystemMailCompose.B_CANCEL);
        GameTestPlayers.remove(h, p);
        h.succeed();
    }

    /** Test-only view of the DB check MailStore uses. */
    private static final class MailStoreAccess {
        static boolean available() {
            return CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
        }
    }
}
