package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Locale;

/**
 * When a cracked client tries a username that belongs to a premium Mojang account, AeroVelocity
 * forces online mode and Mojang rejects the unverified session — vanilla then shows the cryptic
 * "Failed to log in: Invalid session". This mixin replaces that reason on the disconnect screen with
 * a clear instruction to change the username (and covers the genuine expired-session case too).
 *
 * <p>Hook: {@link DisconnectedScreen#init()} builds the message from {@code this.details.reason()}.
 * We {@code @Redirect} that single call and swap the text when it matches the auth-failure pattern.</p>
 */
@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin {

    @Redirect(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/DisconnectionDetails;reason()Lnet/minecraft/network/chat/Component;"))
    private Component coffeesAero$rewriteReason(DisconnectionDetails details) {
        Component reason = details.reason();
        if (coffeesAero$looksLikeRegistryMismatch(reason)) {
            return coffeesAero$outdatedMessage(reason);
        }
        if (coffeesAero$looksLikeAuthFailure(reason)) {
            return coffeesAero$conflictMessage();
        }
        return reason;
    }

    @Unique
    private boolean coffeesAero$looksLikeRegistryMismatch(Component reason) {
        if (reason == null) return false;
        String s = reason.getString().toLowerCase(Locale.ROOT);
        return s.contains("resourcekey")
            || s.contains("registry")
            || s.contains("mismatched mod channel")
            || s.contains("mod channel list")
            || s.contains("missing the following")
            || s.contains("negotiation failed")
            || (s.contains("missing") && s.contains("mod"));
    }

    /** Longest slice of the server's own reason we keep; the screen wraps, but it is not infinite. */
    @Unique private static final int COFFEES_AERO$MAX_REASON = 400;

    /**
     * Explains a registry/mod-channel kick WITHOUT throwing away what the server actually said.
     *
     * <p>The original text is the only thing that names the missing mods, so it is appended rather
     * than replaced — discarding it made these kicks undiagnosable.</p>
     *
     * <p>The headline also stops asserting "out of date" when the bundled version already equals
     * the latest. That case is real: {@code packVersion} comes from a downloaded config file, so a
     * client whose config landed but whose mod jars did not will report the correct version while
     * still being refused. Telling that player to "update" sends them in a circle.</p>
     */
    @Unique
    private Component coffeesAero$outdatedMessage(Component original) {
        String bridgeUrl = com.coffeesaerosmp.core.UpdaterBridge.downloadUrl();
        final String url = (bridgeUrl == null || bridgeUrl.isBlank())
            ? AeroConfig.UPDATE_URL.get() : bridgeUrl;    // CF build has no version check
        String ver = com.coffeesaerosmp.core.UpdaterBridge.latestVersion();
        String mine = String.valueOf(AeroConfig.PACK_VERSION.get());
        boolean sameVersion = ver != null && !ver.isBlank() && ver.equals(mine);

        String headline = sameVersion
            ? "Your pack files don't match the server."
            : "Your modpack is out of date.";
        String action = sameVersion
            ? "\n\nYour pack says v" + mine + ", but some mods are missing or stale. "
              + "Re-import the pack (a fresh import, not an update) and relaunch."
            : "\n\nRe-import " + ((ver == null || ver.isBlank())
                ? "the latest pack" : "Coffees Aero SMP v" + ver) + " and relaunch to join.";

        net.minecraft.network.chat.MutableComponent msg = Component.literal(headline)
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(action).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\n" + url)
                .withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))))
            .append(Component.literal("\n\n(Your bundled version: v" + mine + ")")
                .withStyle(ChatFormatting.DARK_GRAY));

        String raw = (original == null) ? "" : original.getString();
        if (!raw.isBlank()) {
            String trimmed = raw.length() > COFFEES_AERO$MAX_REASON
                ? raw.substring(0, COFFEES_AERO$MAX_REASON) + "…" : raw;
            msg = msg.append(Component.literal("\n\nServer said: " + trimmed)
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        return msg;
    }

    @Unique
    private boolean coffeesAero$looksLikeAuthFailure(Component reason) {
        if (reason == null) return false;
        String s = reason.getString().toLowerCase(Locale.ROOT);
        return s.contains("invalid session")
            || s.contains("unverified username")
            || s.contains("verify username")
            || s.contains("not verified");
    }

    @Unique
    private Component coffeesAero$conflictMessage() {
        return Component.literal("That username belongs to a premium (paid) Minecraft account.")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(
                    "\n\nTo play here as a guest, change your username in your launcher to one that isn't already taken.")
                .withStyle(ChatFormatting.WHITE))
            .append(Component.literal(
                    "\n\nOwn this account? Restart your launcher to refresh your session, then reconnect.")
                .withStyle(ChatFormatting.GRAY));
    }
}
