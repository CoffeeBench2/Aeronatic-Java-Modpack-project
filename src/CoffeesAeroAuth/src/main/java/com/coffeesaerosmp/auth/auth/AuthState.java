package com.coffeesaerosmp.auth.auth;

public enum AuthState {
    AWAITING_TYPE,   // joined via proxy; frozen until AeroVelocity sends premium/cracked signal
    PENDING,         // offline player, has account, needs /login
    AUTHENTICATED,   // auth complete (premium auto-sets this on join)
    LOBBY_REGISTER,  // in private room — no password yet, needs /register
    LOBBY_NAMING,    // in private room — password set, needs /setname
    LOBBY_PENDING,   // in private room — name submitted, waiting for admin approval
}
