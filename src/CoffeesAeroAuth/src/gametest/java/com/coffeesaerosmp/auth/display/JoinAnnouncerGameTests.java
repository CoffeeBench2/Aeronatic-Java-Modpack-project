package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.PreviewSelections;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Custom join/leave lines: shown once per session, only when set, never twice on a relog. */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class JoinAnnouncerGameTests {

    @GameTest(template = "empty", batch = "cosmetics_join", timeoutTicks = 40)
    public static void aJoinLineShowsOnceAndALeaveLineFollows(GameTestHelper h) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "joinmsg");
        var list = h.getLevel().getServer().getPlayerList();
        list.op(p.getGameProfile());
        // ADD, never replace: replacing wiped other tests' previewers mid-run (made the trail test flaky).
        com.coffeesaerosmp.auth.store.Previewers.add(p.getUUID());
        int j0 = JoinAnnouncer.joinLines, l0 = JoinAnnouncer.leaveLines;

        JoinAnnouncer.announceJoin(p);                    // nothing set yet
        if (JoinAnnouncer.joinLines != j0) h.fail("announced a join line the player never set");

        PreviewSelections.put(p.getUUID(), new Selection(null, false, null, "has come aboard", null, null, "has gone ashore"));
        JoinAnnouncer.announceJoin(p);
        if (JoinAnnouncer.joinLines != j0 + 1) h.fail("the join line was not announced");
        JoinAnnouncer.announceJoin(p);                    // a second store load in the same session
        if (JoinAnnouncer.joinLines != j0 + 1) h.fail("the join line repeated within one session");

        JoinAnnouncer.announceLeave(list, p);
        if (JoinAnnouncer.leaveLines != l0 + 1) h.fail("the leave line was not announced");

        JoinAnnouncer.announceJoin(p);                    // rejoin within 60 s
        if (JoinAnnouncer.joinLines != j0 + 1) h.fail("a quick relog announced the join line again");

        list.deop(p.getGameProfile());
        PreviewSelections.forget(p.getUUID());
        GameTestPlayers.remove(h, p);
        h.succeed();
    }
}
