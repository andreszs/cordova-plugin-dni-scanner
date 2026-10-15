package com.crediser.plugins.dniscanner;

/** Thread-safe, Android-independent filtering for device motion sensors. */
final class MotionStabilityTracker {
    private final ScannerConfig config;
    private long startedAtMs;
    private long lastSampleMs;
    private long lastMotionMs;
    private long motionSequence;
    private float smoothedGyroscope;
    private float smoothedAcceleration;
    private boolean hasGyroscopeSample;
    private boolean hasAccelerationSample;

    MotionStabilityTracker(ScannerConfig config) {
        this.config = config;
    }

    synchronized void reset(long nowMs) {
        startedAtMs = nowMs;
        lastSampleMs = 0L;
        lastMotionMs = nowMs;
        motionSequence = 0L;
        smoothedGyroscope = 0f;
        smoothedAcceleration = 0f;
        hasGyroscopeSample = false;
        hasAccelerationSample = false;
    }

    synchronized void addGyroscope(float x, float y, float z, long nowMs) {
        float magnitude = magnitude(x, y, z);
        smoothedGyroscope = smooth(smoothedGyroscope, magnitude, hasGyroscopeSample);
        hasGyroscopeSample = true;
        lastSampleMs = nowMs;
        if (magnitude > config.maxGyroscopeSpikeRadPerSecond
            || smoothedGyroscope > config.maxGyroscopeRadPerSecond) {
            recordMotion(nowMs);
        }
    }

    synchronized void addAcceleration(float x, float y, float z, long nowMs) {
        float magnitude = magnitude(x, y, z);
        smoothedAcceleration = smooth(smoothedAcceleration, magnitude, hasAccelerationSample);
        hasAccelerationSample = true;
        lastSampleMs = nowMs;
        if (magnitude > config.maxAccelerationSpike
            || smoothedAcceleration > config.maxLinearAcceleration) {
            recordMotion(nowMs);
        }
    }

    synchronized MotionSnapshot snapshot(long nowMs, boolean gyroscopeAvailable,
                                         boolean accelerationAvailable) {
        boolean available = gyroscopeAvailable || accelerationAvailable;
        boolean hasSample = (gyroscopeAvailable && hasGyroscopeSample)
            || (accelerationAvailable && hasAccelerationSample);
        boolean sampling = hasSample
            && nowMs - lastSampleMs <= config.motionSensorStaleTimeoutMs;
        boolean startupExpired = nowMs - startedAtMs
            >= config.motionSensorStartupTimeoutMs;
        boolean stable = !available
            || (!sampling && startupExpired)
            || (sampling && nowMs - lastMotionMs >= config.motionStableDurationMs);
        long quietDurationMs = Math.max(0L, nowMs - lastMotionMs);
        return new MotionSnapshot(
            available,
            gyroscopeAvailable,
            accelerationAvailable,
            sampling,
            stable,
            hasGyroscopeSample ? smoothedGyroscope : Float.NaN,
            hasAccelerationSample ? smoothedAcceleration : Float.NaN,
            quietDurationMs,
            motionSequence
        );
    }

    private void recordMotion(long nowMs) {
        lastMotionMs = nowMs;
        motionSequence++;
    }

    private float smooth(float previous, float current, boolean hasPrevious) {
        if (!hasPrevious) {
            return current;
        }
        return config.motionSmoothingFactor * previous
            + (1f - config.motionSmoothingFactor) * current;
    }

    private static float magnitude(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}

final class MotionSnapshot {
    final boolean available;
    final boolean gyroscopeAvailable;
    final boolean accelerationAvailable;
    final boolean sampling;
    final boolean stable;
    final float gyroscopeRadPerSecond;
    final float linearAcceleration;
    final long quietDurationMs;
    final long motionSequence;

    MotionSnapshot(boolean available, boolean gyroscopeAvailable,
                   boolean accelerationAvailable, boolean sampling, boolean stable,
                   float gyroscopeRadPerSecond, float linearAcceleration,
                   long quietDurationMs, long motionSequence) {
        this.available = available;
        this.gyroscopeAvailable = gyroscopeAvailable;
        this.accelerationAvailable = accelerationAvailable;
        this.sampling = sampling;
        this.stable = stable;
        this.gyroscopeRadPerSecond = gyroscopeRadPerSecond;
        this.linearAcceleration = linearAcceleration;
        this.quietDurationMs = quietDurationMs;
        this.motionSequence = motionSequence;
    }
}
