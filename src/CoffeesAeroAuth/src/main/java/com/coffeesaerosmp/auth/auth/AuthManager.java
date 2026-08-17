package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.DatabaseManager;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.CredentialStore;
import com.coffeesaerosmp.auth.profile.DisplayNameManager;
import com.coffeesaerosmp.auth.util.NetUtil;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.coffeesaerosmp.auth.util.Sounds;

public class AuthManager {

    private final CredentialStore    store;
    private final DisplayNameManager displayNames;

    private final Map<UUID, AuthState> authStates           = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      lastSpawnTp          = new ConcurrentHashMap<>(); // in-world /spawn cooldown
    private final Map<UUID, Long>      lastHomeTp           = new ConcurrentHashMap<>(); // /home cooldown
    private final Map<UUID, float[]>   lobbyVitals          = new ConcurrentHashMap<>(); // {food,saturation,health} stashed on lobby entry, restored on /spawn
    private final Map<UUID, double[]>  frozenPos            = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      joinTimes            = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>   failedAttempts       = new ConcurrentHashMap<>();
    private final Set<UUID>            pendingLobbyTeleport = ConcurrentHashMap.newKeySet();
    private final Set<UUID>            awaitingType         = ConcurrentHashMap.newKeySet();
    private final Set<UUID>            nameHidden           = ConcurrentHashMap.newKeySet();
    private final SessionTokenManager  sessionTokens        = new SessionTokenManager();

    private static final UUID PACK_UUID = UUID.fromString("c0ffee00-ae40-4001-8000-cafebabe0001");

    public AuthManager(CredentialStore store, DisplayNameManager displayNames, DatabaseManager db) {
        this.store        = store;
        this.displayNames = displayNames;
        this.sessionTokens.init(db);
    }

    // ── Join / Leave ──────────────────────────────────────────────────────────

    public void onPlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();

        // Operators can skip auth entirely (setup convenience).
        if (AuthConfig.BYPASS_AUTH_FOR_OPS.get() && player.hasPermissions(4)) {
            authStates.put(uuid, AuthState.AUTHENTICATED);
            return;
        }

        // Behind the Velocity proxy (player-info-forwarding = NONE) EVERY player reaches this
        // backend with an offline (v3) UUID, so we cannot tell premium from cracked here. Hold
        // the player frozen and wait for AeroVelocity's aerosmp:player_type message, handled by
        // resolvePlayerType(). onTick() falls back to offline if no signal arrives (direct connect).
        joinTimes.put(uuid, System.currentTimeMillis());
        failedAttempts.put(uuid, 0);
        frozenPos.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
        authStates.put(uuid, AuthState.AWAITING_TYPE);
        awaitingType.add(uuid);
        NameVisibility.hide(player);   // hide their name during the login session
        nameHidden.add(uuid);

        pushResourcePack(player);

        // Prefer the forwarded identity (Velocity modern forwarding) when it is trusted; otherwise stay
        // frozen in AWAITING_TYPE and wait for AeroVelocity's aerosmp:player_type message (handled by
        // resolvePlayerType), with onTick() falling back to offline on timeout. Detection happens at the
        // proxy — here we only READ the forwarded result.
        PlayerIdentityReader.Identity forwarded = PlayerIdentityReader.read(player);
        if (forwarded != PlayerIdentityReader.Identity.UNKNOWN) {
            CoffeesAeroAuth.LOGGER.info("[Auth] {} resolved from forwarded UUID as {}.",
                player.getGameProfile().getName(), forwarded);
            resolvePlayerType(player, forwarded == PlayerIdentityReader.Identity.PREMIUM);
        } else {
            CoffeesAeroAuth.LOGGER.info("[Auth] {} frozen in AWAITING_TYPE — requesting gate auth cookie.",
                player.getGameProfile().getName());
            try {
                player.connection.send(new net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket(
                    CoffeesAeroAuth.AUTH_COOKIE_KEY));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Resolves a held player once their premium/cracked status is known — from the proxy's
     * {@code aerosmp:player_type} message or from the onTick timeout fallback. Idempotent:
     * only the first call per join is honoured.
     */
    public void resolvePlayerType(ServerPlayer player, boolean premium) {
        UUID uuid = player.getUUID();
        if (!awaitingType.remove(uuid)) return;   // already resolved, or not awaiting
        if (isAuthenticated(uuid)) return;

        String mcName = player.getGameProfile().getName();
        CoffeesAeroAuth.LOGGER.info("[Auth] Resolving {} as {}.", mcName, premium ? "PREMIUM" : "OFFLINE");

        // Anti-spoof: a cracked player whose MC name IS (or is too close to) a verified player's name is
        // turned away with the standard message + an example offline handle to relaunch with.
        if (!premium && AuthConfig.KICK_ON_NAME_CONFLICT.get()) {
            String reservedBy = null;
            // exact: their MC name equals a verified player's display name
            UUID nameOwner = store.getDisplayNameOwner(mcName);
            if (nameOwner != null && !nameOwner.equals(uuid)) {
                PlayerProfile ownerProfile = store.get(nameOwner);
                if (ownerProfile != null && ownerProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
                    // SELF-IMPERSONATION GUARD (2026-08-08). `!nameOwner.equals(uuid)` is not enough:
                    // a PREMIUM player who lands here has been resolved OFFLINE (cookie timeout,
                    // missing secret, invalid/absent cookie, or a direct connect), so the backend
                    // gave them md5("OfflinePlayer:"+name) instead of their real Mojang UUID. Same
                    // human, different UUID — and the old check kicked them as an impostor of
                    // themselves. That is the "I'm premium but it says I'm offline" report.
                    //
                    // A genuine impostor picks SOMEONE ELSE'S name, so their offline UUID still
                    // derives from the name they chose — the test below holds for both, which is
                    // why it discriminates: it asks whether this connection is the offline alias of
                    // the very account that owns the name.
                    if (UUIDUtil.isSelfOfflineAlias(uuid, ownerProfile.username)) {
                        CoffeesAeroAuth.LOGGER.warn(
                            "[Auth] {} is the offline alias of PREMIUM {} — not a spoof, letting them in. "
                            + "Their gate cookie did not arrive; playtime lives on {} and this session "
                            + "accrues to {}. Run /authmod duplicates.",
                            uuid, nameOwner, nameOwner, uuid);
                    } else {
                        reservedBy = mcName;
                    }
                }
            }
            // near: their MC name impersonates a known premium username (lookalike / substring / edit-distance 1)
            if (reservedBy == null && CoffeesAeroAuth.WATCHDOG != null) {
                reservedBy = CoffeesAeroAuth.WATCHDOG.findImpersonatedPremium(mcName);
            }
            if (reservedBy != null) {
                String suggestion = suggestOfflineName(mcName);
                player.connection.disconnect(Component.literal(
                    "§cThe name §e" + mcName + "§c is reserved by (or too close to) a verified player §7(" + reservedBy + ")§c.\n" +
                    "§7Relaunch your account with a different name — for example §a" + suggestion + "§7."));
                return;
            }
        }

        PlayerProfile.AccountType type = premium ? PlayerProfile.AccountType.PREMIUM : PlayerProfile.AccountType.OFFLINE;
        CredentialStore.GetOrCreateResult r = store.getOrCreate(uuid, mcName, type);
        PlayerProfile profile = r.profile();

        // Record the first IP this account ever logged in from (set once, never overwritten).
        if (profile.firstIp == null || profile.firstIp.isBlank()) {
            profile.firstIp = NetUtil.getPlayerIP(player);
            store.save(profile);
        }

        // A returning player first created offline but now Mojang-verified is upgraded to premium.
        if (premium && profile.getAccountType() != PlayerProfile.AccountType.PREMIUM) {
            profile.accountType  = PlayerProfile.AccountType.PREMIUM.name();
            profile.nameApproved = true;
            store.save(profile);
        }

        if (r.isNew() && premium) {
            displayNames.claimInitialName(profile);
        }
        failedAttempts.put(uuid, 0);

        if (premium) {
            enterPremiumFlow(player, profile, r.isNew());
        } else {
            enterOfflineFlow(player, profile, mcName);
        }
    }

    private void enterPremiumFlow(ServerPlayer player, PlayerProfile profile, boolean isFirstJoin) {
        UUID uuid = player.getUUID();
        // Premium players are Mojang-verified — auto-authenticated, no password.
        authStates.put(uuid, AuthState.AUTHENTICATED);
        com.coffeesaerosmp.auth.events.PlayerAuthEvents.onPremiumResolved(player, isFirstJoin);

        if (!profile.firstJoinComplete) {
            // First-ever join → orientation in their private lobby, then /spawn into the world.
            routeToLobbyRoom(uuid, profile);
            profile.sessionStartEpoch = System.currentTimeMillis();
            store.save(profile);
            sendLobbyOrientation(player, profile);
        } else if (shouldRouteThroughLobby(profile)) {
            // Away long enough that they go through the lobby like everyone else (2026-07-27:
            // previously premium ALWAYS skipped it). onAuthenticated() sees the lobby dimension,
            // guarantees safe footing and prompts /spawn — and /spawn is the single exit path, so
            // the lobby stash restore and saved-position resume both still run exactly once.
            routeToLobbyRoom(uuid, profile);
            onAuthenticated(player, profile, false);
        } else {
            onAuthenticated(player, profile, false);   // back within the window → straight into the world
        }
    }

    /**
     * Lobby-routing rule (user decision 2026-07-27): a returning player passes through the shared
     * lobby unless they were away for less than {@code lobbyBypassMinutes}.
     *
     * <p>Deliberately separate from {@code sessionGraceMinutes}, which is the SECURITY dial deciding
     * whether an offline player must re-type {@code /login}. Keeping them apart means the lobby
     * window can be generous (2 h) without widening the window in which someone sharing an IP could
     * resume a cracked account without the password.</p>
     *
     * <p>Uses {@code profile.lastSeen}, stamped on logout. A profile with no {@code lastSeen} (never
     * recorded, e.g. pre-existing players before this change) routes through the lobby — the safe
     * default. {@code lobbyBypassMinutes = 0} means always route through the lobby.</p>
     */
    private boolean shouldRouteThroughLobby(PlayerProfile profile) {
        int bypassMinutes = AuthConfig.LOBBY_BYPASS_MINUTES.get();
        if (bypassMinutes <= 0) return true;               // 0 = lobby every single join
        if (profile == null || profile.lastSeen <= 0) return true;   // unknown → be safe
        long awayMs = System.currentTimeMillis() - profile.lastSeen;
        if (awayMs < 0) return false;                      // clock skew — don't punish the player
        return awayMs >= bypassMinutes * 60_000L;
    }

    private void enterOfflineFlow(ServerPlayer player, PlayerProfile profile, String mcName) {
        UUID uuid = player.getUUID();

        // Legacy migration: offline players that fully completed join before room system existed
        if (!profile.nameApproved && profile.firstJoinComplete && profile.passwordHash != null) {
            profile.nameApproved = true;
            store.save(profile);
        }

        // Premium reclaim: if this offline player's approved display name has since become reserved by
        // (or too close to) a verified player, revoke it and send them back through /setname in the
        // lobby. They can't re-pick a name near the verified one — findNameConflict enforces that.
        if (profile.nameApproved && CoffeesAeroAuth.WATCHDOG != null) {
            String current = profile.displayName != null ? profile.displayName : profile.username;
            String clash   = CoffeesAeroAuth.WATCHDOG.findImpersonatedPremium(current);
            if (clash != null) {
                if (profile.displayName != null) store.releaseDisplayName(profile.displayName);
                profile.nameApproved        = false;
                profile.nameApprovalPending = false;
                profile.pendingDisplayName  = null;
                store.save(profile);
                send(player, TextUtil.PREFIX + "§eYour name §f" + current + "§e is now reserved by a verified player §7(" + clash + ")§e.");
                send(player, TextUtil.PREFIX + "§ePlease choose a new display name — for example §a" + suggestOfflineName(mcName) + "§e.");
                CoffeesAeroAuth.LOGGER.info("[Auth] Revoked offline name '{}' for {} — impersonates verified '{}'.", current, mcName, clash);
            }
        }

        if (profile.nameApproved) {
            if (profile.passwordHash == null) {
                // Admin reset their password: an ESTABLISHED account (approved name, room, playtime,
                // claims + world data all keyed by the unchanged UUID) that just needs a new password.
                // Without this branch they deadlock: /login says "use /register" while /register
                // requires LOBBY_REGISTER state. Nothing else on the profile is touched.
                routeToLobbyRoom(uuid, profile);
                authStates.put(uuid, AuthState.LOBBY_REGISTER);
                frozenPos.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
                send(player, TextUtil.PREFIX + "§eYour password was reset by an admin.");
                send(player, TextUtil.PREFIX + "§eSet a new one: §a/register <password> <confirmPassword>");
                return;
            }
            String ip = NetUtil.getPlayerIP(player);
            SessionTokenManager.TokenStatus tokenStatus = sessionTokens.check(uuid, ip);
            if (tokenStatus == SessionTokenManager.TokenStatus.VALID) {
                // Within the reconnect grace window — resume right where they were.
                authStates.put(uuid, AuthState.AUTHENTICATED);
                onAuthenticated(player, profile, false);
                send(player, TextUtil.PREFIX + "§aSession resumed — login skipped.");
            } else {
                // Grace window passed (or IP changed) → send to the lobby room to /login, then /spawn.
                if (tokenStatus == SessionTokenManager.TokenStatus.IP_CHANGED) {
                    sessionTokens.invalidate(uuid);
                    send(player, TextUtil.PREFIX + "§eIP address changed — session invalidated.");
                }
                routeToLobbyRoom(uuid, profile);
                authStates.put(uuid, AuthState.PENDING);
                send(player, TextUtil.PREFIX + "§eWelcome back — please log in: §a/login <password>");
            }

        } else {
            // New or mid-approval offline player → private room flow
            if (profile.roomSlot < 0) {
                profile.roomSlot      = CoffeesAeroAuth.ROOM_MANAGER != null
                    ? CoffeesAeroAuth.ROOM_MANAGER.assignSlot(uuid) : 0;
                profile.roomCreatedAt = System.currentTimeMillis();
                store.save(profile);
            } else if (CoffeesAeroAuth.ROOM_MANAGER != null) {
                // Re-register slot as in-use (room manager was reset on server restart)
                // runStartupCleanup already does this, but guard here too
            }

            if (profile.passwordHash == null) {
                authStates.put(uuid, AuthState.LOBBY_REGISTER);
            } else if (profile.nameApprovalPending && profile.pendingDisplayName != null) {
                authStates.put(uuid, AuthState.LOBBY_PENDING);
                // Re-add to approval queue (queue is in-memory, lost on restart)
                if (CoffeesAeroAuth.APPROVAL_QUEUE != null) {
                    CoffeesAeroAuth.APPROVAL_QUEUE.requeue(uuid, mcName, profile.pendingDisplayName);
                }
            } else {
                authStates.put(uuid, AuthState.LOBBY_NAMING);
            }

            // Teleport to room on first tick (so player is fully joined first)
            pendingLobbyTeleport.add(uuid);
            frozenPos.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
        }
    }

    /** Assigns a private-room slot (if needed) and queues the deferred teleport into it. */
    private void routeToLobbyRoom(UUID uuid, PlayerProfile profile) {
        if (profile.roomSlot < 0) {
            profile.roomSlot      = CoffeesAeroAuth.ROOM_MANAGER != null
                ? CoffeesAeroAuth.ROOM_MANAGER.assignSlot(uuid) : 0;
            profile.roomCreatedAt = System.currentTimeMillis();
            store.save(profile);
        }
        pendingLobbyTeleport.add(uuid);
    }

    /** Welcome shown to a premium player on their first-ever join while they wait in the lobby. */
    private void sendLobbyOrientation(ServerPlayer player, PlayerProfile profile) {
        String serverName  = AuthConfig.SERVER_DISPLAY_NAME.get();
        String displayName = profile.displayName != null ? profile.displayName : profile.username;
        player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§l" + serverName)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§aWelcome aboard, §f" + displayName + "§a!")));
        send(player, TextUtil.PREFIX + "§aWelcome to §6" + serverName + "§a, §f" + displayName + "§a!");
        send(player, TextUtil.PREFIX + "§eHave a look around the lobby, then type §a/spawn§e to enter the server.");
        pushResourcePack(player);
    }

    /**
     * One-time starter currency on first /spawn, paid as a mixed Numismatics wallet (greedy "change":
     * cog=64, sprocket=16, bevel=8, spur=1 — e.g. 200 → 3 cogs + 1 bevel). No-op if Numismatics is absent.
     */
    private void grantStartupBonus(ServerPlayer player) {
        int amount = AuthConfig.STARTUP_BONUS_SPURS.get();
        if (amount <= 0) return;
        int[]    values = {64, 16, 8, 1};
        String[] coins  = {"cog", "sprocket", "bevel", "spur"};
        boolean gaveAny = false;
        int remaining = amount;
        for (int i = 0; i < values.length; i++) {
            int count = remaining / values[i];
            if (count <= 0) continue;
            remaining -= count * values[i];
            if (giveCoin(player, coins[i], count)) gaveAny = true;
        }
        if (gaveAny)
            send(player, TextUtil.PREFIX + "§a✦ Welcome gift: §6" + amount + " spurs §ato get you started!");
    }

    /** Gives {@code count} of a Numismatics coin item, spilling to the ground if the inventory is full. */
    private boolean giveCoin(ServerPlayer player, String coinName, int count) {
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("numismatics", coinName));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return false; // Numismatics absent
        int max = new net.minecraft.world.item.ItemStack(item).getMaxStackSize();
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, n);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            remaining -= n;
        }
        return true;
    }

    private void pushResourcePack(ServerPlayer player) {
        String packUrl  = AuthConfig.RESOURCE_PACK_URL.get();
        String packHash = AuthConfig.RESOURCE_PACK_HASH.get();
        if (!packUrl.isBlank()) {
            try {
                player.connection.send(new ClientboundResourcePackPushPacket(
                    PACK_UUID, packUrl, packHash, true,
                    Optional.of(Component.literal("§6Coffees Aero SMP §7Resource Pack"))));
            } catch (Exception ignored) {}
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerProfile profile = store.get(uuid);
        if (profile != null) {
            if (profile.sessionStartEpoch > 0) {
                long secs = (System.currentTimeMillis() - profile.sessionStartEpoch) / 1000;
                profile.totalPlaytimeSeconds += secs;
                profile.sessionStartEpoch = 0;
            }
            // Remember the logoff spot in the MAIN world so /spawn resumes the player here on their next
            // return instead of dumping them at world spawn. Never record a lobby position — a returning
            // player restored into the void room grid would fall. First-timers have no return pos yet.
            if (isAuthenticated(uuid)
                    && player.level().dimension() != com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
                profile.returnDim = player.level().dimension().location().toString();
                profile.returnX   = player.getX();
                profile.returnY   = player.getY();
                profile.returnZ   = player.getZ();
            }
            // Stamp the logout time. This column existed in the schema and on PlayerProfile but was
            // never actually written (so /profile showed a stale "last seen"). It is now also the
            // clock for the lobby-bypass rule below — premium players have no session token, so
            // SessionTokenManager cannot answer "how long were they away?" for them.
            profile.lastSeen = System.currentTimeMillis();
            store.save(profile);
        }
        // Start the reconnect grace window from logout: a return within SESSION_GRACE_MINUTES skips
        // login; after it, the session is expired and the player must /login again (in the lobby).
        sessionTokens.touchOnLogout(uuid);
        // Premium equivalent: restart the same-IP cookie-replay grace so a kicked/crashed premium
        // player's direct reconnect (spent cookie) still resolves PREMIUM.
        PremiumReconnectGrace.touch(player.getGameProfile().getName());
        authStates.remove(uuid);
        frozenPos.remove(uuid);
        joinTimes.remove(uuid);
        failedAttempts.remove(uuid);
        pendingLobbyTeleport.remove(uuid);
        awaitingType.remove(uuid);
        nameHidden.remove(uuid);
        if (CoffeesAeroAuth.ROOM_MANAGER != null) CoffeesAeroAuth.ROOM_MANAGER.releaseFrozenSpot(uuid);
        NameVisibility.clear(player);
    }

    // ── Per-tick ──────────────────────────────────────────────────────────────

    public void onTick(ServerPlayer player) {
        UUID uuid = player.getUUID();

        // Full hunger in the lobby every tick — no starving (the "out of breath" oxygen bar can still
        // drop, but damage is off so it never hurts). The player's REAL overworld food/health are
        // stashed on lobby entry and restored on /spawn, so the lobby never affects overworld vitals.
        if (player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0f);
        }

        // No background music in the auth lobby: stop the MUSIC category client-side, re-sent
        // periodically because the client's music tracker keeps scheduling the next track.
        if (player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION
                && player.tickCount % 100 == 0) {
            try {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
                    null, net.minecraft.sounds.SoundSource.MUSIC));
                // Clean vanilla lobby: force the client to render CLEAR weather (no rain/thunder),
                // regardless of the shared server weather. Per-player, client-render only — never
                // touches the overworld's actual weather.
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING, 0.0f));
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0f));
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0f));
            } catch (Exception ignored) {}
        }

        // Deferred lobby teleport (first tick after join) — runs even for AUTHENTICATED orientation
        // players (premium first-join), so it precedes the isAuthenticated() early-return.
        if (pendingLobbyTeleport.remove(uuid)) {
            PlayerProfile profile = store.get(uuid);
            if (profile != null && profile.roomSlot >= 0 && CoffeesAeroAuth.ROOM_MANAGER != null) {
                CoffeesAeroAuth.ROOM_MANAGER.teleportToRoom(player, profile.roomSlot);
                if (!isAuthenticated(uuid)) {   // offline lobby/login players stay frozen on their ring pad
                    double[] pos = CoffeesAeroAuth.ROOM_MANAGER.getFrozenSpotFor(uuid);
                    frozenPos.put(uuid, pos);
                    player.teleportTo(pos[0], pos[1], pos[2]);
                }
                // Empty their real inventory (persisted) and hand them the "Teleport to Spawn" paper.
                // Idempotent across reconnects; restored on /spawn (handleSpawn) or via the paper.
                if (CoffeesAeroAuth.LOBBY_STASH != null) CoffeesAeroAuth.LOBBY_STASH.stashAndClear(player);
                // Stash the real overworld food/health once, so the lobby's full-hunger + regen never
                // leak into the overworld (restored on /spawn).
                lobbyVitals.computeIfAbsent(uuid, k -> new float[]{
                    player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), player.getHealth()});
            }
            return;
        }

        if (isAuthenticated(uuid)) {
            if (nameHidden.remove(uuid)) {
                PlayerProfile prof = store.get(uuid);
                NameMask.apply(player);   // masked BEFORE reveal so the badge team keys on the display name
                NameVisibility.reveal(player, prof != null
                    && prof.getAccountType() == PlayerProfile.AccountType.PREMIUM);
            }
            // Floating-island fall-catch: an authed player still in the lobby who drops off the island
            // is returned to the spawn pad (damage is off in the lobby, so this is cosmetic-safe).
            if (player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
                int floor = AuthConfig.LOBBY_FLOOR_Y.get();
                int drop  = AuthConfig.LOBBY_FALL_CATCH_DROP.get();
                if (player.getY() < floor - drop && CoffeesAeroAuth.ROOM_MANAGER != null) {
                    double[] pad = com.coffeesaerosmp.auth.lobby.PrivateRoomManager.spawnPad();
                    player.teleportTo(pad[0], pad[1], pad[2]);
                    player.setDeltaMovement(0, 0, 0);
                    player.fallDistance = 0;
                }
            }
            return;
        }

        // Awaiting the proxy's premium/cracked signal — keep frozen, fall back to offline on timeout.
        if (authStates.get(uuid) == AuthState.AWAITING_TYPE) {
            int timeout = AuthConfig.TYPE_RESOLVE_TIMEOUT_SECONDS.get();
            Long joined = joinTimes.get(uuid);
            if (timeout > 0 && joined != null && (System.currentTimeMillis() - joined) > timeout * 1000L) {
                resolvePlayerType(player, false);   // no signal — treat as offline / cracked
            } else {
                double[] held = frozenPos.get(uuid);
                if (held != null) {
                    player.teleportTo(held[0], held[1], held[2]);
                    player.setDeltaMovement(0, 0, 0);
                }
            }
            return;
        }

        // Auth timeout (PENDING state only)
        if (authStates.getOrDefault(uuid, AuthState.PENDING) == AuthState.PENDING) {
            int timeout = AuthConfig.AUTH_TIMEOUT_SECONDS.get();
            if (timeout > 0) {
                Long joined = joinTimes.get(uuid);
                if (joined != null && (System.currentTimeMillis() - joined) > timeout * 1000L) {
                    player.connection.disconnect(Component.literal(
                        "§cAuthentication timed out. Reconnect and log in within " + timeout + " seconds."));
                    return;
                }
            }
        }

        // Freeze position (all non-AUTHENTICATED states)
        double[] pos = frozenPos.get(uuid);
        if (pos != null) {
            player.teleportTo(pos[0], pos[1], pos[2]);
            player.setDeltaMovement(0, 0, 0);
        }

        // Reminder every 20 seconds (was every 5s — the wall-of-text half of the 07-18 spam report;
        // the instruction still repeats often enough that nobody gets stuck, without scrolling chat)
        if (player.tickCount % 400 == 0) {
            AuthState state = authStates.getOrDefault(uuid, AuthState.PENDING);
            switch (state) {
                case LOBBY_REGISTER ->
                    send(player, TextUtil.PREFIX + "§eSet up your account: §a/register <password> <confirmPassword>");
                case LOBBY_NAMING ->
                    send(player, TextUtil.PREFIX + "§eChoose your display name: §a/setname <displayName>");
                case LOBBY_PENDING ->
                    send(player, TextUtil.PREFIX + "§7Your display name is pending admin approval. Please wait...");
                default ->
                    send(player, TextUtil.PREFIX + "§eUse §a/login <password>§e to continue.");
            }
        }
    }

    // ── Auth commands ─────────────────────────────────────────────────────────

    public boolean handleLogin(ServerPlayer player, String password) {
        UUID uuid = player.getUUID();
        if (isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§aAlready logged in.");
            return true;
        }
        AuthState state = authStates.getOrDefault(uuid, AuthState.PENDING);
        if (isLobbyState(state)) {
            send(player, TextUtil.PREFIX + "§cComplete your lobby setup first.");
            return false;
        }
        PlayerProfile profile = store.get(uuid);
        if (profile == null || profile.passwordHash == null) {
            send(player, TextUtil.PREFIX + "§cNo account found. Use §a/register§c to create one.");
            return false;
        }
        if (!PasswordUtil.verify(password, profile.passwordSalt, profile.passwordHash)) {
            int    attempts = failedAttempts.merge(uuid, 1, Integer::sum);
            int    max      = AuthConfig.MAX_FAILED_ATTEMPTS.get();
            String ip       = NetUtil.getPlayerIP(player);
            if (CoffeesAeroAuth.WATCHDOG != null) {
                CoffeesAeroAuth.WATCHDOG.recordFailedLogin(uuid, ip, player.getGameProfile().getName());
                CoffeesAeroAuth.WATCHDOG.recordFailedLoginFromUnknownIp(uuid, ip, player.getGameProfile().getName());
            }
            if (max > 0 && attempts >= max) {
                if (CoffeesAeroAuth.WATCHDOG != null) {
                    CoffeesAeroAuth.WATCHDOG.getIpBanManager().banAuth(ip, 30_000L);
                }
                player.connection.disconnect(Component.literal(
                    "§cToo many failed login attempts. Please wait §e30§c seconds before reconnecting."));
                return false;
            }
            send(player, TextUtil.PREFIX + "§cWrong password." + (max > 0 ? " Attempt " + attempts + "/" + max + "." : ""));
            return false;
        }
        failedAttempts.put(uuid, 0);
        authStates.put(uuid, AuthState.AUTHENTICATED);
        onAuthenticated(player, profile, false);
        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.recordSuccessfulLogin(uuid, NetUtil.getPlayerIP(player), profile.displayName, false);
        }
        sessionTokens.createToken(uuid, NetUtil.getPlayerIP(player));
        return true;
    }

    public boolean handleRegister(ServerPlayer player, String password, String confirm) {
        UUID uuid = player.getUUID();
        if (isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§aAlready registered and logged in.");
            return true;
        }
        AuthState state = authStates.getOrDefault(uuid, AuthState.PENDING);
        if (state != AuthState.LOBBY_REGISTER) {
            send(player, TextUtil.PREFIX + "§cYou already have an account. Use §a/login <password>§c.");
            return false;
        }
        if (password.length() < 8) {
            send(player, TextUtil.PREFIX + "§cPassword must be at least 8 characters.");
            return false;
        }
        if (!password.equals(confirm)) {
            send(player, TextUtil.PREFIX + "§cPasswords do not match.");
            return false;
        }
        PlayerProfile profile = store.get(uuid);
        if (profile == null) return false;

        String salt = PasswordUtil.generateSalt();
        profile.passwordSalt = salt;
        profile.passwordHash = PasswordUtil.hash(password, salt);
        store.save(profile);

        if (profile.nameApproved) {
            // Re-registration after an admin password reset — the account is fully established
            // (approved name, room, playtime, claims/world data on the same UUID). Complete auth
            // exactly like a successful /login: no /setname, nothing else changes.
            failedAttempts.put(uuid, 0);
            authStates.put(uuid, AuthState.AUTHENTICATED);
            onAuthenticated(player, profile, false);
            if (CoffeesAeroAuth.WATCHDOG != null) {
                CoffeesAeroAuth.WATCHDOG.recordSuccessfulLogin(uuid, NetUtil.getPlayerIP(player), profile.displayName, false);
            }
            sessionTokens.createToken(uuid, NetUtil.getPlayerIP(player));
            send(player, TextUtil.PREFIX + "§aNew password set — welcome back, §f" + profile.displayName + "§a! Use §a/spawn§a to head out.");
            return true;
        }

        authStates.put(uuid, AuthState.LOBBY_NAMING);
        send(player, TextUtil.PREFIX + "§aPassword set! Now choose your display name:");
        send(player, TextUtil.PREFIX + "§a/setname <displayName>§7  (3–20 chars, letters/numbers/underscores)");
        return true;
    }

    /** /setname — only usable from LOBBY_NAMING state. */
    public boolean handleSetName(ServerPlayer player, String name) {
        UUID uuid = player.getUUID();
        if (authStates.getOrDefault(uuid, AuthState.PENDING) != AuthState.LOBBY_NAMING) {
            send(player, TextUtil.PREFIX + "§cYou can only use §a/setname§c while setting up your profile in the lobby.");
            return false;
        }

        // Premium players are Mojang-verified and keep their username — they never enter LOBBY_NAMING,
        // so this is defensive: if one somehow reaches here, refuse rather than let them rename.
        PlayerProfile selfProfile = store.get(uuid);
        if (selfProfile != null && selfProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            send(player, TextUtil.PREFIX + "§aVerified accounts keep their Mojang name — no §a/setname§a needed.");
            return false;
        }

        if (!DisplayNameManager.isValidName(name)) {
            send(player, TextUtil.PREFIX + "§cDisplay name must be 3-16 characters (letters, numbers, underscores only).");
            return false;
        }

        // Check banned words (fail fast)
        String lname = name.toLowerCase();
        String bannedRaw = AuthConfig.BANNED_WORDS.get();
        if (bannedRaw != null && !bannedRaw.isBlank()) {
            for (String word : bannedRaw.split(",")) {
                if (!word.isBlank() && lname.contains(word.trim().toLowerCase())) {
                    send(player, TextUtil.PREFIX + "§cThat name contains prohibited content. Choose a different name.");
                    return false;
                }
            }
        }

        String suggestion = suggestOfflineName(player.getGameProfile().getName());

        // Exact availability in local index
        UUID existing = store.getDisplayNameOwner(name);
        if (existing != null && !existing.equals(uuid)) {
            PlayerProfile ownerProfile = store.get(existing);
            if (ownerProfile != null && ownerProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
                send(player, TextUtil.PREFIX + "§cThat name is reserved by a verified player.");
            } else {
                send(player, TextUtil.PREFIX + "§cThat display name is already taken.");
            }
            send(player, TextUtil.PREFIX + "§7Try a unique offline name, e.g. §a" + suggestion);
            return false;
        }

        // Exact premium-name match (watchdog index)
        if (CoffeesAeroAuth.WATCHDOG != null && CoffeesAeroAuth.WATCHDOG.isPremiumDisplayName(name)) {
            send(player, TextUtil.PREFIX + "§cThat name is reserved by a verified player.");
            send(player, TextUtil.PREFIX + "§7Try a unique offline name, e.g. §a" + suggestion);
            return false;
        }

        // STRICT impersonation block: reject names that contain OR are a lookalike of any existing
        // display name or known premium username (Coffee → Coffee123 / xCoffee / Cof3ee / C0ffee).
        String conflict = findNameConflict(name, uuid);
        if (conflict != null) {
            send(player, TextUtil.PREFIX + "§cThat name is too similar to an existing player (§e" + conflict + "§c).");
            send(player, TextUtil.PREFIX + "§7Pick a clearly different name, e.g. §a" + suggestion);
            return false;
        }

        // Mojang API check — OFF-THREAD. This was a sync HTTP GET on the server thread (up to 6s
        // connect+read); every lobby name attempt froze the whole server for its duration, and
        // players retry names repeatedly. The flow tail hops back to the server thread with the
        // verdict and re-validates the local checks (the world may have moved while we waited).
        send(player, TextUtil.PREFIX + "§7Checking name availability...");
        final net.minecraft.server.MinecraftServer srv = player.getServer();
        java.util.concurrent.CompletableFuture
            .supplyAsync(() -> isMojangUsername(name), net.minecraft.Util.ioPool())
            .thenAcceptAsync(mojangTaken -> {
                ServerPlayer current = srv.getPlayerList().getPlayer(uuid);
                if (current == null) return;                 // logged out while checking
                if (mojangTaken) {
                    send(current, TextUtil.PREFIX + "§cThat name belongs to an existing Mojang account and cannot be used.");
                    return;
                }
                completeSetName(current, uuid, name);
            }, srv);
        return true;   // request accepted — verdict lands momentarily
    }

    /** Server thread. Mojang check passed — re-validate the racy local checks, reserve, queue. */
    private void completeSetName(ServerPlayer player, UUID uuid, String name) {
        UUID owner = store.getDisplayNameOwner(name);
        if (owner != null && !owner.equals(uuid)) {          // someone claimed it during the check
            send(player, TextUtil.PREFIX + "§cThat display name was just taken — pick another.");
            return;
        }
        if (owner == null) {
            store.registerDisplayName(name, uuid);           // reserve name tentatively in index
        }
        PlayerProfile profile = store.get(uuid);
        if (profile != null) {
            profile.nameApprovalPending = true;
            profile.pendingDisplayName  = name;
            store.save(profile);
        }
        authStates.put(uuid, AuthState.LOBBY_PENDING);
        if (CoffeesAeroAuth.APPROVAL_QUEUE != null) {
            CoffeesAeroAuth.APPROVAL_QUEUE.submit(player, name);
        }
    }

    /**
     * STRICT name-conflict scan for /setname. Returns the existing protected name that {@code candidate}
     * impersonates (substring / lookalike / edit-distance 1), or null if it is clearly unique. Checks
     * known premium names plus every other player's current display name.
     */
    private String findNameConflict(String candidate, UUID self) {
        if (CoffeesAeroAuth.WATCHDOG != null) {
            String premium = CoffeesAeroAuth.WATCHDOG.findImpersonatedPremium(candidate);
            if (premium != null) return premium;
        }
        // getAll() is a bulk scan only on the concrete ProfileStore, not the CredentialStore interface.
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll()) {
                if (p.getUUID().equals(self)) continue;
                String existing = p.displayName != null ? p.displayName : p.username;
                if (existing == null || existing.isBlank()) continue;
                if (com.coffeesaerosmp.auth.watchdog.WatchdogManager.impersonates(candidate, existing)) return existing;
            }
        }
        return null;
    }

    /** Suggests a unique-ish offline handle "aerosmp_&lt;username&gt;", sanitized and clamped to 20 chars. */
    private static String suggestOfflineName(String mcName) {
        String base = mcName == null ? "" : mcName.replaceAll("[^a-zA-Z0-9_]", "");
        if (base.isBlank()) base = "player";
        String s = "aerosmp_" + base;
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    /** /changename — one-time post-approval display name change. */
    public boolean handleChangeName(ServerPlayer player, String newName) {
        UUID uuid = player.getUUID();
        if (!isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§cMust be logged in to change your display name.");
            return false;
        }
        PlayerProfile profile = store.get(uuid);
        if (profile == null) return false;

        if (profile.nameChangesUsed >= 1) {
            send(player, TextUtil.PREFIX + "§cYou have already used your one-time name change.");
            return false;
        }
        if (!DisplayNameManager.isValidName(newName)) {
            send(player, TextUtil.PREFIX + "§cDisplay name must be 3-16 characters (letters, numbers, underscores only).");
            return false;
        }
        // Banned words
        String bannedRaw = AuthConfig.BANNED_WORDS.get();
        if (bannedRaw != null && !bannedRaw.isBlank()) {
            String lower = newName.toLowerCase();
            for (String word : bannedRaw.split(",")) {
                if (!word.isBlank() && lower.contains(word.trim().toLowerCase())) {
                    send(player, TextUtil.PREFIX + "§cThat name contains prohibited content.");
                    return false;
                }
            }
        }
        // Global uniqueness check
        UUID existing = store.getDisplayNameOwner(newName);
        if (existing != null && !existing.equals(uuid)) {
            send(player, TextUtil.PREFIX + "§cThat display name is already taken.");
            return false;
        }
        // Online player check — name cannot match any currently connected player's display name
        for (net.minecraft.server.level.ServerPlayer online : player.getServer().getPlayerList().getPlayers()) {
            if (online.getUUID().equals(uuid)) continue;
            PlayerProfile op = store.get(online.getUUID());
            String onlineName = op != null ? op.displayName : online.getGameProfile().getName();
            if (newName.equalsIgnoreCase(onlineName)) {
                send(player, TextUtil.PREFIX + "§cThat name is currently in use by an online player.");
                return false;
            }
        }
        // Premium display name guard
        if (CoffeesAeroAuth.WATCHDOG != null && CoffeesAeroAuth.WATCHDOG.isPremiumDisplayName(newName)) {
            send(player, TextUtil.PREFIX + "§cThat name is reserved by a verified player.");
            return false;
        }
        // Mojang API check — OFF-THREAD (same server-thread-freeze fix as /setname above).
        send(player, TextUtil.PREFIX + "§7Checking name availability...");
        final net.minecraft.server.MinecraftServer srv = player.getServer();
        java.util.concurrent.CompletableFuture
            .supplyAsync(() -> isMojangUsername(newName), net.minecraft.Util.ioPool())
            .thenAcceptAsync(mojangTaken -> {
                ServerPlayer current = srv.getPlayerList().getPlayer(uuid);
                if (current == null) return;                 // logged out while checking
                if (mojangTaken) {
                    send(current, TextUtil.PREFIX + "§cThat name belongs to an existing Mojang account.");
                    return;
                }
                completeChangeName(current, uuid, newName);
            }, srv);
        return true;
    }

    /** Server thread. Mojang check passed — re-validate, consume the one-time change, queue. */
    private void completeChangeName(ServerPlayer player, UUID uuid, String newName) {
        PlayerProfile profile = store.get(uuid);
        if (profile == null) return;
        UUID owner = store.getDisplayNameOwner(newName);
        if (owner != null && !owner.equals(uuid)) {          // claimed during the async check
            send(player, TextUtil.PREFIX + "§cThat display name was just taken — pick another.");
            return;
        }
        // One-time change is consumed now (the command is disabled hereafter); the new name goes
        // through the admin approval queue. The current display name stays active until approval; the
        // new name is reserved tentatively so nobody else can claim it while it is pending.
        String oldName = profile.displayName;
        store.registerDisplayName(newName, uuid);
        profile.nameApprovalPending = true;
        profile.pendingDisplayName  = newName;
        profile.nameChangesUsed++;
        store.save(profile);
        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.recordNameChange("REQUEST uuid=" + uuid + " old=" + oldName + " new=" + newName);
        }
        if (CoffeesAeroAuth.APPROVAL_QUEUE != null) {
            CoffeesAeroAuth.APPROVAL_QUEUE.submitRename(player, newName);
        }
        send(player, TextUtil.PREFIX + "§aName change requested: §f" + oldName + " §7→ §a" + newName);
        send(player, TextUtil.PREFIX + "§7Pending admin approval — this was your one-time change.");
    }

    public boolean handleChangePassword(ServerPlayer player, String oldPw, String newPw) {
        UUID uuid = player.getUUID();
        if (!isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§cMust be logged in to change password.");
            return false;
        }
        PlayerProfile profile = store.get(uuid);
        if (profile == null) return false;
        if (profile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            send(player, TextUtil.PREFIX + "§cVerified accounts authenticate through Mojang — no password to change.");
            return false;
        }
        if (!PasswordUtil.verify(oldPw, profile.passwordSalt, profile.passwordHash)) {
            send(player, TextUtil.PREFIX + "§cCurrent password is incorrect.");
            return false;
        }
        if (newPw.length() < 8) {
            send(player, TextUtil.PREFIX + "§cNew password must be at least 8 characters.");
            return false;
        }
        String salt = PasswordUtil.generateSalt();
        profile.passwordSalt = salt;
        profile.passwordHash = PasswordUtil.hash(newPw, salt);
        store.save(profile);
        sessionTokens.invalidate(uuid);
        send(player, TextUtil.PREFIX + "§aPassword changed. Session invalidated — log in on next join.");
        return true;
    }

    // ── Approval callbacks (called by NameApprovalQueue) ─────────────────────

    /** Called when a player's display name is approved.
     *  Player stays in the lobby room — they leave manually via /spawn. */
    public void completeApproval(ServerPlayer player, PlayerProfile profile) {
        UUID uuid = player.getUUID();
        authStates.put(uuid, AuthState.AUTHENTICATED);
        frozenPos.remove(uuid);  // lift freeze so they can explore the room
        profile.sessionStartEpoch = System.currentTimeMillis();
        profile.firstJoinComplete = true;
        store.save(profile);
        sessionTokens.createToken(uuid, NetUtil.getPlayerIP(player));
        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.recordSuccessfulLogin(uuid, NetUtil.getPlayerIP(player), profile.displayName, false);
        }
        // Welcome — player stays in their hangar until /spawn
        String serverName = AuthConfig.SERVER_DISPLAY_NAME.get();
        player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l" + serverName)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§aWelcome aboard, §f" + profile.displayName + "§a!")));
        send(player, TextUtil.PREFIX + "§a✦ Display name approved: §f" + profile.displayName);
        send(player, TextUtil.PREFIX + "§eLook around your hangar, then type §a/spawn§e to enter the server.");
        send(player, TextUtil.PREFIX + "§7(Resource pack required on first join — accept when prompted.)");
        WelcomeMessages.skinTip(player, profile);
        com.coffeesaerosmp.auth.events.PlayerAuthEvents.onOfflinePlayerAuthenticated(player);
    }

    /** /spawn — from the lobby: exits the room into the main world (no cooldown, players must
     *  never get stuck in the lobby). In the main world: teleport to the overworld world spawn,
     *  once per hour per player (ops exempt). */
    public boolean handleSpawn(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§cYour name must be approved before you can enter the server.");
            return false;
        }
        if (player.level().dimension() != com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
            return handleWorldSpawnTeleport(player);
        }
        // Destination: a returning player resumes at their last main-world position; a first-timer
        // (no saved return position, or its dimension no longer loads) goes to the world spawn point.
        PlayerProfile profile = store.get(uuid);
        boolean resumed = false;
        if (profile != null && profile.returnDim != null) {
            net.minecraft.server.level.ServerLevel target = resolveLevel(player, profile.returnDim);
            if (target != null) {
                player.teleportTo(target, profile.returnX, profile.returnY, profile.returnZ,
                    Set.of(), player.getYRot(), player.getXRot());
                resumed = true;
            }
        }
        if (!resumed && CoffeesAeroAuth.ROOM_MANAGER != null) {
            CoffeesAeroAuth.ROOM_MANAGER.teleportToSpawn(player);
        }
        // Restore the real inventory AFTER the teleport: anything the restore can't fit is dropped at
        // the player's feet, and feet-drops in the LOBBY are destroyed by the room purge/rebuild —
        // that's how GeneralBronze lost his armor (2026-07-10: 41-stack overflow restore ran pre-
        // teleport, the 5 stacks that didn't fit dropped in his hangar and were wiped). Restoring
        // here lands any spill in the world beside the player. Still before the starter bonus so the
        // bonus stacks on top. No-op for brand-new players (empty stash).
        if (CoffeesAeroAuth.LOBBY_STASH != null) {
            CoffeesAeroAuth.LOBBY_STASH.restore(player);
        }
        // Restore the real overworld food/health that were stashed on lobby entry (the lobby's full
        // hunger + any regen are discarded — overworld vitals are exactly as they were).
        float[] vit = lobbyVitals.remove(uuid);
        if (vit != null) {
            player.getFoodData().setFoodLevel((int) vit[0]);
            player.getFoodData().setSaturation(vit[1]);
            player.setHealth(Math.min(vit[2], player.getMaxHealth()));
        }
        // First time entering the world → one-time starter currency + mark first-join complete.
        boolean firstWorldEntry = profile != null && !profile.startupBonusGiven;
        if (profile != null) {
            if (!profile.startupBonusGiven) {
                grantStartupBonus(player);
                profile.startupBonusGiven = true;
            }
            // Season 1 loyalty reward, once per veteran. Reads an in-memory set loaded at boot —
            // no DB round trip on the join path (the login path already blocks the main thread).
            com.coffeesaerosmp.auth.season.VeteranReward.grantIfOwed(player);
            profile.firstJoinComplete = true;
            store.save(profile);
        }
        // Banner only on the FIRST world entry — every later lobby round-trip used to replay it
        // (part of the every-join welcome spam, reworked 2026-07-18 into WelcomeMessages).
        if (firstWorldEntry) WelcomeMessages.firstSpawnBanner(player);
        return true;
    }

    /** In-world /spawn — overworld world-spawn teleport with a configurable per-player cooldown
     *  ({@code spawnTeleportCooldownMinutes}, default 180 min; in-memory, resets on server restart).
     *  Ops bypass. CombatGuard's blocklist already cancels /spawn while combat-tagged, so no
     *  escape-from-PvP check is needed here. */
    private boolean handleWorldSpawnTeleport(ServerPlayer player) {
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        long cooldownMs = com.coffeesaerosmp.auth.config.AuthConfig.SPAWN_TP_COOLDOWN_MINUTES.get() * 60_000L;
        if (cooldownMs > 0 && !player.hasPermissions(2)) {
            Long last = lastSpawnTp.get(uuid);
            if (last != null && now - last < cooldownMs) {
                String rem = TextUtil.formatRemaining(cooldownMs - (now - last));
                send(player, TextUtil.PREFIX + "§7You can §f/spawn§7 again in §e" + rem + "§7.");
                Sounds.error(player);
                return false;
            }
        }
        if (CoffeesAeroAuth.ROOM_MANAGER == null) {
            send(player, TextUtil.PREFIX + "§cSpawn teleport is unavailable right now.");
            return false;
        }
        lastSpawnTp.put(uuid, now);

        // Capture the departure point BEFORE the teleport — afterwards the player is already at
        // spawn and there is nothing left to mark the place they vanished from.
        net.minecraft.server.level.ServerLevel fromLevel = player.serverLevel();
        double fx = player.getX(), fy = player.getY(), fz = player.getZ();

        CoffeesAeroAuth.ROOM_MANAGER.teleportToSpawn(player);

        // Bystanders get the enderman VWOOP + portal particles at BOTH ends: where they went from,
        // and where they landed. Departure is skipped if they were in the lobby — nobody is there
        // to see it, and the lobby is not a place effects belong.
        if (fromLevel.dimension() != com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
            Sounds.teleportAmbient(fromLevel, fx, fy, fz);
        }
        Sounds.teleportAmbient(player.serverLevel(), player.getX(), player.getY(), player.getZ());

        // The cooldown is read from config, so the message must be too — this said "once per hour"
        // while the default was 180 minutes, and would have kept saying it at any other setting.
        Sounds.teleport(player);
        send(player, TextUtil.PREFIX + "§a✈ Teleported to world spawn."
            + (cooldownMs > 0 && !player.hasPermissions(2)
               ? " §7(Usable again in " + TextUtil.formatRemaining(cooldownMs) + ".)" : ""));
        return true;
    }

    /** /home — teleport to the player's bed / respawn point, using vanilla's safe-spot finder so they
     *  land beside the bed rather than inside it. Cooldown {@code homeTeleportCooldownMinutes} (ops
     *  exempt); CombatGuard already blocks /home while combat-tagged. No-op with a message if no bed. */
    public boolean handleHome(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§cLog in before using /home.");
            return false;
        }
        net.minecraft.core.BlockPos bed = player.getRespawnPosition();
        if (bed == null) {
            send(player, TextUtil.PREFIX + "§cNo home set — sleep in a bed (or set a respawn anchor) first.");
            return false;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = AuthConfig.HOME_TP_COOLDOWN_MINUTES.get() * 60_000L;
        if (cooldownMs > 0 && !player.hasPermissions(2)) {
            Long last = lastHomeTp.get(uuid);
            if (last != null && now - last < cooldownMs) {
                String rem = TextUtil.formatRemaining(cooldownMs - (now - last));
                send(player, TextUtil.PREFIX + "§7You can §f/home§7 again in §e" + rem + "§7.");
                Sounds.error(player);
                return false;
            }
        }
        net.minecraft.server.level.ServerLevel level =
            player.getServer() != null ? player.getServer().getLevel(player.getRespawnDimension()) : null;
        if (level == null) {
            send(player, TextUtil.PREFIX + "§cYour home dimension isn't available.");
            return false;
        }
        boolean fromLobby = player.level().dimension()
            == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
        player.teleportTo(level, bed.getX() + 0.5, bed.getY() + 1.0, bed.getZ() + 0.5,
            Set.of(), player.getRespawnAngle(), 0.0f);
        // Leaving the lobby via /home → same cleanup as /spawn: restore the real inventory (which
        // removes the lobby "spawn" paper so it never reaches the overworld) and restore stashed vitals.
        if (fromLobby) {
            if (CoffeesAeroAuth.LOBBY_STASH != null) CoffeesAeroAuth.LOBBY_STASH.restore(player);
            float[] vit = lobbyVitals.remove(uuid);
            if (vit != null) {
                player.getFoodData().setFoodLevel((int) vit[0]);
                player.getFoodData().setSaturation(vit[1]);
                player.setHealth(Math.min(vit[2], player.getMaxHealth()));
            }
        }
        lastHomeTp.put(uuid, now);
        send(player, TextUtil.PREFIX + "§a✦ Teleported home.");
        return true;
    }

    /** Resets state back to LOBBY_NAMING after a name rejection. */
    public void resetToNaming(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (isLobbyState(authStates.getOrDefault(uuid, AuthState.PENDING))) {
            authStates.put(uuid, AuthState.LOBBY_NAMING);
        }
    }

    // ── Auth complete (premium / session resume / password login) ─────────────

    private void onAuthenticated(ServerPlayer player, PlayerProfile profile, boolean isNewAccount) {
        frozenPos.remove(player.getUUID());
        profile.sessionStartEpoch = System.currentTimeMillis();
        boolean firstJoin = !profile.firstJoinComplete;
        if (firstJoin) profile.firstJoinComplete = true;
        store.save(profile);

        // ALL cosmetic greeting surfaces (title, welcome chat, skin tip) live in WelcomeMessages,
        // throttled to once per welcomeIntervalHours — the every-join replay was the 07-18 spam bug.
        WelcomeMessages.onAuthComplete(player, profile, firstJoin);
        // Daily nudge AFTER the welcome sequence: onAuthComplete has three early-return branches
        // and its own throttle, and this is actionable state ("you have something to collect"),
        // not a greeting — it must not inherit that throttle. Silent when nothing is claimable.
        if (CoffeesAeroAuth.DAILY_REWARDS != null) CoffeesAeroAuth.DAILY_REWARDS.remindOnJoin(player);

        // Votes banked while they were offline — most votes are cast when the player is NOT online,
        // so this is the normal delivery path rather than an edge case.
        if (CoffeesAeroAuth.VOTE_REWARDS != null) {
            CoffeesAeroAuth.VOTE_REWARDS.onPlayerJoin(player);
            CoffeesAeroAuth.VOTE_REWARDS.remindOnJoin(player);
        }

        // Someone joining mid-countdown would otherwise be the only person on the server who
        // cannot see that a restart is imminent.
        com.coffeesaerosmp.auth.util.RestartWarning.onPlayerJoin(player);

        // Testing-phase notice. After the welcome sequence and the daily nudge, because it is
        // context for the session rather than a greeting, and it must not be buried above them.
        if (com.coffeesaerosmp.auth.util.TestingMode.isActive()) {
            send(player, com.coffeesaerosmp.auth.util.TestingMode.banner());
            Sounds.alert(player);
        }

        if (profile.getAccountType() == PlayerProfile.AccountType.OFFLINE) {
            com.coffeesaerosmp.auth.events.PlayerAuthEvents.onOfflinePlayerAuthenticated(player);
        }

        // Logged in inside the lobby (expired-session return, or a persisted lobby position) → make
        // sure they aren't standing over void before they type /spawn, then guide them out.
        if (player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
            if (CoffeesAeroAuth.ROOM_MANAGER != null) CoffeesAeroAuth.ROOM_MANAGER.ensureSafeFooting(player);
            send(player, TextUtil.PREFIX + "§eType §a/spawn§e to enter the server.");
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isAuthenticated(UUID uuid) {
        return authStates.get(uuid) == AuthState.AUTHENTICATED;
    }

    public AuthState getState(UUID uuid) {
        return authStates.getOrDefault(uuid, AuthState.PENDING);
    }

    public void invalidateSessionToken(UUID uuid) {
        sessionTokens.invalidate(uuid);
    }

    public double[] getFrozenPositionIfPresent(UUID uuid) {
        return frozenPos.get(uuid);
    }

    public int getUnauthenticatedCount() {
        int n = 0;
        for (AuthState s : authStates.values()) if (s != AuthState.AUTHENTICATED) n++;
        return n;
    }

    public int getAuthenticatedCount() {
        int n = 0;
        for (AuthState s : authStates.values()) if (s == AuthState.AUTHENTICATED) n++;
        return n;
    }

    public List<UUID> getAuthenticatedUUIDs() {
        List<UUID> uuids = new java.util.ArrayList<>();
        authStates.forEach((uuid, state) -> { if (state == AuthState.AUTHENTICATED) uuids.add(uuid); });
        return uuids;
    }

    public CredentialStore getStore() { return store; }

    public DisplayNameManager getDisplayNames() { return displayNames; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static boolean isLobbyState(AuthState state) {
        return state == AuthState.LOBBY_REGISTER
            || state == AuthState.LOBBY_NAMING
            || state == AuthState.LOBBY_PENDING;
    }

    private static boolean isMojangUsername(String name) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return true; // #1 fail CLOSED — can't verify, assume the name is taken (reject it)
        }
    }

    /** Resolves a saved dimension id (e.g. "minecraft:overworld") to a loaded level, or null. */
    private static net.minecraft.server.level.ServerLevel resolveLevel(ServerPlayer player, String dimId) {
        try {
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.parse(dimId);
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, loc);
            return player.getServer() != null ? player.getServer().getLevel(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void send(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }
}
