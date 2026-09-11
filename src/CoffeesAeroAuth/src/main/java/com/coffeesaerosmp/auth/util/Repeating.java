package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

/**
 * Wrapper for tasks handed to {@code ScheduledExecutorService.scheduleAtFixedRate} /
 * {@code scheduleWithFixedDelay}.
 *
 * <h3>The trap</h3>
 * Both methods <b>cancel a repeating task permanently the first time it throws</b>, and do it
 * <b>silently</b> — the exception is parked in a {@link java.util.concurrent.Future} nobody reads.
 * The symptom is a feature that "works and then just stops", with nothing in the log, until the next
 * server restart. No stack trace, no warning, and the scheduler itself keeps running its other
 * tasks so the process looks healthy.
 *
 * <p>That cost a real debugging session on 2026-09-06: the Discord bridge stopped relaying chat
 * while joins and achievements kept arriving, because only the chat path had thrown.
 *
 * <h3>Why every repeating task needs it, even "safe" ones</h3>
 * The bodies here are mostly well defended already — they catch {@code Exception} around their own
 * IO. That is not the same as being safe to leave bare:
 * <ul>
 *   <li>they catch {@code Exception}, not {@code Throwable}, so an {@code Error} or an unchecked
 *       throw from a callee still ends the schedule;</li>
 *   <li>a {@code ModConfigSpec} value read before the config loads throws
 *       {@code IllegalStateException} — none of these bodies guard their config reads;</li>
 *   <li>"it cannot throw today" is a property of the current call graph, and these bodies call into
 *       Discord, MySQL, spark and the audit log. The guard costs one stack frame.</li>
 * </ul>
 * The point is not that a throw is likely; it is that the consequence of one is a silently dead
 * subsystem, which is the hardest possible failure to notice.
 *
 * <p>Named, because "some scheduled task died" is not actionable — the log line has to say which.
 */
public final class Repeating {

    private Repeating() {}

    /** Wraps a repeating task so a throw is logged and the schedule survives. */
    public static Runnable guard(String name, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (Throwable t) {
                // Throwable, not Exception: an Error kills the schedule just as permanently, and
                // this wrapper exists precisely so that nothing ends the repeat silently.
                CoffeesAeroAuth.LOGGER.error(
                    "[Repeating] '{}' threw — schedule kept alive, but this tick did nothing.",
                    name, t);
            }
        };
    }
}
