package com.crediser.plugins.dniscanner;

/** Centralized, resolution-independent scanner parameters. */
final class ScannerConfig {
    static final int MIN_OUTPUT_LONG_EDGE = 640;
    static final int DEFAULT_MAX_OUTPUT_LONG_EDGE = 1280;
    static final int MAX_OUTPUT_LONG_EDGE = 2048;
    static final float GUIDE_MARGIN_PERCENT = 3f;
    static final float CROP_PADDING_PERCENT = 3f;
    static final ScannerConfig DEFAULT = new ScannerConfig(
        DEFAULT_MAX_OUTPUT_LONG_EDGE,
        ScanProfile.DNI
    );

    final ScanProfile profile;

    final int analysisMaxDimension = 512;
    final long analysisIntervalMs = 80L;

    final float expectedDocumentAspect = 85.60f / 53.98f;
    final float minObservedAspect = 1.18f;
    final float maxObservedAspect = 2.25f;
    final float minDocumentArea = 0.18f;
    final float maxDocumentArea = 0.82f;
    final float maxCenterOffset = 0.24f;
    final float minDetectionScore = 0.38f;
    // The corrected JPEG deliberately includes a small safety margin. Losing a
    // document edge is more harmful than retaining a narrow strip of background.
    final float cropPaddingPercent;
    final float cropPaddingFraction;
    // Crop completeness is mandatory even when visual padding is configured as zero.
    // The detected card must have enough source-image clearance to prove that none of
    // its sides were truncated by ImageCapture or by a displaced inner quadrilateral.
    final float minimumCropIntegrityClearanceFraction = 0.015f;
    final float cropIntegrityGuardPixels = 2f;
    // A detected line can be a strong printed line just inside the physical
    // card. The still-image pass searches outward for a plausible enclosing edge.
    final float outwardEdgeSearchFraction = 0.10f;
    final float minimumOutwardEdgeSupport = 0.48f;
    final float minimumRelativeOutwardEdgeSupport = 0.68f;
    final float outwardEdgeDistanceBonus = 0.14f;

    final int houghAngleBins = 90;
    final int houghMaxLines = 40;
    final int houghMaxPairs = 48;
    final int houghRhoSuppression = 12;
    final int houghAngleSuppression = 2;
    final int edgeSampleStep = 2;
    final int minEdgeThreshold = 72;
    final float edgeThresholdStdFactor = 1.10f;

    final float minPerspectiveScore = 0.60f;
    final double minSharpnessVariance = 115.0;
    final float minBrightness = 52.0f;
    final float maxBrightness = 222.0f;
    final int darkPixelThreshold = 22;
    final int clippedPixelThreshold = 235;
    // Specular glare usually starts below true sensor clipping. Keep this
    // separate from clippedPixelThreshold so exposure and glare can be tuned
    // independently.
    final int glarePixelThreshold = 220;
    final int maxGlareLocalRange = 28;
    final int maxGlareColorfulness = 30;
    final int minGlareBrightnessContrast = 26;
    // A bright smooth component is glare only when it stands out from a ring
    // immediately around it. Pale DNI stock and large white printed panels do
    // not have this localized contrast, even though they satisfy the raw
    // brightness and smoothness tests.
    final int glareHaloSampleDistance = 8;
    final int minGlareHaloContrast = 34;
    final float largeGlareComponentFraction = 0.07f;
    final int minLargeGlareHaloContrast = 42;
    // Near-clipped highlights remain valid glare evidence on an already pale
    // card with a smaller halo contrast. Printed white panels are normally
    // below this luminance and still have to pass the stronger contrast gate.
    final int nearClippedGlareMean = 244;
    final int minNearClippedGlareHaloContrast = 30;
    final float glareDocumentEdgeInset = 0.025f;
    final int minEdgeGlareHaloContrast = 46;
    final int minGlareHaloSamples = 8;
    final float maxLocalizedGlareCoverage = 0.24f;
    final float maxDarkFraction = 0.32f;
    final float maxClippedFraction = 0.45f;
    // A connected smooth highlight is more significant than isolated white
    // print. These limits intentionally favor rejecting a questionable frame.
    final float maxGlareFraction;
    final float maxGlareBlobFraction;
    final int glareConfirmationFrames = 3;

    // Permissive diagnostics for downstream OCR. These values do not gate
    // automatic capture; enabled ScanChecks retain sole control of acceptance.
    final double minOcrSharpnessVariance = 55.0;
    final float minOcrBrightness = 35f;
    final float maxOcrBrightness = 238f;
    final float maxOcrDarkFraction = 0.50f;
    final float maxOcrClippedFraction = 0.60f;
    final float maxOcrGlareFraction = 0.28f;
    final float maxOcrGlareBlobFraction = 0.18f;

    // Uneven illumination is measured from robust background estimates over a
    // normalized card grid. Requiring both a broad tonal spread and a coherent
    // transition avoids treating portraits, barcodes or printed regions as shadows.
    final int illuminationGridColumns = 12;
    final int illuminationGridRows = 8;
    final int illuminationSamplesPerAxis = 8;
    final float illuminationGridInset = 0.04f;
    final float illuminationLowPercentile = 0.20f;
    final float illuminationHighPercentile = 0.80f;
    final int minShadowTileDifference = 18;
    final float maxIlluminationSpread;
    final float maxShadowBoundary;
    final float maxSevereShadowBoundary;

    // Hands/fingers are rejected without a general-purpose vision model. The
    // first signal looks for a sustained loss of contrast across a detected
    // card edge. The second is deliberately limited to the peripheral band
    // and looks for a long, strongly warm-colored obstruction.
    final int edgeOcclusionSamples = 32;
    final float edgeOcclusionInset = 0.055f;
    final int minVisibleEdgeContrast = 15;
    final float maxEdgeGapFraction = 0.27f;
    final int warmEdgeSegments = 24;
    final float warmEdgeBandFraction = 0.12f;
    final int minWarmEdgeDominance = 66;
    final float maxWarmEdgeRunFraction = 0.16f;
    final int skinEdgeSegments = 28;
    // Skin color alone is not sufficient: cream or aged card stock can fall
    // inside the same broad YCbCr range. A finger must form a coherent region
    // that continues from the inside to the outside of the detected edge.
    final float skinEdgeBandFraction = 0.075f;
    final int minSkinLikelihood = 22;
    final float minSkinSegmentCoverage = 0.22f;
    final int maxSkinCrossingLumaDifference = 46;
    final int maxSkinCrossingColorfulnessDifference = 42;
    final int maxSkinCrossingWarmthDifference = 36;
    // A moderate skin-colored crossing is not enough by itself because the
    // pale/pink substrate used by some DNI v2 backs can produce the same
    // chromatic signal. It must also coincide with a real loss of edge
    // contrast. Only a much longer crossing may block on its own.
    final float maxSkinEdgeRunFraction = 0.12f;
    final float maxUncorroboratedSkinEdgeRunFraction = 0.30f;
    final float skinCorroboratingEdgeGapFraction = 0.10f;
    // Preview observations are deliberately confirmed across several analyzed
    // frames. This prevents a single imperfect quadrilateral from being
    // presented to the user as a hand/finger obstruction.
    final int edgeOcclusionConfirmationFrames = 3;
    // A captured still is evaluated only once, so skin evidence needs to be
    // substantially stronger than in the preview, or be corroborated by a
    // meaningful loss of contrast along the same document boundary.
    final float maxStillSkinEdgeRunFraction = 0.34f;
    final float stillSkinCorroboratingEdgeGapFraction = 0.10f;
    final long occlusionMessageDurationMs = 2200L;

    final float maxCornerMotion = 0.018f;
    final float maxAreaChange = 0.035f;
    // Elapsed time remains the primary stability requirement. Three frames avoid
    // turning that 500 ms hold into several seconds on slow processors.
    final int stableFrames = 3;
    final long stableDurationMs = 500L;
    // Even when optional motion stability is disabled, never capture from one
    // isolated quadrilateral. This is a detector-integrity requirement, not a
    // user-selectable motion-quality check.
    final int geometryConfirmationFrames = 3;
    final long geometryConfirmationDurationMs = 240L;

    // Device-motion gating complements corner tracking. Gyroscope rotation is
    // the strongest blur predictor; linear acceleration catches translation and
    // hand shake. Raw-spike thresholds react immediately while the smoothed
    // thresholds avoid making the scanner sensitive to normal sensor noise.
    final float motionSmoothingFactor = 0.72f;
    final float maxGyroscopeRadPerSecond = 0.12f;
    final float maxGyroscopeSpikeRadPerSecond = 0.34f;
    final float maxLinearAcceleration = 0.34f;
    final float maxAccelerationSpike = 0.90f;
    final float accelerometerGravityFilterAlpha = 0.82f;
    final long motionStableDurationMs = 300L;
    final long motionSensorStartupTimeoutMs = 1000L;
    final long motionSensorStaleTimeoutMs = 750L;
    final long motionRetryMessageDurationMs = 450L;

    final long captureDelayMs = 180L;
    final long retryMessageDurationMs = 650L;
    final long wrongSideMessageDurationMs = 3000L;

    final int maxOutputLongEdge;
    final float guideMarginPercent;
    final float guideMarginFraction;
    final int jpegQuality = 95;
    final int stillDetectionMaxDimension = 640;

    final int recognitionWidth = 256;
    final int recognitionHeight = 160;
    // Prefer unknown/null over a plausible but incorrect layout classification.
    final float minimumSideConfidence = 0.76f;
    final float minimumVariantConfidence = 0.72f;

    private ScannerConfig(int maxOutputLongEdge, ScanProfile profile) {
        if (maxOutputLongEdge < MIN_OUTPUT_LONG_EDGE
            || maxOutputLongEdge > MAX_OUTPUT_LONG_EDGE) {
            throw new IllegalArgumentException("Invalid maximum output long edge");
        }
        this.maxOutputLongEdge = maxOutputLongEdge;
        this.profile = profile == null ? ScanProfile.DNI : profile;
        this.guideMarginPercent = GUIDE_MARGIN_PERCENT;
        this.guideMarginFraction = GUIDE_MARGIN_PERCENT / 100f;
        this.cropPaddingPercent = CROP_PADDING_PERCENT;
        this.cropPaddingFraction = CROP_PADDING_PERCENT / 100f;
        if (this.profile == ScanProfile.GENERIC_CARD) {
            // Generic cards can contain large white panels, gradients and strong
            // brand colors. Preserve only severe glare/shadow rejection here.
            this.maxGlareFraction = 0.23f;
            this.maxGlareBlobFraction = 0.20f;
            this.maxIlluminationSpread = 0.45f;
            this.maxShadowBoundary = 0.36f;
            this.maxSevereShadowBoundary = 0.48f;
        } else {
            this.maxGlareFraction = 0.14f;
            this.maxGlareBlobFraction = 0.09f;
            this.maxIlluminationSpread = 0.22f;
            this.maxShadowBoundary = 0.075f;
            this.maxSevereShadowBoundary = 0.21f;
        }
    }

    static ScannerConfig withMaxOutputLongEdge(int maxOutputLongEdge) {
        return forSession(maxOutputLongEdge, ScanProfile.DNI);
    }

    static ScannerConfig forSession(int maxOutputLongEdge, ScanProfile profile) {
        if (maxOutputLongEdge == DEFAULT_MAX_OUTPUT_LONG_EDGE
            && profile == ScanProfile.DNI) {
            return DEFAULT;
        }
        return new ScannerConfig(maxOutputLongEdge, profile);
    }
}
