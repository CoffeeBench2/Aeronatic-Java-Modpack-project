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
}
