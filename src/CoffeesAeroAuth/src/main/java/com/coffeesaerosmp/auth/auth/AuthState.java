package com.coffeesaerosmp.auth.auth;

public enum AuthState {
    AWAITING_TYPE,   // joined via proxy; frozen until AeroVelocity sends premium/cracked signal
    PENDING,         // offline player, has account, needs /login
    AUTHENTICATED,   // auth complete (premium auto-sets this on join)
    LOBBY_REGISTER,  // in the login lobby — no password yet, needs /register
    LOBBY_NAMING,    // in the login lobby — password set, needs /setname
    LOBBY_PENDING,   // in the login lobby — name submitted, waiting for admin approval
}
