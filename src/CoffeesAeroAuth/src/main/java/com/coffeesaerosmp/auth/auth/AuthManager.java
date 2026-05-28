package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import com.coffeesaerosmp.auth.profile.DisplayNameManager;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import com.coffeesaerosmp.auth.util.NetUtil;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final ProfileStore       store;
    private final DisplayNameManager displayNames;

    private final Map<UUID, AuthState> authStates     = new ConcurrentHashMap<>();
    private final Map<UUID, double[]>  frozenPos      = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      joinTimes      = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>   failedAttempts = new ConcurrentHashMap<>();
    private final SessionTokenManager  sessionTokens  = new SessionTokenManager();

    // Fixed UUID identifies this server's resource pack in the client's pack stack
    private static final UUID PACK_UUID = UUID.fromString("c0ffee00-aero-4001-8000-cafebabe0001");

    public AuthManager(ProfileStore store, DisplayNameManager displayNames) {
        this.store        = store;
        this.displayNames = displayNames;
    }

    // ── Join / Leave ──────────────────────────────────────────────────────────

    public void onPlayerJoin(ServerPlayer player) {
        UUID   uuid    = player.getUUID();
        String mcName  = player.getGameProfile().getName();
        boolean premium = UUIDUtil.isPremiumUUID(uuid);

        // Op bypass (disabled by default — only enable during initial server setup)
        if (AuthConfig.BYPASS_AUTH_FOR_OPS.get() && player.hasPermissions(4)) {
            authStates.put(uuid, AuthState.AUTHENTICATED);
            return;
        }

        // Anti-spoof: block offline player if their MC username is already a verified
        // player's display name and kickOnNameConflict is enabled
        if (!premium && AuthConfig.KICK_ON_NAME_CONFLICT.get()) {
            UUID nameOwner = store.getDisplayNameOwner(mcName);
            if (nameOwner != null && !nameOwner.equals(uuid)) {
                PlayerProfile ownerProfile = store.get(nameOwner);
                if (ownerProfile != null && ownerProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
                    player.connection.disconnect(Component.literal(
                        "§cYour username §e" + mcName + "§c is reserved by a verified player on this server.\n" +
                        "§7Please change your Minecraft username and try again."
                    ));
                    return;
                }
            }
        }

        PlayerProfile.AccountType type   = premium ? PlayerProfile.AccountType.PREMIUM : PlayerProfile.AccountType.OFFLINE;
        ProfileStore.GetOrCreateResult r = store.getOrCreate(uuid, mcName, type);
        PlayerProfile profile            = r.profile();

        if (r.isNew()) {
            PlayerProfile premiumConflict = displayNames.claimInitialName(profile);
            if (premiumConflict != null && !AuthConfig.KICK_ON_NAME_CONFLICT.get()) {
                // Soft mode: name was auto-suffixed — warn the player
                send(player, TextUtil.PREFIX + "§eYour username conflicted with a verified player. Display name set to: §a" + profile.displayName);
            }
        }

        // Freeze the player at their join position until auth completes
        frozenPos.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
        joinTimes.put(uuid, System.currentTimeMillis());
        failedAttempts.put(uuid, 0);

        if (premium) {
            authStates.put(uuid, AuthState.AUTHENTICATED);
            onAuthenticated(player, profile, false);
        } else if (profile.passwordHash == null) {
            authStates.put(uuid, AuthState.REGISTERING);
            send(player, TextUtil.PREFIX + "§eWelcome! Register your account: §a/register <password> <confirmPassword>");
            send(player, TextUtil.PREFIX + "§7Password must be 8+ characters. Your account is permanently tied to your player ID.");
        } else {
            // Check for a valid session token before requiring password
            String ip = NetUtil.getPlayerIP(player);
            SessionTokenManager.TokenStatus tokenStatus = sessionTokens.check(uuid, ip);

            if (tokenStatus == SessionTokenManager.TokenStatus.VALID) {
                authStates.put(uuid, AuthState.AUTHENTICATED);
                onAuthenticated(player, profile, false);
                send(player, TextUtil.PREFIX + "§aSession resumed — login skipped.");
            } else {
                if (tokenStatus == SessionTokenManager.TokenStatus.IP_CHANGED) {
                    sessionTokens.invalidate(uuid);
                    send(player, TextUtil.PREFIX + "§eIP address changed — session invalidated. Please log in again.");
                }
                authStates.put(uuid, AuthState.PENDING);
                send(player, TextUtil.PREFIX + "§ePlease log in: §a/login <password>");
            }
        }

        // Push resource pack if configured
        String packUrl  = AuthConfig.RESOURCE_PACK_URL.get();
        String packHash = AuthConfig.RESOURCE_PACK_HASH.get();
        if (!packUrl.isBlank()) {
            try {
                player.connection.send(new ClientboundResourcePackPushPacket(
                    PACK_UUID, packUrl, packHash, true,
                    Optional.of(Component.literal("§6Coffees Aero SMP §7Resource Pack"))
                ));
            } catch (Exception e) {
                // Swallow — pack URL may be empty/invalid during local dev
            }
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerProfile profile = store.get(uuid);
        if (profile != null && profile.sessionStartEpoch > 0) {
            long secs = (System.currentTimeMillis() - profile.sessionStartEpoch) / 1000;
            profile.totalPlaytimeSeconds += secs;
            profile.sessionStartEpoch = 0;
            store.save(profile);
        }
        authStates.remove(uuid);
        frozenPos.remove(uuid);
        joinTimes.remove(uuid);
        failedAttempts.remove(uuid);
    }

    // ── Per-tick check (called from PlayerRestrictEvents) ────────────────────

    public void onTick(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (isAuthenticated(uuid)) return;

        // Auth timeout
        int timeout = AuthConfig.AUTH_TIMEOUT_SECONDS.get();
        if (timeout > 0) {
            Long joined = joinTimes.get(uuid);
            if (joined != null && (System.currentTimeMillis() - joined) > timeout * 1000L) {
                player.connection.disconnect(Component.literal(
                    "§cAuthentication timed out. Reconnect and log in within " + timeout + " seconds."
                ));
                return;
            }
        }

        // Freeze position — teleport back to join point every tick
        double[] pos = frozenPos.get(uuid);
        if (pos != null) {
            player.teleportTo(pos[0], pos[1], pos[2]);
            player.setDeltaMovement(0, 0, 0);
        }

        // Reminder every 5 seconds (100 ticks)
        if (player.tickCount % 100 == 0) {
            AuthState state = authStates.getOrDefault(uuid, AuthState.PENDING);
            if (state == AuthState.REGISTERING) {
                send(player, TextUtil.PREFIX + "§eUse §a/register <password> <confirmPassword>§e to create your account.");
            } else {
                send(player, TextUtil.PREFIX + "§eUse §a/login <password>§e to continue.");
            }
        }
    }

    // ── Auth Commands ─────────────────────────────────────────────────────────

    public boolean handleLogin(ServerPlayer player, String password) {
        UUID uuid = player.getUUID();
        if (isAuthenticated(uuid)) {
            send(player, TextUtil.PREFIX + "§aAlready logged in.");
            return true;
        }
        if (authStates.get(uuid) == AuthState.REGISTERING) {
            send(player, TextUtil.PREFIX + "§cNo account yet. Use §a/register <password> <password>§c.");
            return false;
        }
        PlayerProfile profile = store.get(uuid);
        if (profile == null || profile.passwordHash == null) {
            send(player, TextUtil.PREFIX + "§cNo account found. Use §a/register§c to create one.");
            return false;
        }
        if (!PasswordUtil.verify(password, profile.passwordSalt, profile.passwordHash)) {
            int attempts = failedAttempts.merge(uuid, 1, Integer::sum);
            int max      = AuthConfig.MAX_FAILED_ATTEMPTS.get();
            if (CoffeesAeroAuth.WATCHDOG != null) {
                CoffeesAeroAuth.WATCHDOG.recordFailedLogin(uuid, NetUtil.getPlayerIP(player), player.getGameProfile().getName());
                CoffeesAeroAuth.WATCHDOG.recordFailedLoginFromUnknownIp(uuid, NetUtil.getPlayerIP(player), player.getGameProfile().getName());
            }
            if (max > 0 && attempts >= max) {
                player.connection.disconnect(Component.literal("§cToo many failed login attempts. Try again later."));
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
        if (authStates.get(uuid) != AuthState.REGISTERING) {
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

        authStates.put(uuid, AuthState.AUTHENTICATED);
        onAuthenticated(player, profile, true);
        sessionTokens.createToken(uuid, NetUtil.getPlayerIP(player));
        return true;
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
        send(player, TextUtil.PREFIX + "§aPassword changed successfully. Session invalidated — you will need to log in on next join.");
        return true;
    }

    // ── Auth Complete ─────────────────────────────────────────────────────────

    private void onAuthenticated(ServerPlayer player, PlayerProfile profile, boolean isNewAccount) {
        frozenPos.remove(player.getUUID());
        profile.sessionStartEpoch = System.currentTimeMillis();
        store.save(profile);

        String serverName   = AuthConfig.SERVER_DISPLAY_NAME.get();
        String displayName  = profile.displayName != null ? profile.displayName : profile.username;

        if (profile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            send(player, TextUtil.PREFIX + "§a✦ Verified — welcome, " + displayName + "§a!");
        } else if (isNewAccount) {
            send(player, TextUtil.PREFIX + "§aAccount created! Welcome to §6" + serverName + "§a!");
            send(player, TextUtil.PREFIX + "§7Use §a/setdisplayname <name>§7 to choose your display name.");
            send(player, TextUtil.PREFIX + "§7Use §a/setbio <text>§7 to write a short bio.");
        } else {
            send(player, TextUtil.PREFIX + "§aLogged in. Welcome back, §f" + displayName + "§a!");
        }

        if (!profile.firstJoinComplete) {
            profile.firstJoinComplete = true;
            store.save(profile);
            sendFirstJoinSequence(player, serverName);
        } else {
            sendWelcomeTitle(player, displayName, serverName);
        }

        // Deferred Discord + Obsidian hook for offline players who just /login'd
        // (premium players fire this in PlayerAuthEvents.onPlayerJoin immediately)
        if (profile.getAccountType() == PlayerProfile.AccountType.OFFLINE) {
            com.coffeesaerosmp.auth.events.PlayerAuthEvents.onOfflinePlayerAuthenticated(player);
        }
    }

    private void sendFirstJoinSequence(ServerPlayer player, String serverName) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l" + serverName)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§eYour adventure begins now ✈")));

        send(player, "§6§l╔═══════════════════════════╗");
        send(player, "§6§l║  §e✈ Welcome aboard, pilot!  §6§l║");
        send(player, "§6§l║  §7The #1 Create: Aeronautics  §6§l║");
        send(player, "§6§l║  §7experience in Asia.         §6§l║");
        send(player, "§6§l╚═══════════════════════════╝");
        // Intro cutscene hook — resource pack animation plays here (TODO: wire up when RP is ready)
    }

    private void sendWelcomeTitle(ServerPlayer player, String displayName, String serverName) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l" + serverName)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§eWelcome back, §f" + displayName + "§e!")));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isAuthenticated(UUID uuid) {
        return authStates.get(uuid) == AuthState.AUTHENTICATED;
    }

    public AuthState getState(UUID uuid) {
        return authStates.getOrDefault(uuid, AuthState.PENDING);
    }

    /** Explicitly invalidates a player's session token — called by /logout and admin password reset. */
    public void invalidateSessionToken(UUID uuid) {
        sessionTokens.invalidate(uuid);
    }

    /** Returns frozen join position for authenticated players (used by watchdog movement check). */
    public double[] getFrozenPositionIfPresent(UUID uuid) {
        return frozenPos.get(uuid);
    }

    /** Count of players who have joined but not yet authenticated (used by health monitor). */
    public int getUnauthenticatedCount() {
        int n = 0;
        for (AuthState s : authStates.values()) if (s != AuthState.AUTHENTICATED) n++;
        return n;
    }

    /** Count of fully authenticated players (used by Obsidian peak-player tracking). */
    public int getAuthenticatedCount() {
        int n = 0;
        for (AuthState s : authStates.values()) if (s == AuthState.AUTHENTICATED) n++;
        return n;
    }

    /** UUIDs of all currently authenticated players (used by Obsidian connection graph). */
    public List<UUID> getAuthenticatedUUIDs() {
        List<UUID> uuids = new java.util.ArrayList<>();
        authStates.forEach((uuid, state) -> { if (state == AuthState.AUTHENTICATED) uuids.add(uuid); });
        return uuids;
    }

    public ProfileStore getStore() { return store; }

    public DisplayNameManager getDisplayNames() { return displayNames; }

    private void send(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }
}
