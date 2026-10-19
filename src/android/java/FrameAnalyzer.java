package com.crediser.plugins.dniscanner;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

final class FrameAnalyzer implements ImageAnalysis.Analyzer {
    private static final String TAG = "DniScanner";
    interface Listener {
        void onAnalysis(AnalysisResult result, int frameWidth, int frameHeight);
        void onAnalysisFailure(Throwable error);
    }

    private final ScannerConfig config;
    private final ScanChecks checks;
    private final DocumentDetector detector;
    private final QualityEvaluator qualityEvaluator;
    private final PerspectiveTransformer cropGeometry;
    private final StabilityTracker stabilityTracker;
    private final EdgeOcclusionTracker edgeOcclusionTracker;
    private final GlareTracker glareTracker;
    private final Listener listener;
    private long lastAnalysisMs;
    private volatile boolean enabled = true;

    FrameAnalyzer(ScannerConfig config, ScanChecks checks, DetectionRegion detectionRegion,
                  Listener listener) {
        this.config = config;
        this.checks = checks;
        this.listener = listener;
        this.detector = new DocumentDetector(config, detectionRegion);
        this.qualityEvaluator = new QualityEvaluator(config);
        this.cropGeometry = new PerspectiveTransformer(config);
        this.stabilityTracker = new StabilityTracker(config);
        this.edgeOcclusionTracker = new EdgeOcclusionTracker(config);
        this.glareTracker = new GlareTracker(config);
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        long now = SystemClock.elapsedRealtime();
        if (!enabled || now - lastAnalysisMs < config.analysisIntervalMs) {
            image.close();
            return;
        }
        lastAnalysisMs = now;
        try {
            LumaFrame frame = LumaExtractor.fromImageProxy(image, config.analysisMaxDimension);
            DocumentDetection detection = detector.detect(frame);
            AnalysisResult result;
            if (detection == null) {
                stabilityTracker.reset();
                edgeOcclusionTracker.reset();
                glareTracker.reset();
                result = new AnalysisResult(
                    AnalysisResult.State.SEARCHING,
                    null,
                    null,
                    false,
                    false,
                    now
                );
            } else {
                frame = LumaExtractor.withColorFromImageProxy(image, frame);
                QualityMetrics quality = qualityEvaluator.evaluate(frame, detection.quad);
                boolean cropComplete = cropGeometry.hasSufficientSourceMargin(
                    detection.quad,
                    frame.width,
                    frame.height
                );
                AnalysisResult.State state = qualityEvaluator.classify(detection, quality, checks);
                state = edgeOcclusionTracker.apply(state);
                state = glareTracker.apply(state);
                if (state == AnalysisResult.State.READY && !cropComplete) {
                    state = AnalysisResult.State.CROP_INCOMPLETE;
                }
                boolean geometryValid = (!checks.documentSize
                    || (detection.areaFraction >= config.minDocumentArea
                        && detection.areaFraction <= config.maxDocumentArea))
                    && (!checks.centering || detection.centerOffset <= config.maxCenterOffset)
                    && (!checks.perspective
                        || quality.perspectiveScore >= config.minPerspectiveScore)
                    && cropComplete;
                boolean qualityValid = (!checks.sharpness
                    || quality.sharpness >= config.minSharpnessVariance)
                    && (!checks.brightness
                        || (quality.brightness >= config.minBrightness
                            && quality.brightness <= config.maxBrightness
                            && quality.darkFraction <= config.maxDarkFraction
                            && quality.clippedFraction <= config.maxClippedFraction))
                    && (!checks.glare
                        || (quality.glareFraction <= config.maxGlareFraction
                            && quality.glareBlobFraction <= config.maxGlareBlobFraction))
                    && (!checks.shadows || !qualityEvaluator.hasUnevenLighting(quality))
                    && (!checks.edgeOcclusion || !qualityEvaluator.hasEdgeOcclusion(quality));
                result = new AnalysisResult(
                    state,
                    detection,
                    quality,
                    geometryValid,
                    qualityValid,
                    now
                );
                // Geometric confirmation is always required. The stability
                // option only selects the longer hold and device-motion gate.
                result = stabilityTracker.apply(
                    result,
                    frame.width,
                    frame.height,
                    checks.stability
                );
            }
            listener.onAnalysis(result, frame.width, frame.height);
        } catch (Throwable error) {
            stabilityTracker.reset();
            edgeOcclusionTracker.reset();
            glareTracker.reset();
            Log.e(TAG, "Frame analysis failed", error);
            listener.onAnalysisFailure(error);
        } finally {
            image.close();
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stabilityTracker.reset();
            edgeOcclusionTracker.reset();
            glareTracker.reset();
        }
    }
}
