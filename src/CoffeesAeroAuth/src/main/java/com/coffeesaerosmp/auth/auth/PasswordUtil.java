package com.coffeesaerosmp.auth.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

public final class PasswordUtil {

    private static final int    ITERATIONS = 260_000;
    private static final int    KEY_BITS   = 256;
    private static final String ALGO       = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RNG  = new SecureRandom();

    private PasswordUtil() {}

    public static String generateSalt() {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String saltB64) {
        byte[] salt = Base64.getDecoder().decode(saltB64);
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance(ALGO);
            byte[] hash = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    /** Constant-time comparison — prevents timing attacks. */
    public static boolean verify(String password, String saltB64, String expectedB64) {
        String actual = hash(password, saltB64);
        byte[] a = Base64.getDecoder().decode(actual);
        byte[] b = Base64.getDecoder().decode(expectedB64);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
