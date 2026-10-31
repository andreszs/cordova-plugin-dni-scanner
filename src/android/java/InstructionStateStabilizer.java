package com.crediser.plugins.dniscanner;

/**
 * Keeps preview instructions readable without delaying the scanner itself.
 * Capture decisions continue to use the raw analysis state.
 */
final class InstructionStateStabilizer {
    private final long confirmationDurationMs;
    private final long lossGraceDurationMs;
    private AnalysisResult.State visibleState = AnalysisResult.State.SEARCHING;
    private AnalysisResult.State pendingState;
    private long pendingSinceMs;

    InstructionStateStabilizer(long confirmationDurationMs) {
        this(confirmationDurationMs, confirmationDurationMs);
    }

    InstructionStateStabilizer(long confirmationDurationMs, long lossGraceDurationMs) {
        this.confirmationDurationMs = Math.max(0L, confirmationDurationMs);
        this.lossGraceDurationMs = Math.max(0L, lossGraceDurationMs);
    }

    AnalysisResult.State update(AnalysisResult.State candidate, long nowMs) {
        if (candidate == visibleState) {
            pendingState = null;
            pendingSinceMs = 0L;
            return visibleState;
        }
        if (candidate != pendingState) {
            pendingState = candidate;
            pendingSinceMs = nowMs;
            return visibleState;
        }
        long requiredDuration = candidate == AnalysisResult.State.SEARCHING
            ? lossGraceDurationMs
            : confirmationDurationMs;
        if (nowMs - pendingSinceMs >= requiredDuration) {
            visibleState = candidate;
            pendingState = null;
            pendingSinceMs = 0L;
        }
        return visibleState;
    }

    void force(AnalysisResult.State state) {
        visibleState = state;
        pendingState = null;
        pendingSinceMs = 0L;
    }
}
