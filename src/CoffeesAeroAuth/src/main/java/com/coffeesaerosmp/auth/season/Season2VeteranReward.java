package com.coffeesaerosmp.auth.season;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.mail.MailService;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One-time Season 2 loyalty reward for players who played BOTH Season 1 and Season 2 (owner 2026-10-07).
 *
 * <p><b>Why only them.</b> The S3 migration freezes {@code season1_playtime = total_playtime} only where
 * it was still 0 — so a player who JOINED in Season 2 had their S2 hours frozen into that column, was
 * flagged {@code season1_veteran}, and was mailed a veteran reward for their S2 hours at launch (152 of
 * the 154 launch mails, just labelled "Season 1"). A player who had played Season 1 already had a
 * non-zero snapshot, so their Season 2 hours were never rewarded at all — 25 players, SweetYuzu among
 * them. This closes that gap, on the SAME tier table, so both groups are paid alike for Season 2.
 *
 * <p>Season 2 hours = {@code season_start_playtime - season1_playtime} (lifetime at S3 start, minus the
 * frozen S1 total). Rows where the two are equal are exactly the S2-only players already paid.
 *
 * <p><b>ONE-SHOT, guarded by {@code server_flags.s2_veteran_mailout} (1.13.24).</b> 1.13.22 relied on the
 * mail's {@code vet:s2:<uuid>} dedupe key alone — but a player may DELETE a mail once it is claimed, which
 * removes the row and frees the key, so the next boot mailed the reward again (SweetYuzu claimed it twice,
 * SideBlackStar's second copy was removed unclaimed, 2026-10-08). The flag is written once every eligible
 * mail has been stored; with it set, this never runs again. The query runs off-thread.
 */
public final class Season2VeteranReward {
    private Season2VeteranReward() {}

    private static final long H = 3600L;
    static final String DONE_FLAG = "s2_veteran_mailout";

    public static void mailAll(MinecraftServer server) {
        var db = CoffeesAeroAuth.DB_MANAGER;
        if (server == null || db == null || !db.isAvailable() || !MailService.enabled()) return;
        AsyncIo.submit(() -> {
            List<UUID> uuids = new ArrayList<>();
            List<Long> secs = new ArrayList<>();
            try (Connection c = db.getConnection();
                 PreparedStatement done = c.prepareStatement("SELECT 1 FROM server_flags WHERE name = ?")) {
                done.setString(1, DONE_FLAG);
                try (ResultSet rs = done.executeQuery()) {
                    if (rs.next()) return;                          // already paid out — never again
                }
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.warn("[Season] S2 veteran flag check failed — skipped this boot: {}", e.toString());
                return;                                             // fail CLOSED: a missed boot costs nothing
            }
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, season_start_playtime - season1_playtime FROM players "
                   + "WHERE season1_playtime <> season_start_playtime "
                   + "AND season_start_playtime - season1_playtime >= ?")) {
                ps.setLong(1, H);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try { uuids.add(UUID.fromString(rs.getString(1))); secs.add(rs.getLong(2)); }
                        catch (IllegalArgumentException badUuid) { /* skip malformed row */ }
                    }
                }
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.warn("[Season] S2 veteran query failed — retried next boot: {}", e.toString());
                return;
            }
            server.execute(() -> {
                java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(uuids.size());
                java.util.concurrent.atomic.AtomicBoolean allStored = new java.util.concurrent.atomic.AtomicBoolean(true);
                for (int i = 0; i < uuids.size(); i++) {
                    send(server, uuids.get(i), secs.get(i), stored -> {
                        if (!stored) allStored.set(false);
                        if (left.decrementAndGet() == 0 && allStored.get()) markDone();
                    });
                }
                CoffeesAeroAuth.LOGGER.info("[Season] S2 veteran rewards: {} eligible.", uuids.size());
            });
        });
    }

    /** Records the payout as finished, so no later boot can re-send a mail a player has since deleted. */
    private static void markDone() {
        var db = CoffeesAeroAuth.DB_MANAGER;
        if (db == null) return;
        AsyncIo.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT IGNORE INTO server_flags (name, value, set_by, updated_at) VALUES (?,?,?,?)")) {
                ps.setString(1, DONE_FLAG);
                ps.setString(2, "1");
                ps.setString(3, "Season2VeteranReward");
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                CoffeesAeroAuth.LOGGER.info("[Season] S2 veteran mail-out complete — flagged, will not run again.");
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.warn("[Season] S2 veteran done-flag write failed: {}", e.toString());
            }
        });
    }

    private static void send(MinecraftServer server, UUID uuid, long seconds,
                             java.util.function.Consumer<Boolean> after) {
        int diamonds;
        boolean netherite = false;
        String tier;
        if (seconds >= 100 * H)      { diamonds = 64; netherite = true; tier = "Founder"; }
        else if (seconds >= 20 * H)  { diamonds = 32; tier = "Veteran"; }
        else if (seconds >= 5 * H)   { diamonds = 16; tier = "Regular"; }
        else                         { diamonds = 8;  tier = "Pioneer"; }

        List<ItemStack> items = new ArrayList<>();
        for (int left = diamonds; left > 0; left -= 64) items.add(new ItemStack(Items.DIAMOND, Math.min(64, left)));
        if (netherite) items.add(new ItemStack(Items.NETHERITE_INGOT, 1));

        long hours = seconds / H;
        final int dia = diamonds;
        final boolean neth = netherite;
        MailService.sendSystemReward(server, uuid,
            "Season 2 " + tier + " reward",
            "Thank you for the " + hours + " hours you flew with us in Season 2.",
            items, 0, "vet:s2:" + uuid,
            stored -> {
                CoffeesAeroAuth.LOGGER.info("[Season] S2 veteran reward to {} — tier={} ({}h): {} diamonds{} ({})",
                    uuid, tier, hours, dia, neth ? " + netherite ingot" : "", stored ? "mailed" : "NOT stored, retried next boot");
                after.accept(stored);
            });
    }
}
