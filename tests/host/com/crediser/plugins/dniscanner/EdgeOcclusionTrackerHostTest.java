package com.crediser.plugins.dniscanner;

/** Deterministic confirmation test for transient preview edge obstruction. */
public final class EdgeOcclusionTrackerHostTest {
    private EdgeOcclusionTrackerHostTest() {
    }

    public static void main(String[] args) {
        EdgeOcclusionTracker tracker = new EdgeOcclusionTracker(ScannerConfig.DEFAULT);
        assertState(tracker.apply(AnalysisResult.State.EDGE_OCCLUDED),
            AnalysisResult.State.HOLD_STILL, "first-transient-frame");
        assertState(tracker.apply(AnalysisResult.State.EDGE_OCCLUDED),
            AnalysisResult.State.HOLD_STILL, "second-transient-frame");
        assertState(tracker.apply(AnalysisResult.State.EDGE_OCCLUDED),
            AnalysisResult.State.EDGE_OCCLUDED, "third-confirmed-frame");

        assertState(tracker.apply(AnalysisResult.State.READY),
            AnalysisResult.State.READY, "clear-resets-tracker");
        assertState(tracker.apply(AnalysisResult.State.EDGE_OCCLUDED),
            AnalysisResult.State.HOLD_STILL, "new-first-frame-after-reset");
    }

    private static void assertState(AnalysisResult.State actual,
                                    AnalysisResult.State expected,
                                    String name) {
        System.out.printf("%s expected=%s actual=%s%n", name, expected, actual);
        if (actual != expected) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }
}
