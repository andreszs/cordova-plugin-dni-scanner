package com.crediser.plugins.dniscanner;

/** Small immutable session configuration; detector policy lives in its engine. */
final class ScannerConfig {
    static final int MIN_PREFERRED_CAPTURE_LONG_EDGE = 1280;
    static final int DEFAULT_PREFERRED_CAPTURE_LONG_EDGE = 1600;
    static final int MAX_PREFERRED_CAPTURE_LONG_EDGE = 2048;
    static final int MIN_OUTPUT_LONG_EDGE = 640;
    static final int DEFAULT_MAX_OUTPUT_LONG_EDGE = 1280;
    static final int MAX_OUTPUT_LONG_EDGE = 2048;
    static final int MIN_EFFECTIVE_DOCUMENT_LONG_EDGE = 0;
    static final int DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE = 0;
    static final int MAX_EFFECTIVE_DOCUMENT_LONG_EDGE = 4096;
    static final double MIN_STILL_SHARPNESS_VARIANCE = 1.0;
    static final double DEFAULT_MIN_STILL_SHARPNESS_VARIANCE = 115.0;
    static final double MAX_STILL_SHARPNESS_VARIANCE = 10000.0;
    static final float GUIDE_MARGIN_PERCENT = 6f;
    static final float CROP_PADDING_PERCENT = 6f;
    static final float MAX_RECTANGLE_DEVIATION_DEGREES = 1.5f;
    static final float MAX_SYMMETRIC_PERSPECTIVE_DEVIATION_DEGREES = 8f;
    static final float MAX_TRAPEZOID_SYMMETRY_ERROR_DEGREES = 2.5f;
    static final float MAX_IN_PLANE_ROTATION_DEGREES = 2f;

    static final ScannerConfig DEFAULT = new ScannerConfig(
        DEFAULT_PREFERRED_CAPTURE_LONG_EDGE,
        DEFAULT_MAX_OUTPUT_LONG_EDGE,
        DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE,
        DEFAULT_MIN_STILL_SHARPNESS_VARIANCE,
        ScanProfile.DNI
    );

    final ScanProfile profile;
    final int preferredCaptureLongEdge;
    final int maxOutputLongEdge;
    final int minEffectiveDocumentLongEdge;
    final double minStillSharpnessVariance;

    final int analysisMaxDimension = 512;
    final long analysisIntervalMs = 80L;
    final int stillDetectionMaxDimension = 640;
    final float expectedDocumentAspect = 85.60f / 53.98f;
    final float guideMarginFraction = GUIDE_MARGIN_PERCENT / 100f;
    final float cropPaddingFraction = CROP_PADDING_PERCENT / 100f;
    final float minimumCropIntegrityClearanceFraction = 0.006f;
    final float cropIntegrityGuardPixels = 1f;

    final int stableFrames = 4;
    final long stableDurationMs = 500L;
    final int geometryConfirmationFrames = 2;
    final long geometryConfirmationDurationMs = 240L;

    final float motionSmoothingFactor = 0.72f;
    final float maxGyroscopeRadPerSecond = 0.18f;
    final float maxGyroscopeSpikeRadPerSecond = 0.45f;
    final float maxLinearAcceleration = 0.34f;
    final float maxAccelerationSpike = 0.90f;
    final float accelerometerGravityFilterAlpha = 0.82f;
    final long motionStableDurationMs = 300L;
    final long motionSensorStartupTimeoutMs = 1000L;
    final long motionSensorStaleTimeoutMs = 750L;

    final long instructionStateConfirmationMs = 500L;
    final long instructionLossGraceMs = 300L;
    final long captureDelayMs = 180L;
    final long focusMeteringTimeoutMs = 700L;
    final long retryMessageDurationMs = 1000L;

    final double minSharpnessVariance = 55.0;
    final int jpegQuality = 95;
    final int recognitionWidth = 256;
    final int recognitionHeight = 160;
    final float minimumSideConfidence = 0.76f;
    final float minimumVariantConfidence = 0.72f;

    private ScannerConfig(int preferredCaptureLongEdge, int maxOutputLongEdge,
                          int minEffectiveDocumentLongEdge,
                          double minStillSharpnessVariance, ScanProfile profile) {
        if (preferredCaptureLongEdge < MIN_PREFERRED_CAPTURE_LONG_EDGE
            || preferredCaptureLongEdge > MAX_PREFERRED_CAPTURE_LONG_EDGE) {
            throw new IllegalArgumentException("Invalid preferred capture long edge");
        }
        if (maxOutputLongEdge < MIN_OUTPUT_LONG_EDGE
            || maxOutputLongEdge > MAX_OUTPUT_LONG_EDGE) {
            throw new IllegalArgumentException("Invalid maximum output long edge");
        }
        if (minEffectiveDocumentLongEdge < MIN_EFFECTIVE_DOCUMENT_LONG_EDGE
            || minEffectiveDocumentLongEdge > MAX_EFFECTIVE_DOCUMENT_LONG_EDGE) {
            throw new IllegalArgumentException("Invalid minimum effective document long edge");
        }
        if (!Double.isFinite(minStillSharpnessVariance)
            || minStillSharpnessVariance < MIN_STILL_SHARPNESS_VARIANCE
            || minStillSharpnessVariance > MAX_STILL_SHARPNESS_VARIANCE) {
            throw new IllegalArgumentException("Invalid minimum still sharpness variance");
        }
        this.preferredCaptureLongEdge = preferredCaptureLongEdge;
        this.maxOutputLongEdge = maxOutputLongEdge;
        this.minEffectiveDocumentLongEdge = minEffectiveDocumentLongEdge;
        this.minStillSharpnessVariance = minStillSharpnessVariance;
        this.profile = profile == null ? ScanProfile.DNI : profile;
    }

    static ScannerConfig withMaxOutputLongEdge(int maxOutputLongEdge) {
        return forSession(
            DEFAULT_PREFERRED_CAPTURE_LONG_EDGE,
            maxOutputLongEdge,
            DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE,
            DEFAULT_MIN_STILL_SHARPNESS_VARIANCE,
            ScanProfile.DNI
        );
    }

    static ScannerConfig forSession(int preferredCaptureLongEdge, int maxOutputLongEdge,
                                    ScanProfile profile) {
        return forSession(
            preferredCaptureLongEdge,
            maxOutputLongEdge,
            DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE,
            DEFAULT_MIN_STILL_SHARPNESS_VARIANCE,
            profile
        );
    }

    static ScannerConfig forSession(int preferredCaptureLongEdge, int maxOutputLongEdge,
                                    int minEffectiveDocumentLongEdge,
                                    double minStillSharpnessVariance,
                                    ScanProfile profile) {
        if (preferredCaptureLongEdge == DEFAULT_PREFERRED_CAPTURE_LONG_EDGE
            && maxOutputLongEdge == DEFAULT_MAX_OUTPUT_LONG_EDGE
            && minEffectiveDocumentLongEdge == DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE
            && minStillSharpnessVariance == DEFAULT_MIN_STILL_SHARPNESS_VARIANCE
            && profile == ScanProfile.DNI) {
            return DEFAULT;
        }
        return new ScannerConfig(
            preferredCaptureLongEdge,
            maxOutputLongEdge,
            minEffectiveDocumentLongEdge,
            minStillSharpnessVariance,
            profile
        );
    }
}
