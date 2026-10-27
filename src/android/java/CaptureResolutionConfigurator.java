package com.crediser.plugins.dniscanner;

import android.util.Size;
import android.view.Surface;

import androidx.camera.core.ImageCapture;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;

/** Configures still capture resolution independently from corrected output sizing. */
final class CaptureResolutionConfigurator {
    private CaptureResolutionConfigurator() {
    }

    static void apply(ImageCapture.Builder builder, int preferredLongEdge, int targetRotation) {
        Size preferred = preferredSize(preferredLongEdge, targetRotation);
        ResolutionStrategy strategy = new ResolutionStrategy(
            preferred,
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        );
        ResolutionSelector selector = new ResolutionSelector.Builder()
            .setResolutionStrategy(strategy)
            .build();
        builder.setResolutionSelector(selector);
    }

    static Size preferredSize(int preferredLongEdge, int targetRotation) {
        int shortEdge = Math.max(1, Math.round(preferredLongEdge * 3f / 4f));
        boolean landscape = targetRotation == Surface.ROTATION_90
            || targetRotation == Surface.ROTATION_270;
        return landscape
            ? new Size(preferredLongEdge, shortEdge)
            : new Size(shortEdge, preferredLongEdge);
    }

}
