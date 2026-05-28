package com.coffeesaerosmp.auth.watchdog;

public enum Severity {
    LOW     ("🟢", "LOW",      0x00CC00),
    MEDIUM  ("🟡", "MEDIUM",   0xFFCC00),
    HIGH    ("🟠", "HIGH",     0xFF6600),
    CRITICAL("🔴", "CRITICAL", 0xFF0000);

    public final String emoji;
    public final String label;
    public final int    color;   // Discord embed color (decimal RGB)

    Severity(String emoji, String label, int color) {
        this.emoji = emoji;
        this.label = label;
        this.color = color;
    }
}
