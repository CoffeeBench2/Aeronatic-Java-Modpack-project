package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;

/**
 * Audio feedback for commands, so an action that changes state is never silent.
 *
 * <p>Named by INTENT, not by sound file. Call sites say {@code Sounds.success(player)} rather than
 * naming a specific event, so the palette can be retuned in one place — the same reason
 * {@link TextUtil#PREFIX} exists for the visual half. Pick a sound here and every command that
 * means "that worked" changes with it.
 *
 * <p>Everything uses {@link ServerPlayer#playNotifySound} — a direct packet to that one player at
 * full clarity, with no position in the world. A positional {@code level.playSound} would leak the
 * player's actions to anyone standing nearby and would fade with distance, which is wrong for what
 * is effectively UI.
 */
public final class Sounds {

    private Sounds() {}

    private static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (player == null || sound == null) return;
        try {
            if (!AuthConfig.SOUND_FEEDBACK.get()) return;
            player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
        } catch (Exception ignored) {
            // Feedback must never be able to break the command it is decorating.
        }
    }

    /** A command did what was asked — light, quick, unobtrusive. */
    public static void success(ServerPlayer player) {
        play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.5f);
    }

    /** Refused: cooldown still running, bad input, not allowed. The classic "no". */
    public static void error(ServerPlayer player) {
        play(player, SoundEvents.VILLAGER_NO, 0.6f, 1.2f);
    }

    /** Something is waiting for the player — the /daily join nudge. Meant to be noticed, not startle. */
    public static void notify(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.6f, 1.6f);
    }

    /** A reward actually landed in the inventory. The one place a celebratory sound is earned. */
    public static void reward(ServerPlayer player) {
        play(player, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.2f);
        play(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.0f);
    }

    /** The player moved somewhere — /spawn, /home, /rtp arrival. */
    public static void teleport(ServerPlayer player) {
        play(player, SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.4f);
    }

    /**
     * Server-wide attention: a restart warning, a mode change. Two tones so it reads as a signal
     * rather than as another UI blip — a single note is what every other command already sounds
     * like, and this needs to cut through that.
     */
    public static void alert(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 0.7f);
        play(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.0f);
    }

    /** A server-wide mode switched. Rising = on, falling = off, so it is audible which way it went. */
    public static void mode(ServerPlayer player, boolean on) {
        play(player, SoundEvents.NOTE_BLOCK_BIT.value(), 0.7f, on ? 0.8f : 1.4f);
        play(player, SoundEvents.NOTE_BLOCK_BIT.value(), 0.7f, on ? 1.4f : 0.8f);
    }

    /**
     * The teleport as BYSTANDERS experience it — a positional enderman VWOOP plus portal particles
     * at a world position, so people nearby see and hear that someone left or arrived.
     *
     * <p><b>Deliberately not {@code playNotifySound}, and deliberately not gated on
     * {@code soundFeedback}.</b> Everything else in this class is UI for the acting player, and
     * that config's own wording is "sent only to the acting player, never to bystanders". This is
     * the opposite: a world event, like a door or an ender pearl, and it belongs to the world
     * rather than to whoever typed the command.
     */
    public static void teleportAmbient(net.minecraft.server.level.ServerLevel level,
                                       double x, double y, double z) {
        if (level == null) return;
        try {
            level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 0.9f, 1.0f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                x, y + 1.0, z, 40, 0.35, 0.9, 0.35, 0.4);
        } catch (Exception ignored) {
            // Cosmetics must never break the teleport they are decorating.
        }
    }
}
