package com.crediser.plugins.dniscanner;

/** Deterministic confirmation test for transient preview glare. */
public final class GlareTrackerHostTest {
    private GlareTrackerHostTest() {
    }

    public static void main(String[] args) {
        GlareTracker tracker = new GlareTracker(ScannerConfig.DEFAULT);
        assertState(tracker.apply(AnalysisResult.State.AVOID_GLARE),
            AnalysisResult.State.HOLD_STILL, "first-transient-frame");
        assertState(tracker.apply(AnalysisResult.State.AVOID_GLARE),
            AnalysisResult.State.HOLD_STILL, "second-transient-frame");
        assertState(tracker.apply(AnalysisResult.State.AVOID_GLARE),
            AnalysisResult.State.AVOID_GLARE, "third-confirmed-frame");

        assertState(tracker.apply(AnalysisResult.State.READY),
            AnalysisResult.State.READY, "clear-resets-tracker");
        assertState(tracker.apply(AnalysisResult.State.AVOID_GLARE),
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
