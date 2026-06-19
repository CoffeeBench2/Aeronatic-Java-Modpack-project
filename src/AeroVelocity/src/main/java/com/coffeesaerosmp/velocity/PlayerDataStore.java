package com.coffeesaerosmp.velocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataStore {

    public enum PlayerType { PREMIUM, CRACKED }

    private final Map<UUID, PlayerType> typeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lobbyAuthMap = new ConcurrentHashMap<>();

    public void setType(UUID uuid, PlayerType type) {
        typeMap.put(uuid, type);
    }

    public PlayerType getType(UUID uuid) {
        return typeMap.getOrDefault(uuid, PlayerType.CRACKED);
    }

    public boolean isPremium(UUID uuid) {
        return typeMap.getOrDefault(uuid, PlayerType.CRACKED) == PlayerType.PREMIUM;
    }

    public void setLobbyAuthenticated(UUID uuid) {
        lobbyAuthMap.put(uuid, true);
    }

    public boolean isLobbyAuthenticated(UUID uuid) {
        return lobbyAuthMap.getOrDefault(uuid, false);
    }

    public void remove(UUID uuid) {
        typeMap.remove(uuid);
        lobbyAuthMap.remove(uuid);
    }
}
