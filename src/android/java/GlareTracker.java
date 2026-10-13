package com.crediser.plugins.dniscanner;

/** Confirms transient preview glare readings over consecutive analyzed frames. */
final class GlareTracker {
    private final int requiredFrames;
    private int consecutiveFrames;

    GlareTracker(ScannerConfig config) {
        requiredFrames = Math.max(1, config.glareConfirmationFrames);
    }

    AnalysisResult.State apply(AnalysisResult.State state) {
        if (state != AnalysisResult.State.AVOID_GLARE) {
            reset();
            return state;
        }
        consecutiveFrames++;
        return consecutiveFrames >= requiredFrames
            ? AnalysisResult.State.AVOID_GLARE
            : AnalysisResult.State.HOLD_STILL;
    }

    void reset() {
        consecutiveFrames = 0;
    }
}
