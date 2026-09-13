package com.tanrunn.buildshop.client.ldlib2;

import com.lowdragmc.lowdraglib2.math.Size;

/** Shared visual scale for the native LDLib2 screens. */
final class LdLib2UiMetrics {
    /** Keep the shop compact so the product grid remains the visual focus. */
    private static final float SCALE = 0.60f;

    private LdLib2UiMetrics() {
    }

    static float scale(float value) {
        return value * SCALE;
    }

    static Size fitToScreen(Size screen) {
        return Size.of(Math.max(1, Math.min(Math.round(scale(820)), screen.width - 24)),
                Math.max(1, Math.min(Math.round(scale(560)), screen.height - 24)));
    }
}
