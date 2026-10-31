package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintains one coherent document track. Full contour detection corrects drift;
 * pyramidal Lucas-Kanade flow bridges the intervening frames. Stability is
 * measured from corner displacement and elapsed time, never from an arbitrary
 * number of identical callbacks.
 */
final class TemporalDocumentTracker implements AutoCloseable {
    private static final int DETECTION_PERIOD = 3;
    private static final int MAX_FLOW_ONLY_FRAMES = 3;
    private static final float MAX_RECONCILE_DISTANCE = 0.055f;
    private static final float MAX_RECONCILE_CORNER_DISTANCE = 0.075f;
    private static final float MIN_TRACK_AREA_RATIO = 0.86f;
    private static final float MAX_TRACK_AREA_RATIO = 1.16f;
    private static final float MAX_TRACK_ASPECT_CHANGE = 0.16f;
    private static final float MIN_OUTER_RECOVERY_AREA_GAIN = 1.08f;
    private static final float MIN_OUTER_RECOVERY_SCORE = 0.60f;
    private static final float MAX_STABLE_DISTANCE = 0.018f;
    private static final float MAX_STABLE_AREA_CHANGE = 0.055f;

    private final ScannerConfig config;
    private final ScanChecks checks;
    private final OpenCvDocumentEngine engine;
    private final PerspectiveTransformer cropGeometry;

    private Mat previousGray;
    private DocumentQuad previousRaw;
    private DocumentQuad smoothed;
    private DocumentDetection lastDetection;
    private int frameIndex;
    private int flowOnlyFrames;
    private int observations;
    private long stableSinceMs;

    TemporalDocumentTracker(ScannerConfig config, ScanChecks checks,
                            DetectionRegion detectionRegion) {
        this.config = config;
        this.checks = checks;
        this.engine = new OpenCvDocumentEngine(config, detectionRegion);
        this.cropGeometry = new PerspectiveTransformer(config);
    }

    synchronized AnalysisResult update(LumaFrame frame, long nowMs) {
        Mat current = engine.gray(frame);
        try {
            DocumentQuad flow = trackWithOpticalFlow(current, frame.width, frame.height);
            boolean runDetection = smoothed == null || frameIndex++ % DETECTION_PERIOD == 0
                || flow == null || flowOnlyFrames >= MAX_FLOW_ONLY_FRAMES;
            DocumentDetection detected = runDetection
                ? engine.detect(current, frame.width, frame.height,
                    flow != null ? flow : smoothed)
                : null;

            DocumentDetection candidate = reconcile(
                detected, flow, frame.width, frame.height
            );
            if (candidate == null) {
                clearTrack(false);
                remember(current);
                return new AnalysisResult(
                    AnalysisResult.State.SEARCHING, null, null,
                    false, false, nowMs
                );
            }

            DocumentQuad raw = candidate.quad;
            DocumentQuad nextSmoothed = smoothed == null
                ? raw : OpenCvDocumentEngine.blend(smoothed, raw, 0.42f);
            boolean stableGeometry = updateStability(
                nextSmoothed, frame.width, frame.height, nowMs
            );
            smoothed = nextSmoothed;
            candidate = metricsFor(smoothed, candidate.score, frame.width, frame.height);
            lastDetection = candidate;

            QualityMetrics quality = engine.assess(current, candidate.quad);
            boolean cropComplete = cropGeometry.hasSufficientSourceMargin(
                candidate.quad, frame.width, frame.height
            );
            AnalysisResult.State state = engine.classify(candidate, quality, checks);
            if (state == AnalysisResult.State.READY && !cropComplete) {
                state = AnalysisResult.State.CROP_INCOMPLETE;
            }

            boolean geometryValid = (!checks.documentSize
                || (candidate.areaFraction >= 0.14f && candidate.areaFraction <= 0.88f))
                && (!checks.centering || candidate.centerOffset <= 0.34f)
                && candidate.quad.maximumInPlaneRotationDegrees()
                    <= ScannerConfig.MAX_IN_PLANE_ROTATION_DEGREES
                && candidate.quad.hasAcceptablePerspective(
                    ScannerConfig.MAX_RECTANGLE_DEVIATION_DEGREES,
                    ScannerConfig.MAX_SYMMETRIC_PERSPECTIVE_DEVIATION_DEGREES,
                    ScannerConfig.MAX_TRAPEZOID_SYMMETRY_ERROR_DEGREES
                )
                && cropComplete;
            boolean qualityValid = state == AnalysisResult.State.READY;
            long stableDuration = stableSinceMs == 0L ? 0L : nowMs - stableSinceMs;
            int requiredObservations = checks.stability ? 4 : 2;
            long requiredDuration = checks.stability
                ? config.stableDurationMs : config.geometryConfirmationDurationMs;
            if (state == AnalysisResult.State.READY
                && (!stableGeometry || observations < requiredObservations
                    || stableDuration < requiredDuration)) {
                state = AnalysisResult.State.HOLD_STILL;
                qualityValid = true;
            }

            AnalysisResult result = new AnalysisResult(
                state, candidate, quality, geometryValid, qualityValid, nowMs
            );
            result.stableFrameCount = observations;
            result.stableDurationMs = stableDuration;
            previousRaw = raw;
            remember(current);
            return result;
        } finally {
            current.release();
        }
    }

    private DocumentDetection reconcile(DocumentDetection detected, DocumentQuad flow,
                                        int width, int height) {
        if (detected != null) {
            DocumentQuad quad = detected.quad;
            if (flow != null) {
                DocumentDetection flowDetection = metricsFor(
                    flow,
                    lastDetection == null ? 0.45f : lastDetection.score,
                    width,
                    height
                );
                if (isConsistentTrackUpdate(detected, flowDetection, width, height)) {
                    quad = OpenCvDocumentEngine.blend(flow, detected.quad, 0.72f);
                    flowOnlyFrames = 0;
                } else if (isOuterContourRecovery(detected, flowDetection)) {
                    // A weak first pass can lock onto a printed/security contour inside
                    // a DNI v3. Permit a materially larger, credible contour to replace
                    // it, but restart stability so it cannot trigger an immediate shot.
                    resetStability();
                    flowOnlyFrames = 0;
                } else {
                    // Keep the coherent optical-flow track. A single inner contour must
                    // not move one corner into the card and reset the capture window.
                    flowOnlyFrames++;
                    return metricsFor(
                        flow,
                        Math.max(0.45f, flowDetection.score - 0.02f),
                        width,
                        height
                    );
                }
            } else {
                flowOnlyFrames = 0;
            }
            return metricsFor(quad, detected.score, width, height);
        }
        if (flow != null && lastDetection != null
            && flowOnlyFrames++ < MAX_FLOW_ONLY_FRAMES) {
            return metricsFor(
                flow,
                Math.max(0.45f, lastDetection.score - 0.06f * flowOnlyFrames),
                width,
                height
            );
        }
        return null;
    }

    private boolean isConsistentTrackUpdate(DocumentDetection detected,
                                            DocumentDetection tracked,
                                            int width, int height) {
        float rmsDistance = OpenCvDocumentEngine.quadDistance(
            detected.quad, tracked.quad, width, height
        );
        float maximumCornerDistance = OpenCvDocumentEngine.quadMaxCornerDistance(
            detected.quad, tracked.quad, width, height
        );
        float areaRatio = detected.areaFraction / Math.max(0.0001f, tracked.areaFraction);
        float aspectChange = Math.abs(detected.observedAspect - tracked.observedAspect)
            / Math.max(0.01f, tracked.observedAspect);
        return rmsDistance <= MAX_RECONCILE_DISTANCE
            && maximumCornerDistance <= MAX_RECONCILE_CORNER_DISTANCE
            && areaRatio >= MIN_TRACK_AREA_RATIO
            && areaRatio <= MAX_TRACK_AREA_RATIO
            && aspectChange <= MAX_TRACK_ASPECT_CHANGE;
    }

    private boolean isOuterContourRecovery(DocumentDetection detected,
                                           DocumentDetection tracked) {
        return detected.areaFraction
                >= tracked.areaFraction * MIN_OUTER_RECOVERY_AREA_GAIN
            && detected.score >= MIN_OUTER_RECOVERY_SCORE;
    }

    private DocumentQuad trackWithOpticalFlow(Mat current, int width, int height) {
        if (previousGray == null || smoothed == null
            || previousGray.size().width != current.size().width
            || previousGray.size().height != current.size().height) {
            return null;
        }
        MatOfPoint2f previousPoints = OpenCvDocumentEngine.points(smoothed);
        MatOfPoint2f nextPoints = new MatOfPoint2f();
        MatOfByte status = new MatOfByte();
        MatOfFloat error = new MatOfFloat();
        try {
            Video.calcOpticalFlowPyrLK(
                previousGray, current, previousPoints, nextPoints, status, error
            );
            byte[] valid = status.toArray();
            org.opencv.core.Point[] tracked = nextPoints.toArray();
            if (valid.length != 4 || tracked.length != 4) {
                return null;
            }
            List<PointF> points = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                if (valid[index] == 0 || tracked[index].x < 1 || tracked[index].y < 1
                    || tracked[index].x > width - 2 || tracked[index].y > height - 2) {
                    return null;
                }
                points.add(new PointF((float) tracked[index].x, (float) tracked[index].y));
            }
            DocumentQuad quad = DocumentQuad.ordered(points);
            if (quad == null || quad.area() < width * height * 0.08f) {
                return null;
            }
            return quad;
        } finally {
            previousPoints.release();
            nextPoints.release();
            status.release();
            error.release();
        }
    }

    private boolean updateStability(DocumentQuad current, int width, int height, long nowMs) {
        if (previousRaw == null || stableSinceMs <= 0L) {
            observations = 1;
            stableSinceMs = nowMs;
            return false;
        }
        float movement = OpenCvDocumentEngine.quadDistance(
            previousRaw, current, width, height
        );
        float areaChange = Math.abs(current.area() - previousRaw.area())
            / Math.max(1f, previousRaw.area());
        if (movement <= MAX_STABLE_DISTANCE && areaChange <= MAX_STABLE_AREA_CHANGE) {
            observations++;
            return true;
        }
        if (movement <= MAX_STABLE_DISTANCE * 1.8f
            && areaChange <= MAX_STABLE_AREA_CHANGE * 1.8f) {
            observations = Math.max(1, observations - 1);
            stableSinceMs = Math.max(stableSinceMs, nowMs - 180L);
            return false;
        }
        resetStability();
        stableSinceMs = nowMs;
        observations = 1;
        return false;
    }

    private DocumentDetection metricsFor(DocumentQuad quad, float score,
                                         int width, int height) {
        float area = quad.area() / Math.max(1f, width * (float) height);
        PointF center = quad.center();
        float centerOffset = (float) Math.hypot(
            (center.x - width * 0.5f) / Math.max(1f, width * 0.5f),
            (center.y - height * 0.5f) / Math.max(1f, height * 0.5f)
        );
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        float aspect = (quad.topWidth() + quad.bottomWidth()) * 0.5f
            / Math.max(1f, averageHeight);
        return new DocumentDetection(quad, score, area, centerOffset, aspect);
    }

    private void remember(Mat current) {
        if (previousGray != null) {
            previousGray.release();
        }
        previousGray = current.clone();
    }

    private void resetStability() {
        previousRaw = null;
        observations = 0;
        stableSinceMs = 0L;
    }

    synchronized void reset() {
        clearTrack(true);
    }

    private void clearTrack(boolean releaseFrame) {
        smoothed = null;
        lastDetection = null;
        flowOnlyFrames = 0;
        frameIndex = 0;
        resetStability();
        if (releaseFrame && previousGray != null) {
            previousGray.release();
            previousGray = null;
        }
    }

    @Override
    public void close() {
        reset();
    }
}
