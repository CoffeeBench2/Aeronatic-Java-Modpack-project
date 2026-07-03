package com.coffeesaerosmp.lobby.auth;

import com.coffeesaerosmp.lobby.AeroLobbyPlugin;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LobbyAuthManager {

    private final AeroLobbyPlugin plugin;
    private Connection db;

    // Players who have completed auth this session (includes premium who auto-passed)
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    // Players currently in a chat-input flow (awaiting password / display name)
    private final Map<UUID, ChatInputState> pendingInput = new ConcurrentHashMap<>();

    public enum ChatInputState {
        AWAITING_DISPLAY_NAME,
        AWAITING_NEW_PASSWORD,
        AWAITING_LOGIN_PASSWORD
    }

    // Temp storage across multi-step registration
    private final Map<UUID, String> pendingDisplayName = new ConcurrentHashMap<>();

    public LobbyAuthManager(AeroLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" +
                plugin.getDataFolder().getAbsolutePath() + "/auth.db");
            db.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid        TEXT PRIMARY KEY,
                    username    TEXT NOT NULL,
                    display_name TEXT UNIQUE NOT NULL,
                    password_hash TEXT,
                    player_type TEXT NOT NULL DEFAULT 'cracked',
                    registered_at INTEGER NOT NULL
                )
            """);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialise auth database: " + e.getMessage());
        }
    }

    public void close() {
        try { if (db != null && !db.isClosed()) db.close(); } catch (SQLException ignored) {}
    }

    // --- Premium registration (no password) ---

    public void registerPremium(Player player) {
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR IGNORE INTO players(uuid,username,display_name,player_type,registered_at) VALUES(?,?,?,?,?)");
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, name);
            ps.setString(4, "premium");
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            // Update username in case they changed their Mojang name
            db.prepareStatement("UPDATE players SET username=? WHERE uuid=?")
                .setString(1, name);
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error registering premium " + name + ": " + e.getMessage());
        }
        authenticated.add(player.getUniqueId());
    }

    // --- Cracked: check if registered ---

    public boolean isCrackedRegistered(UUID uuid) {
        try {
            PreparedStatement ps = db.prepareStatement(
                "SELECT 1 FROM players WHERE uuid=? AND player_type='cracked'");
            ps.setString(1, uuid.toString());
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    // --- Cracked: display name availability ---

    public boolean isDisplayNameTaken(String displayName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                "SELECT 1 FROM players WHERE LOWER(display_name)=LOWER(?)");
            ps.setString(1, displayName);
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    // --- Cracked: register new account ---

    public boolean registerCracked(Player player, String displayName, String password) {
        if (isDisplayNameTaken(displayName)) return false;
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        try {
            PreparedStatement ps = db.prepareStatement(
                "INSERT INTO players(uuid,username,display_name,password_hash,player_type,registered_at) VALUES(?,?,?,?,?,?)");
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setString(3, displayName);
            ps.setString(4, hash);
            ps.setString(5, "cracked");
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            authenticated.add(player.getUniqueId());
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error registering cracked " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    // --- Cracked: login ---

    public boolean loginCracked(Player player, String password) {
        try {
            PreparedStatement ps = db.prepareStatement(
                "SELECT password_hash FROM players WHERE uuid=?");
            ps.setString(1, player.getUniqueId().toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return false;
            String hash = rs.getString("password_hash");
            if (BCrypt.checkpw(password, hash)) {
                authenticated.add(player.getUniqueId());
                return true;
            }
            return false;
        } catch (SQLException e) { return false; }
    }

    // --- State queries ---

    public boolean isAuthenticated(UUID uuid) { return authenticated.contains(uuid); }

    public void setPendingInput(UUID uuid, ChatInputState state) { pendingInput.put(uuid, state); }
    public void clearPendingInput(UUID uuid) { pendingInput.remove(uuid); }
    public ChatInputState getPendingInput(UUID uuid) { return pendingInput.get(uuid); }
    public boolean hasPendingInput(UUID uuid) { return pendingInput.containsKey(uuid); }

    public void setPendingDisplayName(UUID uuid, String name) { pendingDisplayName.put(uuid, name); }
    public String getPendingDisplayName(UUID uuid) { return pendingDisplayName.get(uuid); }
    public void clearPendingDisplayName(UUID uuid) { pendingDisplayName.remove(uuid); }

    public void remove(UUID uuid) {
        authenticated.remove(uuid);
        pendingInput.remove(uuid);
        pendingDisplayName.remove(uuid);
    }
}
