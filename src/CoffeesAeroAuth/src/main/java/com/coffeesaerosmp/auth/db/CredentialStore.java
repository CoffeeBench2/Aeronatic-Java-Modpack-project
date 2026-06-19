package com.coffeesaerosmp.auth.db;

import java.util.UUID;

/**
 * The narrow profile/credential/display-name surface the auth flow depends on.
 *
 * <p>Interface Segregation + Dependency Inversion: {@code AuthManager} (the flow controller) is
 * written against this interface, not the concrete {@link ProfileStore}, so it neither knows nor
 * cares whether a credential lives in MySQL or a flat file. The single implementation today is
 * {@link ProfileStore}, which is itself a failover store — MySQL primary, flat-file fallback, chosen
 * per-operation by {@link DatabaseManager#isAvailable()}. That keeps the swap logic in one place and
 * leaves room to split it into discrete {@code MySqlCredentialStore} / {@code FlatFileCredentialStore}
 * implementations later without touching any caller.</p>
 *
 * <p>Infrastructure consumers that need the wider surface (bulk scans via {@code getAll()}, the
 * on-disk {@code profilesDir()}) continue to depend on the concrete {@link ProfileStore}; this
 * interface deliberately excludes them so the flow controller stays minimal.</p>
 */
public interface CredentialStore {

    /** Result of {@link #getOrCreate}: the profile plus whether it was freshly created this call. */
    record GetOrCreateResult(PlayerProfile profile, boolean isNew) {}

    GetOrCreateResult getOrCreate(UUID uuid, String username, PlayerProfile.AccountType type);

    PlayerProfile get(UUID uuid);

    void save(PlayerProfile profile);

    boolean isDisplayNameTaken(String name);

    UUID getDisplayNameOwner(String name);

    void registerDisplayName(String name, UUID uuid);

    void releaseDisplayName(String name);
}
