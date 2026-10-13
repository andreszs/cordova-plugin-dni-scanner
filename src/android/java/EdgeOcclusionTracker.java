package com.crediser.plugins.dniscanner;

/** Confirms transient preview edge-obstruction readings over consecutive frames. */
final class EdgeOcclusionTracker {
    private final int requiredFrames;
    private int consecutiveFrames;

    EdgeOcclusionTracker(ScannerConfig config) {
        requiredFrames = Math.max(1, config.edgeOcclusionConfirmationFrames);
    }

    AnalysisResult.State apply(AnalysisResult.State state) {
        if (state != AnalysisResult.State.EDGE_OCCLUDED) {
            reset();
            return state;
        }
        consecutiveFrames++;
        return consecutiveFrames >= requiredFrames
            ? AnalysisResult.State.EDGE_OCCLUDED
            : AnalysisResult.State.HOLD_STILL;
    }

    void reset() {
        consecutiveFrames = 0;
    }
}
