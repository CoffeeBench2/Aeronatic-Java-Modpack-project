package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.version.VersionCheck;
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
            return coffeesAero$outdatedMessage();
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

    @Unique
    private Component coffeesAero$outdatedMessage() {
        String url = VersionCheck.downloadUrl();
        String ver = VersionCheck.latestVersion();
        String verText = (ver == null || ver.isBlank()) ? "the latest pack" : "Coffees Aero SMP v" + ver;
        return Component.literal("Your modpack is out of date.")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal("\n\nRe-import " + verText + " and relaunch to join.")
                .withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\n" + url)
                .withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))))
            .append(Component.literal("\n\n(Your bundled version: v" + AeroConfig.PACK_VERSION.get() + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
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
