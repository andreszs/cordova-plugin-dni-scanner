package com.crediser.plugins.dniscanner;

public final class MotionStabilityTrackerHostTest {
    public static void main(String[] args) {
        fallsBackWhenSensorsAreMissing();
        waitsForSensorStartup();
        rejectsRotationUntilQuiet();
        rejectsTranslationUntilQuiet();
        System.out.println("MotionStabilityTrackerHostTest OK");
    }

    private static void fallsBackWhenSensorsAreMissing() {
        MotionStabilityTracker tracker = new MotionStabilityTracker(ScannerConfig.DEFAULT);
        tracker.reset(100L);
        MotionSnapshot snapshot = tracker.snapshot(100L, false, false);
        require(snapshot.stable, "Missing sensors must preserve visual-only stability");
        require(!snapshot.available, "Missing sensors must be reported as unavailable");
    }

    private static void waitsForSensorStartup() {
        MotionStabilityTracker tracker = new MotionStabilityTracker(ScannerConfig.DEFAULT);
        tracker.reset(0L);
        require(!tracker.snapshot(100L, true, true).stable,
            "Registered sensors must receive startup time before capture");
        MotionSnapshot fallback = tracker.snapshot(
            ScannerConfig.DEFAULT.motionSensorStartupTimeoutMs + 1L,
            true,
            true
        );
        require(fallback.stable, "A silent sensor must not block capture forever");
        require(!fallback.sampling, "Silent sensors must be identified as not sampling");
    }

    private static void rejectsRotationUntilQuiet() {
        MotionStabilityTracker tracker = new MotionStabilityTracker(ScannerConfig.DEFAULT);
        tracker.reset(0L);
        tracker.addGyroscope(0.55f, 0f, 0f, 10L);
        tracker.addAcceleration(0f, 0f, 0f, 10L);
        MotionSnapshot moving = tracker.snapshot(20L, true, true);
        require(!moving.stable, "A gyroscope spike must block capture");
        require(moving.motionSequence > 0L, "Motion must advance the capture guard");
        settle(tracker, 100L, 1400L);
        MotionSnapshot quiet = tracker.snapshot(1400L, true, true);
        require(quiet.stable, "Capture must resume after the configured quiet period");
    }

    private static void rejectsTranslationUntilQuiet() {
        MotionStabilityTracker tracker = new MotionStabilityTracker(ScannerConfig.DEFAULT);
        tracker.reset(0L);
        tracker.addGyroscope(0f, 0f, 0f, 10L);
        tracker.addAcceleration(1.2f, 0f, 0f, 10L);
        require(!tracker.snapshot(20L, true, true).stable,
            "An acceleration spike must block capture");
        settle(tracker, 100L, 1600L);
        require(tracker.snapshot(1600L, true, true).stable,
            "Acceleration must settle before capture resumes");
    }

    private static void settle(MotionStabilityTracker tracker, long firstMs, long lastMs) {
        for (long now = firstMs; now <= lastMs; now += 100L) {
            tracker.addGyroscope(0f, 0f, 0f, now);
            tracker.addAcceleration(0f, 0f, 0f, now);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
