package com.coffeesaerosmp.auth.auth;

public enum AuthState {
    PENDING,      // offline player, has password, not yet logged in
    REGISTERING,  // offline player, no password yet — waiting for /register
    AUTHENTICATED // auth complete (premium auto-sets this on join)
}
