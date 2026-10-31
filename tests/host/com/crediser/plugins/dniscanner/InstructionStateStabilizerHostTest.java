package com.crediser.plugins.dniscanner;

public final class InstructionStateStabilizerHostTest {
    public static void main(String[] args) {
        InstructionStateStabilizer tracker = new InstructionStateStabilizer(450L);
        require(tracker.update(AnalysisResult.State.HOLD_STILL, 1000L)
            == AnalysisResult.State.SEARCHING, "Transient state changed the instruction");
        require(tracker.update(AnalysisResult.State.EDGE_OCCLUDED, 1200L)
            == AnalysisResult.State.SEARCHING, "Oscillation was not debounced");
        require(tracker.update(AnalysisResult.State.EDGE_OCCLUDED, 1651L)
            == AnalysisResult.State.EDGE_OCCLUDED, "Persistent state was not displayed");
        tracker.force(AnalysisResult.State.CAPTURING);
        require(tracker.update(AnalysisResult.State.HOLD_STILL, 1700L)
            == AnalysisResult.State.CAPTURING, "Forced capture state was not preserved");
        InstructionStateStabilizer lossGrace = new InstructionStateStabilizer(500L, 300L);
        lossGrace.force(AnalysisResult.State.HOLD_STILL);
        require(lossGrace.update(AnalysisResult.State.SEARCHING, 2000L)
            == AnalysisResult.State.HOLD_STILL, "Tracking loss regressed immediately");
        require(lossGrace.update(AnalysisResult.State.SEARCHING, 2301L)
            == AnalysisResult.State.SEARCHING, "Tracking loss grace exceeded 300 ms");
        System.out.println("InstructionStateStabilizerHostTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
