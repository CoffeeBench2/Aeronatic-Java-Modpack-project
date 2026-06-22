package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Feature B — Discord ⇄ server admin console bridge.
 *
 * <ul>
 *   <li><b>Console → Discord:</b> a runtime Log4j2 appender on the root logger batches INFO+ server log
 *       lines and posts them to the admin console channel as code blocks (rate-limited; never blocks the
 *       logging thread; self-originated "[Discord]" lines are filtered to avoid feedback loops).</li>
 *   <li><b>Discord → console:</b> {@link #runCommand} executes an admin's message as a server command on the
 *       server thread through an output-capturing {@link CommandSourceStack} and posts the captured
 *       success/failure output back to the same channel.</li>
 * </ul>
 *
 * Role gating + channel routing happen upstream in {@link DiscordGateway}; by the time {@link #runCommand}
 * is called the author is already confirmed to hold the admin role.
 */
public class AdminConsoleBridge {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String APPENDER_NAME = "AeroAuthDiscordConsole";
    private static final int    MAX_MSG_CHARS = 1900;   // Discord hard limit is 2000; leave room for fences
    private static final int    MAX_LINES_PER_FLUSH = 20;
    private static final int    QUEUE_CAPACITY = 500;

    private final MinecraftServer server;
    private final DiscordRest     rest;
    private final String          channelId;

    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean         mirrorEnabled = false;
    private MirrorAppender           appender;
    private ScheduledExecutorService flusher;

    public AdminConsoleBridge(MinecraftServer server, DiscordRest rest, String channelId) {
        this.server    = server;
        this.rest      = rest;
        this.channelId = channelId == null ? "" : channelId;
    }

    public boolean isConfigured() {
        return rest != null && rest.isConfigured() && !channelId.isBlank();
    }

    // ── Console → Discord mirror ──────────────────────────────────────────────

    /** Attaches the log appender and starts the batched flusher. No-op if not configured. */
    public void startMirror() {
        if (!isConfigured() || mirrorEnabled) return;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            appender = new MirrorAppender();
            appender.start();
            // Attach to the root logger at INFO threshold so DEBUG/TRACE spam never reaches us.
            config.getRootLogger().addAppender(appender, Level.INFO, null);
            ctx.updateLoggers();

            flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AeroAuth-ConsoleMirror");
                t.setDaemon(true);
                return t;
            });
            flusher.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
            mirrorEnabled = true;
            CoffeesAeroAuth.LOGGER.info("[Discord] Admin console mirror active → channel {}", channelId);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] Failed to start console mirror: {}", e.getMessage());
        }
    }

    public void stopMirror() {
        mirrorEnabled = false;
        try {
            if (appender != null) {
                LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
                ctx.getConfiguration().getRootLogger().removeAppender(APPENDER_NAME);
                ctx.updateLoggers();
                appender.stop();
                appender = null;
            }
        } catch (Exception ignored) {}
        if (flusher != null) {
            flusher.shutdownNow();
            flusher = null;
        }
        queue.clear();
    }

    /** Drains a budget of queued lines into one fenced message and posts it. Runs off-thread. */
    private void flush() {
        if (!mirrorEnabled || queue.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder();
            int lines = 0;
            while (lines < MAX_LINES_PER_FLUSH) {
                String line = queue.peek();
                if (line == null) break;
                if (sb.length() + line.length() + 1 > MAX_MSG_CHARS) break;
                queue.poll();
                sb.append(line).append('\n');
                lines++;
            }
            if (sb.length() == 0) {
                // Single oversized line: hard-trim it so it never wedges the queue.
                String big = queue.poll();
                if (big == null) return;
                sb.append(big, 0, Math.min(big.length(), MAX_MSG_CHARS));
            }
            postCodeBlock(sb.toString());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] console flush error: {}", e.getMessage());
        }
    }

    private void postCodeBlock(String body) {
        String fenced = "```ansi\n" + body.replace("```", "ʼʼʼ") + "```";
        JsonObject o = new JsonObject();
        o.addProperty("content", fenced);
        rest.postMessage(channelId, o.toString());
    }

    // ── Discord → console command ─────────────────────────────────────────────

    /**
     * Runs {@code commandLine} as a level-4 server command and posts captured output back to Discord.
     * MUST be called on the server thread (DiscordGateway routes admin messages via server.execute).
     */
    public void runCommand(String author, String commandLine) {
        String cmd = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        if (cmd.isBlank()) return;

        final List<String> captured = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component component) { captured.add(component.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };

        ServerLevel level = server.overworld();
        CommandSourceStack source = new CommandSourceStack(
            sink,
            Vec3.atLowerCornerOf(level.getSharedSpawnPos()),
            Vec2.ZERO,
            level,
            4,                                   // operator permission
            "Discord:" + author,
            Component.literal("Discord (" + author + ")"),
            server,
            null
        );

        CoffeesAeroAuth.LOGGER.info("[Discord] {} ran command: /{}", author, cmd);
        try {
            server.getCommands().performPrefixedCommand(source, cmd);
        } catch (Throwable t) {
            captured.add("✖ command threw: " + t.getMessage());
        }

        String out = captured.isEmpty()
            ? "✅ `/" + cmd + "` executed (no output)."
            : "▶ `/" + cmd + "`\n" + String.join("\n", captured);
        if (out.length() > MAX_MSG_CHARS) out = out.substring(0, MAX_MSG_CHARS) + " …(truncated)";
        postCodeBlock(out);
    }

    // ── Log appender ──────────────────────────────────────────────────────────

    private class MirrorAppender extends AbstractAppender {
        MirrorAppender() {
            super(APPENDER_NAME, (Filter) null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            try {
                if (!mirrorEnabled) return;
                String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
                // Don't mirror our own Discord HTTP/gateway chatter — that would feed back into the channel.
                if (logger.contains("coffees_aero_auth") && event.getMessage().getFormattedMessage().contains("[Discord]")) return;
                if (logger.startsWith("jdk.") || logger.startsWith("java.net")) return;

                String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
                if (msg.contains("[Discord]")) return;

                String src = logger.contains(".") ? logger.substring(logger.lastIndexOf('.') + 1) : logger;
                String line = "[" + CLOCK.format(java.time.Instant.ofEpochMilli(event.getTimeMillis()))
                    + " " + event.getLevel() + "] [" + src + "] " + msg;
                if (line.length() > MAX_MSG_CHARS) line = line.substring(0, MAX_MSG_CHARS);

                // Non-blocking: drop oldest if the buffer is saturated rather than stalling the log thread.
                if (!queue.offer(line)) {
                    queue.poll();
                    queue.offer(line);
                }
                if (event.getThrown() != null) {
                    queue.offer("    ↳ " + event.getThrown().getClass().getSimpleName()
                        + ": " + event.getThrown().getMessage());
                }
            } catch (Throwable ignored) {
                // An appender must never throw back into the logging pipeline.
            }
        }
    }
}
