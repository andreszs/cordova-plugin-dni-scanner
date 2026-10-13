package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

final class StabilityTracker {
    private final ScannerConfig config;
    private DocumentQuad previousQuad;
    private float previousArea;
    private int stableFrames;
    private long stableSinceMs;

    StabilityTracker(ScannerConfig config) {
        this.config = config;
    }

    AnalysisResult apply(AnalysisResult current, int frameWidth, int frameHeight) {
        if (!current.isReadyCandidate() || current.detection == null) {
            reset();
            return current;
        }

        long now = current.timestampMs;
        boolean stable = previousQuad != null && isStable(
            previousQuad,
            current.detection.quad,
            previousArea,
            current.detection.quad.area(),
            frameWidth,
            frameHeight
        );

        if (!stable) {
            stableFrames = 1;
            stableSinceMs = now;
        } else {
            stableFrames++;
        }
        previousQuad = current.detection.quad;
        previousArea = current.detection.quad.area();

        long duration = Math.max(0L, now - stableSinceMs);
        boolean ready = stableFrames >= config.stableFrames && duration >= config.stableDurationMs;
        AnalysisResult result = new AnalysisResult(
            ready ? AnalysisResult.State.READY : AnalysisResult.State.HOLD_STILL,
            current.detection,
            current.quality,
            current.geometryValid,
            current.qualityValid,
            current.timestampMs,
            current.source
        );
        result.stableFrameCount = stableFrames;
        result.stableDurationMs = duration;
        return result;
    }

    void reset() {
        previousQuad = null;
        previousArea = 0f;
        stableFrames = 0;
        stableSinceMs = 0L;
    }

    private boolean isStable(DocumentQuad previous, DocumentQuad current,
                             float oldArea, float newArea, int width, int height) {
        float diagonal = (float) Math.hypot(width, height);
        PointF[] oldPoints = {
            previous.topLeft, previous.topRight, previous.bottomRight, previous.bottomLeft
        };
        PointF[] newPoints = {
            current.topLeft, current.topRight, current.bottomRight, current.bottomLeft
        };
        float squared = 0f;
        for (int i = 0; i < 4; i++) {
            float distance = DocumentQuad.distance(oldPoints[i], newPoints[i]) / diagonal;
            squared += distance * distance;
        }
        float rootMeanSquareMotion = (float) Math.sqrt(squared / 4f);
        float areaChange = oldArea <= 0f ? 1f : Math.abs(newArea - oldArea) / oldArea;
        return rootMeanSquareMotion <= config.maxCornerMotion
            && areaChange <= config.maxAreaChange;
    }
}
