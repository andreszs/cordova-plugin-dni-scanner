package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class LumaFrame {
    final int width;
    final int height;
    final byte[] data;
    final byte[] colorfulness;
    final byte[] warmth;
    final byte[] skinLikelihood;

    LumaFrame(int width, int height, byte[] data) {
        this(width, height, data, null, null);
    }

    LumaFrame(int width, int height, byte[] data, byte[] colorfulness) {
        this(width, height, data, colorfulness, null);
    }

    LumaFrame(int width, int height, byte[] data, byte[] colorfulness, byte[] warmth) {
        this(width, height, data, colorfulness, warmth, null);
    }

    LumaFrame(int width, int height, byte[] data, byte[] colorfulness, byte[] warmth,
              byte[] skinLikelihood) {
        this.width = width;
        this.height = height;
        this.data = data;
        this.colorfulness = colorfulness;
        this.warmth = warmth;
        this.skinLikelihood = skinLikelihood;
    }

    int unsignedAt(int x, int y) {
        return data[y * width + x] & 0xff;
    }

    int colorfulnessAt(int x, int y) {
        return colorfulness == null ? -1 : colorfulness[y * width + x] & 0xff;
    }

    int warmthAt(int x, int y) {
        return warmth == null ? -1 : warmth[y * width + x] & 0xff;
    }

    int skinLikelihoodAt(int x, int y) {
        return skinLikelihood == null ? -1 : skinLikelihood[y * width + x] & 0xff;
    }
}

final class DocumentQuad {
    final PointF topLeft;
    final PointF topRight;
    final PointF bottomRight;
    final PointF bottomLeft;

    DocumentQuad(PointF topLeft, PointF topRight, PointF bottomRight, PointF bottomLeft) {
        this.topLeft = new PointF(topLeft.x, topLeft.y);
        this.topRight = new PointF(topRight.x, topRight.y);
        this.bottomRight = new PointF(bottomRight.x, bottomRight.y);
        this.bottomLeft = new PointF(bottomLeft.x, bottomLeft.y);
    }

    List<PointF> points() {
        return Arrays.asList(topLeft, topRight, bottomRight, bottomLeft);
    }

    DocumentQuad scaled(float scaleX, float scaleY) {
        return new DocumentQuad(
            new PointF(topLeft.x * scaleX, topLeft.y * scaleY),
            new PointF(topRight.x * scaleX, topRight.y * scaleY),
            new PointF(bottomRight.x * scaleX, bottomRight.y * scaleY),
            new PointF(bottomLeft.x * scaleX, bottomLeft.y * scaleY)
        );
    }

    float area() {
        PointF[] points = { topLeft, topRight, bottomRight, bottomLeft };
        float sum = 0f;
        for (int i = 0; i < points.length; i++) {
            PointF a = points[i];
            PointF b = points[(i + 1) % points.length];
            sum += a.x * b.y - b.x * a.y;
        }
        return Math.abs(sum) * 0.5f;
    }

    PointF center() {
        return new PointF(
            (topLeft.x + topRight.x + bottomRight.x + bottomLeft.x) * 0.25f,
            (topLeft.y + topRight.y + bottomRight.y + bottomLeft.y) * 0.25f
        );
    }

    float topWidth() {
        return distance(topLeft, topRight);
    }

    float bottomWidth() {
        return distance(bottomLeft, bottomRight);
    }

    float leftHeight() {
        return distance(topLeft, bottomLeft);
    }

    float rightHeight() {
        return distance(topRight, bottomRight);
    }

    float rectangularityScore() {
        float widthBalance = ratioScore(topWidth(), bottomWidth());
        float heightBalance = ratioScore(leftHeight(), rightHeight());
        float diagonalBalance = ratioScore(
            distance(topLeft, bottomRight),
            distance(topRight, bottomLeft)
        );
        float angleScore = 0f;
        PointF[] corners = { topLeft, topRight, bottomRight, bottomLeft };
        for (int index = 0; index < corners.length; index++) {
            PointF previous = corners[(index + 3) % corners.length];
            PointF current = corners[index];
            PointF next = corners[(index + 1) % corners.length];
            float ax = previous.x - current.x;
            float ay = previous.y - current.y;
            float bx = next.x - current.x;
            float by = next.y - current.y;
            float denominator = (float) Math.sqrt(
                (ax * ax + ay * ay) * (bx * bx + by * by)
            );
            float cosine = denominator < 1e-4f
                ? 1f
                : Math.abs((ax * bx + ay * by) / denominator);
            angleScore += Math.max(0f, 1f - cosine);
        }
        angleScore /= corners.length;
        return clamp01(
            0.30f * widthBalance
                + 0.25f * heightBalance
                + 0.20f * diagonalBalance
                + 0.25f * angleScore
        );
    }

    float maximumPerspectiveDeviationDegrees() {
        float[] deviations = cornerAngleDeviationsDegrees();
        if (deviations == null) {
            return 90f;
        }
        float maximum = 0f;
        for (float deviation : deviations) {
            maximum = Math.max(maximum, Math.abs(deviation));
        }
        return maximum;
    }

    float maximumInPlaneRotationDegrees() {
        return Math.max(
            Math.abs(lineRotationDegrees(topLeft, topRight)),
            Math.abs(lineRotationDegrees(bottomLeft, bottomRight))
        );
    }

    boolean hasAcceptablePerspective(float freeLimit, float symmetricLimit,
                                     float symmetryTolerance) {
        float[] deviations = cornerAngleDeviationsDegrees();
        if (deviations == null) {
            return false;
        }
        float maximum = 0f;
        for (float deviation : deviations) {
            maximum = Math.max(maximum, Math.abs(deviation));
        }
        if (maximum <= freeLimit) {
            return true;
        }
        if (maximum > symmetricLimit) {
            return false;
        }

        // A clean projected rectangle has two adjacent, similarly opened
        // corners and two adjacent, similarly closed corners. Evaluate both
        // possible trapezoid axes so in-plane rotation remains irrelevant.
        float horizontalAxisError = maximum(
            Math.abs(deviations[0] - deviations[1]),
            Math.abs(deviations[2] - deviations[3]),
            Math.abs(deviations[0] + deviations[3]),
            Math.abs(deviations[1] + deviations[2])
        );
        float verticalAxisError = maximum(
            Math.abs(deviations[1] - deviations[2]),
            Math.abs(deviations[3] - deviations[0]),
            Math.abs(deviations[1] + deviations[0]),
            Math.abs(deviations[2] + deviations[3])
        );
        return Math.min(horizontalAxisError, verticalAxisError) <= symmetryTolerance;
    }

    private float[] cornerAngleDeviationsDegrees() {
        PointF[] corners = { topLeft, topRight, bottomRight, bottomLeft };
        float[] deviations = new float[corners.length];
        for (int index = 0; index < corners.length; index++) {
            PointF previous = corners[(index + 3) % corners.length];
            PointF current = corners[index];
            PointF next = corners[(index + 1) % corners.length];
            float ax = previous.x - current.x;
            float ay = previous.y - current.y;
            float bx = next.x - current.x;
            float by = next.y - current.y;
            float denominator = (float) Math.sqrt(
                (ax * ax + ay * ay) * (bx * bx + by * by)
            );
            if (denominator < 1e-4f) {
                return null;
            }
            float cosine = Math.max(-1f, Math.min(1f, (ax * bx + ay * by) / denominator));
            float angle = (float) Math.toDegrees(Math.acos(cosine));
            deviations[index] = angle - 90f;
        }
        return deviations;
    }

    private static float maximum(float first, float second, float third, float fourth) {
        return Math.max(Math.max(first, second), Math.max(third, fourth));
    }

    private static float lineRotationDegrees(PointF start, PointF end) {
        float angle = (float) Math.toDegrees(Math.atan2(end.y - start.y, end.x - start.x));
        while (angle > 90f) angle -= 180f;
        while (angle < -90f) angle += 180f;
        return angle;
    }

    private static float ratioScore(float first, float second) {
        float maximum = Math.max(first, second);
        return maximum < 1e-4f ? 0f : Math.min(first, second) / maximum;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static float distance(PointF a, PointF b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static DocumentQuad ordered(List<PointF> raw) {
        if (raw == null || raw.size() != 4) {
            return null;
        }
        PointF center = new PointF();
        for (PointF point : raw) {
            center.x += point.x;
            center.y += point.y;
        }
        center.x /= 4f;
        center.y /= 4f;

        List<PointF> sorted = new ArrayList<>(raw);
        Collections.sort(sorted, (first, second) -> Double.compare(
            Math.atan2(first.y - center.y, first.x - center.x),
            Math.atan2(second.y - center.y, second.x - center.x)
        ));

        int topLeftIndex = 0;
        float minSum = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float sum = sorted.get(i).x + sorted.get(i).y;
            if (sum < minSum) {
                minSum = sum;
                topLeftIndex = i;
            }
        }

        PointF[] ordered = new PointF[4];
        for (int i = 0; i < 4; i++) {
            ordered[i] = sorted.get((topLeftIndex + i) % 4);
        }
        DocumentQuad result = new DocumentQuad(ordered[0], ordered[1], ordered[2], ordered[3]);
        if (signedCross(result.topLeft, result.topRight, result.bottomRight) < 0f) {
            result = new DocumentQuad(ordered[0], ordered[3], ordered[2], ordered[1]);
        }
        return result;
    }

    private static float signedCross(PointF a, PointF b, PointF c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }
}

/**
 * Maps the visible guide to analyzed and captured frames using the same
 * FILL_CENTER transform used by PreviewView and ScannerOverlayView.
 */
final class DetectionRegion {
    final float viewWidth;
    final float viewHeight;
    final float guideLeft;
    final float guideTop;
    final float guideRight;
    final float guideBottom;
    final float marginFraction;

    DetectionRegion(float viewWidth, float viewHeight, float guideLeft, float guideTop,
                    float guideRight, float guideBottom, float marginFraction) {
        if (viewWidth <= 0f || viewHeight <= 0f
            || guideRight <= guideLeft || guideBottom <= guideTop) {
            throw new IllegalArgumentException("Invalid detection region dimensions");
        }
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.guideLeft = guideLeft;
        this.guideTop = guideTop;
        this.guideRight = guideRight;
        this.guideBottom = guideBottom;
        this.marginFraction = Math.max(0f, marginFraction);
    }

    boolean contains(DocumentQuad quad, int frameWidth, int frameHeight) {
        if (quad == null || frameWidth <= 0 || frameHeight <= 0) {
            return false;
        }
        float scale = fillCenterScale(frameWidth, frameHeight);
        float offsetX = (viewWidth - frameWidth * scale) * 0.5f;
        float offsetY = (viewHeight - frameHeight * scale) * 0.5f;
        float margin = Math.min(guideRight - guideLeft, guideBottom - guideTop)
            * marginFraction;
        float allowedLeft = Math.max(0f, guideLeft - margin);
        float allowedTop = Math.max(0f, guideTop - margin);
        float allowedRight = Math.min(viewWidth, guideRight + margin);
        float allowedBottom = Math.min(viewHeight, guideBottom + margin);
        for (PointF point : quad.points()) {
            float viewX = offsetX + point.x * scale;
            float viewY = offsetY + point.y * scale;
            if (viewX < allowedLeft || viewX > allowedRight
                || viewY < allowedTop || viewY > allowedBottom) {
                return false;
            }
        }
        return true;
    }

    float targetAreaFraction(int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return 0.47f;
        }
        float scale = fillCenterScale(frameWidth, frameHeight);
        float guideWidthInFrame = (guideRight - guideLeft) / scale;
        float guideHeightInFrame = (guideBottom - guideTop) / scale;
        float fraction = guideWidthInFrame * guideHeightInFrame
            / (frameWidth * (float) frameHeight);
        return Math.max(0.01f, Math.min(0.99f, fraction));
    }

    float marginPixels() {
        return Math.min(guideRight - guideLeft, guideBottom - guideTop) * marginFraction;
    }

    float[] frameBounds(int frameWidth, int frameHeight) {
        float scale = fillCenterScale(frameWidth, frameHeight);
        float offsetX = (viewWidth - frameWidth * scale) * 0.5f;
        float offsetY = (viewHeight - frameHeight * scale) * 0.5f;
        float margin = Math.min(guideRight - guideLeft, guideBottom - guideTop)
            * marginFraction;
        // The pale-edge fallback must search the same outward-tolerant region
        // accepted by contains(); otherwise the tolerance disappears precisely
        // on white backgrounds where that fallback is needed most.
        float allowedLeft = Math.max(0f, guideLeft - margin);
        float allowedTop = Math.max(0f, guideTop - margin);
        float allowedRight = Math.min(viewWidth, guideRight + margin);
        float allowedBottom = Math.min(viewHeight, guideBottom + margin);
        return new float[] {
            Math.max(1f, (allowedLeft - offsetX) / scale),
            Math.max(1f, (allowedTop - offsetY) / scale),
            Math.min(frameWidth - 2f, (allowedRight - offsetX) / scale),
            Math.min(frameHeight - 2f, (allowedBottom - offsetY) / scale)
        };
    }

    private float fillCenterScale(int frameWidth, int frameHeight) {
        return Math.max(viewWidth / frameWidth, viewHeight / frameHeight);
    }
}

final class DocumentDetection {
    final DocumentQuad quad;
    final float score;
    final float areaFraction;
    final float centerOffset;
    final float observedAspect;

    DocumentDetection(DocumentQuad quad, float score, float areaFraction,
                      float centerOffset, float observedAspect) {
        this.quad = quad;
        this.score = score;
        this.areaFraction = areaFraction;
        this.centerOffset = centerOffset;
        this.observedAspect = observedAspect;
    }
}

final class QualityMetrics {
    final double sharpness;
    final float brightness;
    final float darkFraction;
    final float clippedFraction;
    final float glareFraction;
    final float glareBlobFraction;
    final float perspectiveScore;
    final float edgeGapFraction;
    final float warmEdgeRunFraction;
    final float illuminationSpread;
    final float shadowBoundary;
    final float skinEdgeRunFraction;
    QualityMetrics(double sharpness, float brightness, float darkFraction,
                   float clippedFraction, float glareFraction, float glareBlobFraction,
                   float perspectiveScore) {
        this(sharpness, brightness, darkFraction, clippedFraction, glareFraction,
            glareBlobFraction, perspectiveScore, 0f, 0f, 0f, 0f, 0f);
    }

    QualityMetrics(double sharpness, float brightness, float darkFraction,
                   float clippedFraction, float glareFraction, float glareBlobFraction,
                   float perspectiveScore, float edgeGapFraction,
                   float warmEdgeRunFraction) {
        this(sharpness, brightness, darkFraction, clippedFraction, glareFraction,
            glareBlobFraction, perspectiveScore, edgeGapFraction, warmEdgeRunFraction,
            0f, 0f, 0f);
    }

    QualityMetrics(double sharpness, float brightness, float darkFraction,
                   float clippedFraction, float glareFraction, float glareBlobFraction,
                   float perspectiveScore, float edgeGapFraction,
                   float warmEdgeRunFraction, float illuminationSpread,
                   float shadowBoundary) {
        this(sharpness, brightness, darkFraction, clippedFraction, glareFraction,
            glareBlobFraction, perspectiveScore, edgeGapFraction, warmEdgeRunFraction,
            illuminationSpread, shadowBoundary, 0f);
    }

    QualityMetrics(double sharpness, float brightness, float darkFraction,
                   float clippedFraction, float glareFraction, float glareBlobFraction,
                   float perspectiveScore, float edgeGapFraction,
                   float warmEdgeRunFraction, float illuminationSpread,
                   float shadowBoundary, float skinEdgeRunFraction) {
        this.sharpness = sharpness;
        this.brightness = brightness;
        this.darkFraction = darkFraction;
        this.clippedFraction = clippedFraction;
        this.glareFraction = glareFraction;
        this.glareBlobFraction = glareBlobFraction;
        this.perspectiveScore = perspectiveScore;
        this.edgeGapFraction = edgeGapFraction;
        this.warmEdgeRunFraction = warmEdgeRunFraction;
        this.illuminationSpread = illuminationSpread;
        this.shadowBoundary = shadowBoundary;
        this.skinEdgeRunFraction = skinEdgeRunFraction;
    }
}

final class ScanChecks {
    final boolean documentSize;
    final boolean centering;
    final boolean perspective;
    final boolean stability;
    final boolean sharpness;
    final boolean brightness;
    final boolean glare;
    final boolean shadows;
    final boolean edgeOcclusion;

    ScanChecks(boolean documentSize, boolean centering, boolean perspective,
               boolean stability, boolean sharpness, boolean brightness,
               boolean glare, boolean shadows, boolean edgeOcclusion) {
        this.documentSize = documentSize;
        this.centering = centering;
        this.perspective = perspective;
        this.stability = stability;
        this.sharpness = sharpness;
        this.brightness = brightness;
        this.glare = glare;
        this.shadows = shadows;
        this.edgeOcclusion = edgeOcclusion;
    }

    static ScanChecks defaults() {
        return new ScanChecks(true, true, true, true, true, true, true, true, true);
    }
}

enum SideValidationMode {
    OFF("off"),
    ADVISORY("advisory"),
    STRICT("strict");

    final String value;

    SideValidationMode(String value) {
        this.value = value;
    }

    static SideValidationMode fromValue(String value) {
        for (SideValidationMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return null;
    }
}

enum ScanProfile {
    DNI("dni"),
    GENERIC_CARD("genericCard");

    final String value;

    ScanProfile(String value) {
        this.value = value;
    }

    static ScanProfile fromValue(String value) {
        for (ScanProfile profile : values()) {
            if (profile.value.equals(value)) {
                return profile;
            }
        }
        return null;
    }
}

final class AnalysisResult {
    enum Source {
        PREVIEW,
        STILL
    }

    enum State {
        SEARCHING,
        MOVE_CLOSER,
        MOVE_AWAY,
        CENTER_DOCUMENT,
        ADJUST_PERSPECTIVE,
        IMPROVE_LIGHTING,
        REDUCE_LIGHTING,
        AVOID_GLARE,
        UNEVEN_LIGHTING,
        EDGE_OCCLUDED,
        CROP_INCOMPLETE,
        HOLD_STILL,
        WAITING_FOR_FOCUS,
        READY,
        FOCUSING,
        CAPTURING,
        PROCESSING,
        RETRYING,
        SUCCESS
    }

    final State state;
    final DocumentDetection detection;
    final QualityMetrics quality;
    final boolean geometryValid;
    final boolean qualityValid;
    final long timestampMs;
    final Source source;
    int stableFrameCount;
    long stableDurationMs;
    MotionSnapshot motion;

    AnalysisResult(State state, DocumentDetection detection, QualityMetrics quality,
                   boolean geometryValid, boolean qualityValid, long timestampMs) {
        this(state, detection, quality, geometryValid, qualityValid, timestampMs,
            Source.PREVIEW);
    }

    AnalysisResult(State state, DocumentDetection detection, QualityMetrics quality,
                   boolean geometryValid, boolean qualityValid, long timestampMs,
                   Source source) {
        this.state = state;
        this.detection = detection;
        this.quality = quality;
        this.geometryValid = geometryValid;
        this.qualityValid = qualityValid;
        this.timestampMs = timestampMs;
        this.source = source == null ? Source.PREVIEW : source;
    }

    boolean isReadyCandidate() {
        return state == State.READY && geometryValid && qualityValid;
    }
}

final class RecognitionResult {
    final String side;
    final Integer variant;
    final float sideConfidence;
    final float variantConfidence;
    final int rotationDegrees;
    final List<String> signals;
    final float contentScore;

    RecognitionResult(String side, Integer variant, float sideConfidence,
                      float variantConfidence, int rotationDegrees, List<String> signals,
                      float contentScore) {
        this.side = side;
        this.variant = variant;
        this.sideConfidence = sideConfidence;
        this.variantConfidence = variantConfidence;
        this.rotationDegrees = rotationDegrees;
        this.signals = signals;
        this.contentScore = contentScore;
    }

    boolean matchesRequestedSide(String requestedSide) {
        return requestedSide != null && requestedSide.equals(side);
    }

    static RecognitionResult unknown() {
        return new RecognitionResult(
            "unknown",
            null,
            0f,
            0f,
            0,
            java.util.Collections.emptyList(),
            0f
        );
    }

}
