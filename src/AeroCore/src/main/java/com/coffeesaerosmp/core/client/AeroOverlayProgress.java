package com.coffeesaerosmp.core.client;

/**
 * Duck interface implemented onto vanilla {@code LoadingOverlay} by {@code LoadingOverlayMixin} so
 * {@code NeoForgeStartupOverlayMixin} (whose target SUBCLASSES LoadingOverlay) can read the
 * smoothed progress value without shadowing a parent-class field.
 */
public interface AeroOverlayProgress {

    float aerocore$progress();
}
