package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.RecipeViewer;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Undoes EMI's suppression of JEI's own GUI plugin, so picking JEI actually gives you JEI.
 *
 * <h2>The second, hidden suppression</h2>
 * Cancelling {@code JemiPlugin.registerRuntime} (see {@link JemiPluginMixin}) is NOT enough — that
 * only stops EMI swapping JEI's runtime objects. EMI ALSO ships a mixin of its own,
 * {@code dev.emi.emi.mixin.jei.PluginCallerMixin}, which {@code @Redirect}s the
 * {@code Consumer.accept} call inside {@code PluginCaller.callOnPlugins} and simply <b>returns</b>
 * — never invoking the plugin — when both:
 *
 * <ul>
 *   <li>the plugin UID is in EMI's {@code SKIPPED} set:
 *       {@code jei:minecraft}, {@code jei:gui}, {@code jei:fabric_gui}, {@code jei:forge_gui},
 *       {@code jei:neoforge_gui}; and</li>
 *   <li>the phase is one of {@code Registering categories}, {@code Registering ingredients},
 *       {@code Registering vanilla category extensions}, {@code Sending Runtime},
 *       {@code Sending Runtime Unavailable}.</li>
 * </ul>
 *
 * <p>{@code jei:neoforge_gui} is the plugin that BUILDS JEI's overlay, and it is denied
 * {@code Sending Runtime} — i.e. {@code onRuntimeAvailable} never fires for it. That is why the log
 * happily said "Starting JEI took 3.800 seconds" and no JEI ever appeared on screen: JEI loaded
 * fully and was then denied the one callback that draws it. Verified from EMI 1.1.24 bytecode,
 * 2026-08-18, after the registerRuntime-only fix shipped and still showed nothing.
 *
 * <p>A second set, {@code SKIPPED_MODS} ({@code JemiUtil.getHandledMods()}), skips the JEI plugins
 * of every mod EMI handles natively, in ALL phases. Under JEI those are wanted too, or JEI comes up
 * missing recipe categories.
 *
 * <h2>Why clear the sets instead of out-injecting the redirect</h2>
 * Two mixins cannot both {@code @Redirect} the same instruction, and EMI's redirect handler is
 * merged into {@code PluginCaller} itself, so there is no class left to target. Both sets, however,
 * are merged in as plain static {@code HashSet}s and are mutable. Emptying them at HEAD of the first
 * {@code callOnPlugins} call — which runs before the redirect in the body — makes every skip test
 * miss, and the redirect falls through to {@code accept} exactly as unmodified JEI would.
 *
 * <p>The fields are found by TYPE, not by name: JEI's own {@code PluginCaller} declares only a
 * {@code Logger}, so every static {@code Set} on it came from EMI. That survives EMI renaming them.
 */
@Mixin(targets = "mezz.jei.library.load.PluginCaller", remap = false)
public class JeiPluginCallerMixin {

    private static boolean aero$restored;

    @Inject(method = "callOnPlugins", at = @At("HEAD"), remap = false, require = 0)
    private static void aero$unskipJeiGuiPlugins(String title, java.util.List<?> plugins,
                                                 java.util.function.Consumer<?> func, CallbackInfo ci) {
        if (aero$restored) return;
        aero$restored = true;                       // once per launch, whatever the outcome
        try {
            if (!RecipeViewer.preferJei()) return;
            int cleared = 0;
            for (Field f : Class.forName("mezz.jei.library.load.PluginCaller").getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !Set.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof Set<?> s && !s.isEmpty()) {
                        cleared += s.size();
                        s.clear();
                    }
                } catch (Throwable t) {
                    LogUtils.getLogger().warn("[AeroCore] could not clear EMI skip set {}: {}",
                        f.getName(), t.toString());
                }
            }
            LogUtils.getLogger().info(
                "[AeroCore] Recipe viewer = JEI — cleared {} EMI plugin-skip entries; JEI's own GUI plugin will load.",
                cleared);
        } catch (Throwable t) {
            // EMI absent, or JEI moved the class: unmodified JEI already works, so this is harmless.
            LogUtils.getLogger().warn("[AeroCore] JEI plugin-skip restore failed, leaving as-is: {}", t.toString());
        }
    }
}
