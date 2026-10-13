package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

/** Verifies that capture only becomes ready after consecutive stable geometry. */
public final class StabilityTrackerHostTest {
    private StabilityTrackerHostTest() {
    }

    public static void main(String[] args) {
        StabilityTracker tracker = new StabilityTracker(ScannerConfig.DEFAULT);
        AnalysisResult result = null;
        for (int frame = 0; frame < 7; frame++) {
            float jitter = frame % 2 == 0 ? 0.8f : -0.8f;
            result = tracker.apply(readyResult(1000L + frame * 100L, jitter), 640, 960);
            System.out.printf("frame=%d state=%s stableFrames=%d duration=%d%n",
                frame, result.state, result.stableFrameCount, result.stableDurationMs);
        }
        if (result == null || result.state != AnalysisResult.State.READY) {
            throw new AssertionError("Stable geometry did not become capture-ready");
        }

        AnalysisResult moved = tracker.apply(readyResult(1800L, 55f), 640, 960);
        if (moved.state != AnalysisResult.State.HOLD_STILL || moved.stableFrameCount != 1) {
            throw new AssertionError("Large movement did not reset stability");
        }
    }

    private static AnalysisResult readyResult(long timestamp, float offset) {
        DocumentQuad quad = new DocumentQuad(
            new PointF(70f + offset, 310f),
            new PointF(570f + offset, 310f),
            new PointF(570f + offset, 625f),
            new PointF(70f + offset, 625f)
        );
        DocumentDetection detection = new DocumentDetection(quad, 0.9f, 0.26f, 0f, 1.587f);
        QualityMetrics quality = new QualityMetrics(300.0, 128f, 0f, 0f, 0f, 0f, 1f);
        return new AnalysisResult(
            AnalysisResult.State.READY,
            detection,
            quality,
            true,
            true,
            timestamp
        );
    }
}
