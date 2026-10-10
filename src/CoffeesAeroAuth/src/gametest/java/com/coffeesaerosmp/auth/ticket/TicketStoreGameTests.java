package com.coffeesaerosmp.auth.ticket;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /ticket's DB layer against a REAL MySQL — run-gametest/.env points at the S3 BETA test database
 * (unused since the S3 cutover), never the live one. Creates a row, counts it, updates it, reads it back,
 * and deletes it again.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class TicketStoreGameTests {

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void ticketRoundTripsThroughMysql(GameTestHelper h) {
        var server = h.getLevel().getServer();
        UUID who = UUID.randomUUID();
        AtomicLong id = new AtomicLong(0);          // 0 = pending, -1 = failed
        AtomicInteger count = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger stage = new AtomicInteger(0);

        h.succeedWhen(() -> {
            if (!TicketStore.available()) throw new GameTestAssertException("DB not up yet (is run-gametest/.env set?)");
            switch (stage.get()) {
                case 0 -> {
                    stage.set(1);
                    TicketStore.create(server, who, "gametest", "GameTest ticket — safe to ignore", "overworld 0 64 0", id::set);
                    throw new GameTestAssertException("creating");
                }
                case 1 -> {
                    if (id.get() == 0) throw new GameTestAssertException("waiting for insert");
                    if (id.get() < 0) h.fail("insert failed (see log)");
                    stage.set(2);
                    TicketStore.countSince(server, who, 0, count::set);
                    throw new GameTestAssertException("counting");
                }
                case 2 -> {
                    if (count.get() == Integer.MIN_VALUE) throw new GameTestAssertException("waiting for count");
                    if (count.get() != 1) h.fail("expected 1 ticket for the test uuid, got " + count.get());
                    TicketStore.update(id.get(), Ticket.Status.CLOSED, "tester", "tester: done");
                    stage.set(3);
                    throw new GameTestAssertException("updating");
                }
                case 3 -> {
                    Ticket t = TicketStore.getNow(id.get());
                    if (t == null) h.fail("row vanished");
                    if (t.status() != Ticket.Status.CLOSED) throw new GameTestAssertException("waiting for update");
                    if (!"tester".equals(t.closedBy())) h.fail("closed_by not stored: " + t.closedBy());
                    if (!"tester: done".equals(t.lastReply())) h.fail("last_reply not stored: " + t.lastReply());
                    if (!t.message().startsWith("GameTest ticket")) h.fail("message mangled: " + t.message());
                    try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                         PreparedStatement ps = c.prepareStatement("DELETE FROM tickets WHERE uuid=?")) {
                        ps.setString(1, who.toString());
                        ps.executeUpdate();
                    } catch (Exception e) {
                        h.fail("cleanup failed: " + e.getMessage());
                    }
                }
                default -> h.fail("bad stage");
            }
        });
    }
}
