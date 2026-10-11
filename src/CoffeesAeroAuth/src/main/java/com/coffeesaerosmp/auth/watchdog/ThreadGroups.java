package com.coffeesaerosmp.auth.watchdog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns per-thread CPU time into "which SUBSYSTEM used the CPU quota". After the 1.13.29 restart S3 was
 * CPU-throttled in 500–1,000 of every 3,000 scheduling periods; the question is whose threads spend it
 * (Distant Horizons workers? Sable physics? GC? the server thread?). Pure, so the grouping is unit-tested.
 */
public final class ThreadGroups {

    private ThreadGroups() {}

    /** "DH-Worker-3" → "DH-Worker-#", "Worker-Main-12" → "Worker-Main-#", "Server thread" stays. */
    public static String group(String threadName) {
        if (threadName == null || threadName.isBlank()) return "(unnamed)";
        String g = threadName.replaceAll("\\d+", "#").replaceAll("#(\\s*[-_#\\s]*#)+", "#");
        return g.length() > 48 ? g.substring(0, 48) : g;
    }

    /** CPU nanoseconds per group, top {@code n}, as "Server thread 41.2s, DH-Worker-# 30.0s, …". */
    public static String top(Map<String, Long> cpuNanosByThreadName, int n) {
        Map<String, Long> byGroup = new HashMap<>();
        long total = 0;
        for (var e : cpuNanosByThreadName.entrySet()) {
            if (e.getValue() <= 0) continue;
            byGroup.merge(group(e.getKey()), e.getValue(), Long::sum);
            total += e.getValue();
        }
        List<Map.Entry<String, Long>> l = new ArrayList<>(byGroup.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i).getKey()).append(' ')
              .append(String.format(Locale.ROOT, "%.1fs", l.get(i).getValue() / 1e9));
        }
        if (total > 0) sb.append(String.format(Locale.ROOT, " (all threads %.1fs CPU)", total / 1e9));
        return sb.toString();
    }
}
