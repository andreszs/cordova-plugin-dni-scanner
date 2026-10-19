package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/** Lightweight rectangle detector based on gradient-directed Hough voting. */
final class DocumentDetector {
    private final ScannerConfig config;
    private final DetectionRegion detectionRegion;

    DocumentDetector(ScannerConfig config) {
        this(config, null);
    }

    DocumentDetector(ScannerConfig config, DetectionRegion detectionRegion) {
        this.config = config;
        this.detectionRegion = detectionRegion;
    }

    DocumentDetection detect(LumaFrame frame) {
        if (frame.width < 40 || frame.height < 40) {
            return null;
        }

        GradientField gradients = computeGradients(frame);
        if (gradients.edgeThreshold <= 0) {
            return null;
        }

        int diagonal = (int) Math.ceil(Math.hypot(frame.width, frame.height));
        int rhoCount = diagonal * 2 + 1;
        int angleBins = config.houghAngleBins;
        int[][] accumulator = new int[angleBins][rhoCount];
        double[] cosines = new double[angleBins];
        double[] sines = new double[angleBins];
        for (int bin = 0; bin < angleBins; bin++) {
            double angle = Math.PI * bin / angleBins;
            cosines[bin] = Math.cos(angle);
            sines[bin] = Math.sin(angle);
        }

        int step = config.edgeSampleStep;
        for (int y = 1; y < frame.height - 1; y += step) {
            for (int x = 1; x < frame.width - 1; x += step) {
                int index = y * frame.width + x;
                int magnitude = gradients.magnitudes[index] & 0xffff;
                if (magnitude < gradients.edgeThreshold) {
                    continue;
                }
                double normal = Math.atan2(gradients.gy[index], gradients.gx[index]);
                if (normal < 0) {
                    normal += Math.PI;
                }
                if (normal >= Math.PI) {
                    normal -= Math.PI;
                }
                int centerBin = (int) Math.round(normal * angleBins / Math.PI) % angleBins;
                int weight = Math.min(384, magnitude);
                for (int delta = -1; delta <= 1; delta++) {
                    int bin = (centerBin + delta + angleBins) % angleBins;
                    int rho = (int) Math.round(x * cosines[bin] + y * sines[bin]) + diagonal;
                    if (rho >= 0 && rho < rhoCount) {
                        accumulator[bin][rho] += delta == 0 ? weight : weight / 2;
                    }
                }
            }
        }

        List<Line> lines = strongestLines(accumulator, diagonal, cosines, sines);
        if (lines.size() < 4) {
            return null;
        }
        List<LinePair> pairs = parallelPairs(lines, frame.width, frame.height);
        if (pairs.size() < 2) {
            return null;
        }

        float maximumLineScore = Math.max(1f, lines.get(0).score);
        DocumentDetection best = null;
        for (int firstIndex = 0; firstIndex < pairs.size(); firstIndex++) {
            LinePair first = pairs.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < pairs.size(); secondIndex++) {
                LinePair second = pairs.get(secondIndex);
                double normalDifference = angleDifference(first.averageTheta(), second.averageTheta());
                if (normalDifference < Math.toRadians(58) || normalDifference > Math.toRadians(122)) {
                    continue;
                }
                DocumentQuad quad = intersections(first, second);
                if (quad == null || !isInsideWithMargin(quad, frame.width, frame.height, 0.08f)) {
                    continue;
                }
                if (detectionRegion != null
                    && !detectionRegion.contains(quad, frame.width, frame.height)) {
                    continue;
                }

                float areaFraction = quad.area() / (frame.width * (float) frame.height);
                if (areaFraction < config.minDocumentArea * 0.55f
                    || areaFraction > Math.min(0.96f, config.maxDocumentArea * 1.18f)) {
                    continue;
                }
                float averageWidth = (quad.topWidth() + quad.bottomWidth()) * 0.5f;
                float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
                if (averageHeight < 1f) {
                    continue;
                }
                float observedAspect = averageWidth / averageHeight;
                if (observedAspect < config.minObservedAspect || observedAspect > config.maxObservedAspect) {
                    continue;
                }

                float edgeSupport = edgeSupport(quad, gradients, frame.width, frame.height);
                float aspectScore = clamp01(1f - Math.abs(observedAspect - config.expectedDocumentAspect) / 0.75f);
                float targetArea = detectionRegion == null
                    ? 0.47f
                    : detectionRegion.targetAreaFraction(frame.width, frame.height);
                float areaScore = clamp01(
                    1f - Math.abs(areaFraction - targetArea) / Math.max(0.15f, targetArea)
                );
                PointF center = quad.center();
                float dx = (center.x - frame.width * 0.5f) / (frame.width * 0.5f);
                float dy = (center.y - frame.height * 0.5f) / (frame.height * 0.5f);
                float centerOffset = (float) Math.sqrt(dx * dx + dy * dy);
                float centerScore = clamp01(1f - centerOffset);
                float lineScore = clamp01((first.score + second.score) / (4f * maximumLineScore));
                float score = 0.48f * edgeSupport + 0.17f * aspectScore + 0.13f * areaScore
                    + 0.10f * centerScore + 0.12f * lineScore;

                if (best == null || score > best.score) {
                    best = new DocumentDetection(quad, score, areaFraction, centerOffset, observedAspect);
                }
            }
        }
        return best != null && best.score >= config.minDetectionScore ? best : null;
    }

    /**
     * Refines a still-image quadrilateral toward plausible enclosing edges.
     * Strong printed rules inside a DNI can otherwise be mistaken for the
     * physical card boundary. Refinement is deliberately outward-only.
     */
    DocumentDetection refineOutwardEdges(LumaFrame frame, DocumentDetection original) {
        if (original == null || original.quad == null) {
            return original;
        }
        GradientField gradients = computeGradients(frame);
        if (gradients.edgeThreshold <= 0) {
            return original;
        }

        PointF[] points = {
            original.quad.topLeft,
            original.quad.topRight,
            original.quad.bottomRight,
            original.quad.bottomLeft
        };
        float averageWidth = (original.quad.topWidth() + original.quad.bottomWidth()) * 0.5f;
        float averageHeight = (original.quad.leftHeight() + original.quad.rightHeight()) * 0.5f;
        int maximumOffset = Math.max(3, Math.round(
            Math.min(averageWidth, averageHeight) * config.outwardEdgeSearchFraction
        ));
        ShiftedEdge[] edges = new ShiftedEdge[4];
        boolean changed = false;
        for (int side = 0; side < 4; side++) {
            PointF start = points[side];
            PointF end = points[(side + 1) % 4];
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float length = (float) Math.hypot(dx, dy);
            if (length < 1f) {
                return original;
            }
            // DocumentQuad points are clockwise in Android screen coordinates.
            float outwardX = dy / length;
            float outwardY = -dx / length;
            float baseline = edgeSupport(
                start,
                end,
                gradients,
                frame.width,
                frame.height
            );
            float minimumSupport = Math.max(
                config.minimumOutwardEdgeSupport,
                baseline * config.minimumRelativeOutwardEdgeSupport
            );
            int bestOffset = 0;
            float bestUtility = baseline;
            for (int offset = 3; offset <= maximumOffset; offset++) {
                PointF shiftedStart = shift(start, outwardX, outwardY, offset);
                PointF shiftedEnd = shift(end, outwardX, outwardY, offset);
                if (!pointInsideFrame(shiftedStart, frame.width, frame.height)
                    || !pointInsideFrame(shiftedEnd, frame.width, frame.height)) {
                    break;
                }
                float support = edgeSupport(
                    shiftedStart,
                    shiftedEnd,
                    gradients,
                    frame.width,
                    frame.height
                );
                if (support < minimumSupport) {
                    continue;
                }
                float utility = support
                    + config.outwardEdgeDistanceBonus * offset / (float) maximumOffset;
                if (utility > bestUtility + 0.015f) {
                    bestUtility = utility;
                    bestOffset = offset;
                }
            }
            changed |= bestOffset > 0;
            edges[side] = new ShiftedEdge(
                shift(start, outwardX, outwardY, bestOffset),
                shift(end, outwardX, outwardY, bestOffset)
            );
        }
        if (!changed) {
            return original;
        }

        PointF topLeft = intersection(edges[3], edges[0]);
        PointF topRight = intersection(edges[0], edges[1]);
        PointF bottomRight = intersection(edges[1], edges[2]);
        PointF bottomLeft = intersection(edges[2], edges[3]);
        if (topLeft == null || topRight == null || bottomRight == null || bottomLeft == null) {
            return original;
        }
        DocumentQuad refined = new DocumentQuad(topLeft, topRight, bottomRight, bottomLeft);
        if (!isConvex(refined)
            || !isInsideWithMargin(refined, frame.width, frame.height, 0f)
            || (detectionRegion != null
                && !detectionRegion.contains(refined, frame.width, frame.height))) {
            return original;
        }
        float areaFraction = refined.area() / (frame.width * (float) frame.height);
        float refinedWidth = (refined.topWidth() + refined.bottomWidth()) * 0.5f;
        float refinedHeight = (refined.leftHeight() + refined.rightHeight()) * 0.5f;
        if (refinedHeight < 1f) {
            return original;
        }
        float aspect = refinedWidth / refinedHeight;
        if (aspect < config.minObservedAspect || aspect > config.maxObservedAspect) {
            return original;
        }
        PointF center = refined.center();
        float centerX = (center.x - frame.width * 0.5f) / (frame.width * 0.5f);
        float centerY = (center.y - frame.height * 0.5f) / (frame.height * 0.5f);
        float centerOffset = (float) Math.hypot(centerX, centerY);
        return new DocumentDetection(
            refined,
            original.score,
            areaFraction,
            centerOffset,
            aspect
        );
    }

    private GradientField computeGradients(LumaFrame frame) {
        int count = frame.width * frame.height;
        short[] magnitudes = new short[count];
        short[] gxValues = new short[count];
        short[] gyValues = new short[count];
        double sum = 0.0;
        double sumSquares = 0.0;
        int samples = 0;

        for (int y = 1; y < frame.height - 1; y++) {
            for (int x = 1; x < frame.width - 1; x++) {
                int topLeft = frame.unsignedAt(x - 1, y - 1);
                int top = frame.unsignedAt(x, y - 1);
                int topRight = frame.unsignedAt(x + 1, y - 1);
                int left = frame.unsignedAt(x - 1, y);
                int right = frame.unsignedAt(x + 1, y);
                int bottomLeft = frame.unsignedAt(x - 1, y + 1);
                int bottom = frame.unsignedAt(x, y + 1);
                int bottomRight = frame.unsignedAt(x + 1, y + 1);

                int gx = -topLeft - 2 * left - bottomLeft + topRight + 2 * right + bottomRight;
                int gy = -topLeft - 2 * top - topRight + bottomLeft + 2 * bottom + bottomRight;
                int magnitude = Math.min(32767, Math.abs(gx) + Math.abs(gy));
                int index = y * frame.width + x;
                magnitudes[index] = (short) magnitude;
                gxValues[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, gx));
                gyValues[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, gy));
                if ((x & 3) == 0 && (y & 3) == 0) {
                    sum += magnitude;
                    sumSquares += (double) magnitude * magnitude;
                    samples++;
                }
            }
        }
        double mean = samples == 0 ? 0.0 : sum / samples;
        double variance = samples == 0 ? 0.0 : Math.max(0.0, sumSquares / samples - mean * mean);
        int threshold = (int) Math.max(config.minEdgeThreshold,
            mean + config.edgeThresholdStdFactor * Math.sqrt(variance));
        return new GradientField(magnitudes, gxValues, gyValues, threshold);
    }

    private List<Line> strongestLines(int[][] accumulator, int diagonal,
                                      double[] cosines, double[] sines) {
        int[][] mutable = new int[accumulator.length][];
        for (int i = 0; i < accumulator.length; i++) {
            mutable[i] = accumulator[i].clone();
        }
        List<Line> result = new ArrayList<>();
        for (int selected = 0; selected < config.houghMaxLines; selected++) {
            int bestAngle = -1;
            int bestRho = -1;
            int bestScore = 0;
            for (int angle = 0; angle < mutable.length; angle++) {
                for (int rho = 0; rho < mutable[angle].length; rho++) {
                    if (mutable[angle][rho] > bestScore) {
                        bestScore = mutable[angle][rho];
                        bestAngle = angle;
                        bestRho = rho;
                    }
                }
            }
            if (bestScore <= 0) {
                break;
            }
            double theta = Math.PI * bestAngle / mutable.length;
            result.add(new Line(theta, bestRho - diagonal, bestScore,
                cosines[bestAngle], sines[bestAngle]));
            for (int angleDelta = -config.houghAngleSuppression;
                 angleDelta <= config.houghAngleSuppression; angleDelta++) {
                int angle = (bestAngle + angleDelta + mutable.length) % mutable.length;
                for (int rho = Math.max(0, bestRho - config.houghRhoSuppression);
                     rho <= Math.min(mutable[angle].length - 1, bestRho + config.houghRhoSuppression);
                     rho++) {
                    mutable[angle][rho] = 0;
                }
            }
        }
        Collections.sort(result, (first, second) -> Integer.compare(second.score, first.score));
        return result;
    }

    private List<LinePair> parallelPairs(List<Line> lines, int width, int height) {
        PriorityQueue<LinePair> strongest = new PriorityQueue<>(
            config.houghMaxPairs + 1,
            (first, second) -> Integer.compare(first.score, second.score)
        );
        float minimumSeparation = Math.min(width, height) * 0.20f;
        float maximumSeparation = (float) Math.hypot(width, height) * 0.92f;
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                Line first = lines.get(i);
                Line second = lines.get(j);
                if (angleDifference(first.theta, second.theta) > Math.toRadians(12)) {
                    continue;
                }
                float separation = (float) Math.abs(first.rho - second.rho);
                if (separation < minimumSeparation || separation > maximumSeparation) {
                    continue;
                }
                LinePair pair = new LinePair(first, second);
                strongest.offer(pair);
                if (strongest.size() > config.houghMaxPairs) {
                    strongest.poll();
                }
            }
        }
        List<LinePair> result = new ArrayList<>(strongest);
        Collections.sort(result, (first, second) -> Integer.compare(second.score, first.score));
        return result;
    }

    private DocumentQuad intersections(LinePair first, LinePair second) {
        List<PointF> points = new ArrayList<>(4);
        points.add(intersection(first.first, second.first));
        points.add(intersection(first.first, second.second));
        points.add(intersection(first.second, second.first));
        points.add(intersection(first.second, second.second));
        if (points.contains(null)) {
            return null;
        }
        DocumentQuad quad = DocumentQuad.ordered(points);
        if (quad == null || !isConvex(quad)) {
            return null;
        }
        return quad;
    }

    private PointF intersection(Line first, Line second) {
        double determinant = first.cosine * second.sine - second.cosine * first.sine;
        if (Math.abs(determinant) < 1e-5) {
            return null;
        }
        float x = (float) ((first.rho * second.sine - second.rho * first.sine) / determinant);
        float y = (float) ((second.rho * first.cosine - first.rho * second.cosine) / determinant);
        return new PointF(x, y);
    }

    private float edgeSupport(DocumentQuad quad, GradientField gradients, int width, int height) {
        PointF[] points = { quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft };
        float support = 0f;
        for (int side = 0; side < 4; side++) {
            support += edgeSupport(
                points[side],
                points[(side + 1) % 4],
                gradients,
                width,
                height
            );
        }
        return support / 4f;
    }

    private float edgeSupport(PointF start, PointF end, GradientField gradients,
                              int width, int height) {
        int supported = 0;
        int samples = Math.max(20, Math.min(100, (int) DocumentQuad.distance(start, end) / 3));
        for (int sample = 0; sample <= samples; sample++) {
            float fraction = sample / (float) samples;
            int x = Math.round(start.x + (end.x - start.x) * fraction);
            int y = Math.round(start.y + (end.y - start.y) * fraction);
            int localMaximum = 0;
            for (int offsetY = -2; offsetY <= 2; offsetY++) {
                for (int offsetX = -2; offsetX <= 2; offsetX++) {
                    int px = x + offsetX;
                    int py = y + offsetY;
                    if (px > 0 && px < width - 1 && py > 0 && py < height - 1) {
                        localMaximum = Math.max(localMaximum,
                            gradients.magnitudes[py * width + px] & 0xffff);
                    }
                }
            }
            if (localMaximum >= gradients.edgeThreshold) {
                supported++;
            }
        }
        return supported / (float) (samples + 1);
    }

    private PointF shift(PointF point, float x, float y, float distance) {
        return new PointF(point.x + x * distance, point.y + y * distance);
    }

    private boolean pointInsideFrame(PointF point, int width, int height) {
        return point.x >= 1f && point.y >= 1f
            && point.x <= width - 2f && point.y <= height - 2f;
    }

    private PointF intersection(ShiftedEdge first, ShiftedEdge second) {
        float firstX = first.end.x - first.start.x;
        float firstY = first.end.y - first.start.y;
        float secondX = second.end.x - second.start.x;
        float secondY = second.end.y - second.start.y;
        float determinant = firstX * secondY - firstY * secondX;
        if (Math.abs(determinant) < 1e-5f) {
            return null;
        }
        float offsetX = second.start.x - first.start.x;
        float offsetY = second.start.y - first.start.y;
        float parameter = (offsetX * secondY - offsetY * secondX) / determinant;
        return new PointF(
            first.start.x + parameter * firstX,
            first.start.y + parameter * firstY
        );
    }

    private boolean isInsideWithMargin(DocumentQuad quad, int width, int height, float marginFraction) {
        float marginX = width * marginFraction;
        float marginY = height * marginFraction;
        for (PointF point : quad.points()) {
            if (point.x < -marginX || point.y < -marginY
                || point.x > width + marginX || point.y > height + marginY) {
                return false;
            }
        }
        return true;
    }

    private boolean isConvex(DocumentQuad quad) {
        PointF[] points = { quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft };
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            PointF a = points[i];
            PointF b = points[(i + 1) % 4];
            PointF c = points[(i + 2) % 4];
            float cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
            if (Math.abs(cross) < 1e-3f) {
                return false;
            }
            if (i == 0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    private static double angleDifference(double first, double second) {
        double difference = Math.abs(first - second) % Math.PI;
        return difference > Math.PI / 2 ? Math.PI - difference : difference;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class GradientField {
        final short[] magnitudes;
        final short[] gx;
        final short[] gy;
        final int edgeThreshold;

        GradientField(short[] magnitudes, short[] gx, short[] gy, int edgeThreshold) {
            this.magnitudes = magnitudes;
            this.gx = gx;
            this.gy = gy;
            this.edgeThreshold = edgeThreshold;
        }
    }

    private static final class Line {
        final double theta;
        final double rho;
        final int score;
        final double cosine;
        final double sine;

        Line(double theta, double rho, int score, double cosine, double sine) {
            this.theta = theta;
            this.rho = rho;
            this.score = score;
            this.cosine = cosine;
            this.sine = sine;
        }
    }

    private static final class LinePair {
        final Line first;
        final Line second;
        final int score;

        LinePair(Line first, Line second) {
            this.first = first;
            this.second = second;
            this.score = first.score + second.score;
        }

        double averageTheta() {
            double x = Math.cos(first.theta * 2) + Math.cos(second.theta * 2);
            double y = Math.sin(first.theta * 2) + Math.sin(second.theta * 2);
            double value = Math.atan2(y, x) * 0.5;
            return value < 0 ? value + Math.PI : value;
        }
    }

    private static final class ShiftedEdge {
        final PointF start;
        final PointF end;

        ShiftedEdge(PointF start, PointF end) {
            this.start = start;
            this.end = end;
        }
    }
}
