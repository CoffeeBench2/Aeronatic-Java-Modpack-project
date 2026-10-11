package com.coffeesaerosmp.auth.watchdog;

/**
 * Parses the container's {@code cpu.stat}. When the host's CPU quota for our container is used up inside a
 * scheduling period, EVERY thread is paused until the next period — the tick loop, the sampler, everything.
 * That looks like a mystery stall from inside the JVM; {@code cpu.stat} is the only place it shows.
 *
 * <ul>
 *   <li>cgroup v2 ({@code /sys/fs/cgroup/cpu.stat}): {@code nr_periods}, {@code nr_throttled}, {@code throttled_usec}</li>
 *   <li>cgroup v1 ({@code .../cpu,cpuacct/cpu.stat}): {@code nr_periods}, {@code nr_throttled}, {@code throttled_time} (ns)</li>
 * </ul>
 * Pure, so the parser is unit-tested against both formats.
 */
public record CgroupCpuStat(long nrPeriods, long nrThrottled, long throttledUsec) {

    /** Null when the text has none of the throttling counters (not a cpu.stat, or a cgroup without a CPU limit). */
    public static CgroupCpuStat parse(String text) {
        if (text == null) return null;
        long periods = -1, throttled = -1, usec = -1;
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length != 2) continue;
            long v;
            try { v = Long.parseLong(p[1]); } catch (NumberFormatException e) { continue; }
            switch (p[0]) {
                case "nr_periods" -> periods = v;
                case "nr_throttled" -> throttled = v;
                case "throttled_usec" -> usec = v;               // v2
                case "throttled_time" -> usec = v / 1000;         // v1, nanoseconds
                default -> { }
            }
        }
        if (periods < 0 && throttled < 0 && usec < 0) return null;
        return new CgroupCpuStat(Math.max(0, periods), Math.max(0, throttled), Math.max(0, usec));
    }

    /** Counters since {@code earlier} (counters only grow; a reset yields zeros instead of negatives). */
    public CgroupCpuStat since(CgroupCpuStat earlier) {
        if (earlier == null) return this;
        return new CgroupCpuStat(Math.max(0, nrPeriods - earlier.nrPeriods),
            Math.max(0, nrThrottled - earlier.nrThrottled), Math.max(0, throttledUsec - earlier.throttledUsec));
    }
}
