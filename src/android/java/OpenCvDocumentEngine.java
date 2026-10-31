package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenCV document engine adapted from the detection strategies used by DocShot
 * (MIT, Copyright 2026 Artem Shamsuarov) and the side-evidence/quality approach
 * used by Open Android Doc Scanner (Apache-2.0).
 *
 * This is deliberately DNI-specific: it produces several contour proposals,
 * verifies that all four physical sides have evidence and ranks the proposals
 * against the guide and the existing temporal track. It never synthesizes a
 * rectangle from four unrelated guide-aligned lines.
 */
final class OpenCvDocumentEngine {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final float MIN_CANDIDATE_AREA = 0.10f;
    private static final float MAX_CANDIDATE_AREA = 0.94f;
    private static final float MIN_EDGE_SUPPORT = 0.48f;
    private static final float MAX_PRIOR_DISTANCE = 0.14f;

    private final ScannerConfig config;
    private final DetectionRegion detectionRegion;

    OpenCvDocumentEngine(ScannerConfig config, DetectionRegion detectionRegion) {
        this.config = config;
        this.detectionRegion = detectionRegion;
        ensureInitialized();
    }

    private static void ensureInitialized() {
        if (INITIALIZED.get()) {
            return;
        }
        synchronized (INITIALIZED) {
            if (!INITIALIZED.get()) {
                if (!OpenCVLoader.initLocal()) {
                    throw new IllegalStateException("OpenCV could not be initialized");
                }
                INITIALIZED.set(true);
            }
        }
    }

    Mat gray(LumaFrame frame) {
        Mat gray = new Mat(frame.height, frame.width, CvType.CV_8UC1);
        gray.put(0, 0, frame.data);
        return gray;
    }

    DocumentDetection detect(LumaFrame frame, DocumentQuad prior) {
        Mat gray = gray(frame);
        try {
            return detect(gray, frame.width, frame.height, prior);
        } finally {
            gray.release();
        }
    }

    DocumentDetection detect(Mat gray, int width, int height, DocumentQuad prior) {
        List<Candidate> candidates = new ArrayList<>();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble deviation = new MatOfDouble();
        Core.meanStdDev(gray, mean, deviation);
        double average = mean.toArray()[0];
        double contrast = deviation.toArray()[0];
        mean.release();
        deviation.release();

        // Low-contrast and pale scenes start with DoG. Normal scenes use the
        // cheapest Canny pass first. Additional strategies are miss-only.
        if (contrast < 42.0 || average > 170.0) {
            collect(gray, width, height, prior, Strategy.DOG, candidates);
            if (!hasStrongCandidate(candidates)) {
                collect(gray, width, height, prior, Strategy.CLAHE, candidates);
            }
            if (!hasStrongCandidate(candidates)) {
                collect(gray, width, height, prior, Strategy.STANDARD, candidates);
            }
        } else {
            collect(gray, width, height, prior, Strategy.STANDARD, candidates);
            if (!hasStrongCandidate(candidates)) {
                collect(gray, width, height, prior, Strategy.DOG, candidates);
            }
            if (!hasStrongCandidate(candidates)) {
                collect(gray, width, height, prior, Strategy.ADAPTIVE, candidates);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort((a, b) -> Float.compare(b.score, a.score));
        Candidate best = candidates.get(0);
        float second = candidates.size() > 1 ? candidates.get(1).score : 0f;
        float ambiguityMargin = Math.max(0f, best.score - second);
        float confidence = clamp01(best.score * (0.82f + 0.18f * Math.min(1f, ambiguityMargin / 0.20f)));
        if (confidence < 0.48f) {
            return null;
        }
        return new DocumentDetection(
            best.quad,
            confidence,
            best.areaFraction,
            best.centerOffset,
            best.observedAspect
        );
    }

    private boolean hasStrongCandidate(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (candidate.score >= 0.72f) {
                return true;
            }
        }
        return false;
    }

    private void collect(Mat gray, int width, int height, DocumentQuad prior,
                         Strategy strategy, List<Candidate> output) {
        Mat edges = preprocess(gray, strategy);
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);
            List<ContourArea> proposals = new ArrayList<>();
            double pixels = width * (double) height;
            for (MatOfPoint contour : contours) {
                double area = Math.abs(Imgproc.contourArea(contour));
                if (area >= pixels * 0.012 && area <= pixels * MAX_CANDIDATE_AREA) {
                    proposals.add(new ContourArea(contour, area));
                }
            }
            proposals.sort((a, b) -> Double.compare(b.area, a.area));
            int count = Math.min(100, proposals.size());
            for (int index = 0; index < count; index++) {
                addContourCandidates(
                    proposals.get(index), edges, width, height, prior, strategy, output
                );
            }
        } finally {
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            hierarchy.release();
            edges.release();
        }
    }

    private Mat preprocess(Mat gray, Strategy strategy) {
        Mat result = new Mat();
        Mat smooth = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            switch (strategy) {
                case STANDARD:
                    Imgproc.GaussianBlur(gray, smooth, new Size(5, 5), 0);
                    Imgproc.Canny(smooth, result, 28, 88);
                    break;
                case DOG:
                    Mat small = new Mat();
                    Mat broad = new Mat();
                    try {
                        Imgproc.GaussianBlur(gray, small, new Size(3, 3), 0);
                        Imgproc.GaussianBlur(gray, broad, new Size(21, 21), 0);
                        Core.absdiff(small, broad, result);
                        Imgproc.threshold(result, result, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
                    } finally {
                        small.release();
                        broad.release();
                    }
                    break;
                case CLAHE:
                    CLAHE clahe = Imgproc.createCLAHE(4.0, new Size(8, 8));
                    try {
                        clahe.apply(gray, smooth);
                        Imgproc.GaussianBlur(smooth, smooth, new Size(5, 5), 0);
                        Imgproc.Canny(smooth, result, 18, 58);
                    } finally {
                        clahe.collectGarbage();
                        clahe.clear();
                    }
                    break;
                case ADAPTIVE:
                    Imgproc.GaussianBlur(gray, smooth, new Size(5, 5), 0);
                    Imgproc.adaptiveThreshold(
                        smooth, result, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY, 51, 4
                    );
                    break;
            }
            Imgproc.morphologyEx(result, result, Imgproc.MORPH_CLOSE, kernel);
            return result;
        } finally {
            smooth.release();
            kernel.release();
        }
    }

    private void addContourCandidates(ContourArea contourArea, Mat edges, int width, int height,
                                      DocumentQuad prior, Strategy strategy,
                                      List<Candidate> output) {
        MatOfPoint contour = contourArea.contour;
        MatOfInt indices = new MatOfInt();
        try {
            Imgproc.convexHull(contour, indices);
            org.opencv.core.Point[] boundary = contour.toArray();
            int[] hullIndices = indices.toArray();
            org.opencv.core.Point[] hull = new org.opencv.core.Point[hullIndices.length];
            for (int i = 0; i < hullIndices.length; i++) {
                hull[i] = boundary[hullIndices[i]];
            }
            propose(boundary, contourArea.area, edges, width, height, prior, strategy, output);
            propose(hull, contourArea.area, edges, width, height, prior, strategy, output);
        } finally {
            indices.release();
        }
    }

    private void propose(org.opencv.core.Point[] points, double contourArea, Mat edges,
                         int width, int height, DocumentQuad prior, Strategy strategy,
                         List<Candidate> output) {
        if (points.length < 4) {
            return;
        }
        MatOfPoint2f curve = new MatOfPoint2f(points);
        MatOfPoint2f approximation = new MatOfPoint2f();
        try {
            double perimeter = Imgproc.arcLength(curve, true);
            for (double epsilon : new double[] { 0.012, 0.022, 0.035 }) {
                Imgproc.approxPolyDP(curve, approximation, perimeter * epsilon, true);
                if (approximation.total() != 4) {
                    continue;
                }
                MatOfPoint polygon = new MatOfPoint(approximation.toArray());
                boolean convex = Imgproc.isContourConvex(polygon);
                polygon.release();
                if (!convex) {
                    continue;
                }
                List<PointF> corners = new ArrayList<>(4);
                for (org.opencv.core.Point point : approximation.toArray()) {
                    corners.add(new PointF((float) point.x, (float) point.y));
                }
                DocumentQuad quad = DocumentQuad.ordered(corners);
                Candidate candidate = scoreCandidate(
                    quad, contourArea, edges, width, height, prior, strategy
                );
                if (candidate != null) {
                    addUnique(output, candidate, width, height);
                    break;
                }
            }
        } finally {
            curve.release();
            approximation.release();
        }
    }

    private Candidate scoreCandidate(DocumentQuad quad, double contourArea, Mat edges,
                                     int width, int height, DocumentQuad prior,
                                     Strategy strategy) {
        if (quad == null || !validQuad(quad, width, height)) {
            return null;
        }
        if (detectionRegion != null && !detectionRegion.contains(quad, width, height)) {
            return null;
        }
        float areaFraction = quad.area() / (width * (float) height);
        if (areaFraction < MIN_CANDIDATE_AREA || areaFraction > MAX_CANDIDATE_AREA) {
            return null;
        }
        float averageWidth = (quad.topWidth() + quad.bottomWidth()) * 0.5f;
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        if (averageHeight < 8f) {
            return null;
        }
        float observedAspect = averageWidth / averageHeight;
        if (observedAspect < 1.08f || observedAspect > 2.75f) {
            return null;
        }
        float edgeSupport = edgeSupport(quad, edges, width, height);
        if (edgeSupport < MIN_EDGE_SUPPORT) {
            return null;
        }
        float solidity = clamp01((float) (contourArea / Math.max(1.0, quad.area())));
        if (solidity < 0.62f) {
            return null;
        }
        PointF center = quad.center();
        float centerOffset = (float) Math.hypot(
            (center.x - width * 0.5f) / (width * 0.5f),
            (center.y - height * 0.5f) / (height * 0.5f)
        );
        float targetArea = detectionRegion == null
            ? 0.47f : detectionRegion.targetAreaFraction(width, height);
        float areaScore = clamp01(1f - Math.abs(areaFraction - targetArea) / Math.max(0.18f, targetArea));
        float aspectScore = clamp01(1f - Math.abs(observedAspect - config.expectedDocumentAspect) / 0.85f);
        float shapeScore = quad.rectangularityScore();
        float centerScore = clamp01(1f - centerOffset);
        float priorScore = 0.5f;
        if (prior != null) {
            float distance = quadDistance(prior, quad, width, height);
            if (distance > MAX_PRIOR_DISTANCE) {
                return null;
            }
            priorScore = clamp01(1f - distance / MAX_PRIOR_DISTANCE);
        }
        float score = 0.30f * edgeSupport
            + 0.17f * solidity
            + 0.15f * areaScore
            + 0.12f * aspectScore
            + 0.10f * shapeScore
            + 0.06f * centerScore
            + 0.10f * priorScore;
        if (strategy != Strategy.STANDARD) {
            // Enhanced preprocessing may expose faint edges, but never earns
            // confidence merely because it amplified them.
            score = Math.min(score, 0.90f);
        }
        return new Candidate(quad, score, areaFraction, centerOffset, observedAspect);
    }

    private void addUnique(List<Candidate> output, Candidate candidate, int width, int height) {
        for (int index = 0; index < output.size(); index++) {
            Candidate existing = output.get(index);
            if (quadDistance(existing.quad, candidate.quad, width, height) < 0.018f) {
                if (candidate.score > existing.score) {
                    output.set(index, candidate);
                }
                return;
            }
        }
        output.add(candidate);
    }

    float edgeSupport(DocumentQuad quad, Mat edges, int width, int height) {
        PointF[] points = {
            quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft
        };
        float total = 0f;
        for (int side = 0; side < 4; side++) {
            PointF first = points[side];
            PointF second = points[(side + 1) % 4];
            int supported = 0;
            int samples = 40;
            for (int sample = 0; sample < samples; sample++) {
                float t = 0.08f + 0.84f * (sample + 0.5f) / samples;
                int x = Math.round(first.x + (second.x - first.x) * t);
                int y = Math.round(first.y + (second.y - first.y) * t);
                boolean found = false;
                for (int dy = -2; dy <= 2 && !found; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int px = x + dx;
                        int py = y + dy;
                        if (px >= 0 && px < width && py >= 0 && py < height
                            && edges.get(py, px)[0] > 0) {
                            found = true;
                            break;
                        }
                    }
                }
                if (found) {
                    supported++;
                }
            }
            total += supported / (float) samples;
        }
        return total * 0.25f;
    }

    QualityMetrics assess(LumaFrame frame, DocumentQuad quad) {
        Mat gray = gray(frame);
        try {
            return assess(gray, quad);
        } finally {
            gray.release();
        }
    }

    QualityMetrics assess(Mat gray, DocumentQuad quad) {
        int targetWidth = 512;
        int targetHeight = Math.max(32, Math.round(targetWidth / config.expectedDocumentAspect));
        Mat document = new Mat();
        MatOfPoint2f source = points(quad);
        MatOfPoint2f destination = new MatOfPoint2f(
            new org.opencv.core.Point(0, 0),
            new org.opencv.core.Point(targetWidth - 1, 0),
            new org.opencv.core.Point(targetWidth - 1, targetHeight - 1),
            new org.opencv.core.Point(0, targetHeight - 1)
        );
        Mat transform = Imgproc.getPerspectiveTransform(source, destination);
        try {
            Imgproc.warpPerspective(
                gray, document, transform, new Size(targetWidth, targetHeight),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar.all(0)
            );
            return assessRectified(document, quad.rectangularityScore());
        } finally {
            document.release();
            source.release();
            destination.release();
            transform.release();
        }
    }

    private QualityMetrics assessRectified(Mat document, float perspectiveScore) {
        int marginX = Math.max(2, Math.round(document.cols() * 0.08f));
        int marginY = Math.max(2, Math.round(document.rows() * 0.08f));
        Rect interiorRect = new Rect(
            marginX, marginY,
            document.cols() - marginX * 2,
            document.rows() - marginY * 2
        );
        Mat interior = document.submat(interiorRect);
        try {
            byte[] pixels = new byte[(int) interior.total()];
            interior.get(0, 0, pixels);
            long sum = 0;
            int dark = 0;
            int clipped = 0;
            for (byte raw : pixels) {
                int value = raw & 0xff;
                sum += value;
                if (value <= 20) dark++;
                if (value >= 250) clipped++;
            }
            float brightness = pixels.length == 0 ? 0f : sum / (float) pixels.length;
            float darkFraction = pixels.length == 0 ? 1f : dark / (float) pixels.length;
            float clippedFraction = pixels.length == 0 ? 1f : clipped / (float) pixels.length;

            List<Double> sharpnessScores = new ArrayList<>();
            List<Double> informative = new ArrayList<>();
            double[][] tileMeans = new double[3][3];
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    int left = interior.cols() * column / 3;
                    int right = interior.cols() * (column + 1) / 3;
                    int top = interior.rows() * row / 3;
                    int bottom = interior.rows() * (row + 1) / 3;
                    Mat tile = interior.submat(top, bottom, left, right);
                    Mat laplacian = new Mat();
                    MatOfDouble mean = new MatOfDouble();
                    MatOfDouble deviation = new MatOfDouble();
                    try {
                        Core.meanStdDev(tile, mean, deviation);
                        tileMeans[row][column] = mean.toArray()[0];
                        double texture = deviation.toArray()[0];
                        Imgproc.Laplacian(tile, laplacian, CvType.CV_64F);
                        Core.meanStdDev(laplacian, mean, deviation);
                        double score = deviation.toArray()[0] * deviation.toArray()[0];
                        sharpnessScores.add(score);
                        if (texture >= 8.0) informative.add(score);
                    } finally {
                        tile.release();
                        laplacian.release();
                        mean.release();
                        deviation.release();
                    }
                }
            }
            List<Double> selected = informative.isEmpty() ? sharpnessScores : informative;
            Collections.sort(selected);
            double sharpness = selected.get(Math.round((selected.size() - 1) * 0.75f));

            double minimumTile = Double.MAX_VALUE;
            double maximumTile = 0;
            double maximumBoundary = 0;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    minimumTile = Math.min(minimumTile, tileMeans[row][column]);
                    maximumTile = Math.max(maximumTile, tileMeans[row][column]);
                    if (row > 0) {
                        maximumBoundary = Math.max(maximumBoundary,
                            Math.abs(tileMeans[row][column] - tileMeans[row - 1][column]));
                    }
                    if (column > 0) {
                        maximumBoundary = Math.max(maximumBoundary,
                            Math.abs(tileMeans[row][column] - tileMeans[row][column - 1]));
                    }
                }
            }

            Mat highlight = new Mat();
            Mat labels = new Mat();
            Mat stats = new Mat();
            Mat centroids = new Mat();
            float largestHighlight = 0f;
            try {
                Imgproc.threshold(interior, highlight, 250, 255, Imgproc.THRESH_BINARY);
                int components = Imgproc.connectedComponentsWithStats(
                    highlight, labels, stats, centroids, 8, CvType.CV_32S
                );
                for (int component = 1; component < components; component++) {
                    double[] value = stats.get(component, Imgproc.CC_STAT_AREA);
                    if (value != null) {
                        largestHighlight = Math.max(
                            largestHighlight,
                            (float) (value[0] / Math.max(1.0, interior.total()))
                        );
                    }
                }
            } finally {
                highlight.release();
                labels.release();
                stats.release();
                centroids.release();
            }

            return new QualityMetrics(
                sharpness,
                brightness,
                darkFraction,
                clippedFraction,
                clippedFraction,
                largestHighlight,
                perspectiveScore,
                0f,
                0f,
                (float) ((maximumTile - minimumTile) / 255.0),
                (float) (maximumBoundary / 255.0),
                0f
            );
        } finally {
            interior.release();
        }
    }

    AnalysisResult.State classify(DocumentDetection detection, QualityMetrics quality,
                                  ScanChecks checks) {
        if (checks.documentSize && detection.areaFraction < 0.14f) {
            return AnalysisResult.State.MOVE_CLOSER;
        }
        if (checks.documentSize && detection.areaFraction > 0.88f) {
            return AnalysisResult.State.MOVE_AWAY;
        }
        if (checks.centering && detection.centerOffset > 0.34f) {
            return AnalysisResult.State.CENTER_DOCUMENT;
        }
        if (detection.quad.maximumInPlaneRotationDegrees()
            > ScannerConfig.MAX_IN_PLANE_ROTATION_DEGREES) {
            return AnalysisResult.State.ADJUST_PERSPECTIVE;
        }
        if (!detection.quad.hasAcceptablePerspective(
            ScannerConfig.MAX_RECTANGLE_DEVIATION_DEGREES,
            ScannerConfig.MAX_SYMMETRIC_PERSPECTIVE_DEVIATION_DEGREES,
            ScannerConfig.MAX_TRAPEZOID_SYMMETRY_ERROR_DEGREES
        )) {
            return AnalysisResult.State.ADJUST_PERSPECTIVE;
        }
        if (checks.sharpness && quality.sharpness < 55.0) {
            return AnalysisResult.State.WAITING_FOR_FOCUS;
        }
        if (checks.brightness && (quality.brightness < 36f || quality.darkFraction > 0.45f)) {
            return AnalysisResult.State.IMPROVE_LIGHTING;
        }
        // White DNI stock is not overexposure. Require both substantial sensor
        // clipping and a connected specular region before blocking capture.
        if (checks.brightness && quality.clippedFraction > 0.42f
            && quality.glareBlobFraction > 0.10f) {
            return AnalysisResult.State.REDUCE_LIGHTING;
        }
        if (checks.glare && quality.glareBlobFraction > 0.13f) {
            return AnalysisResult.State.AVOID_GLARE;
        }
        if (checks.shadows && quality.illuminationSpread > 0.46f
            && quality.shadowBoundary > 0.28f) {
            return AnalysisResult.State.UNEVEN_LIGHTING;
        }
        return AnalysisResult.State.READY;
    }

    private boolean validQuad(DocumentQuad quad, int width, int height) {
        PointF[] p = { quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft };
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            PointF a = p[i];
            PointF b = p[(i + 1) % 4];
            PointF c = p[(i + 2) % 4];
            if (a.x < 1 || a.y < 1 || a.x > width - 2 || a.y > height - 2) {
                return false;
            }
            float cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
            if (Math.abs(cross) < 1e-3f) return false;
            if (sign == 0f) sign = Math.signum(cross);
            else if (cross * sign < 0f) return false;
        }
        return true;
    }

    static float quadDistance(DocumentQuad first, DocumentQuad second, int width, int height) {
        float diagonal = Math.max(1f, (float) Math.hypot(width, height));
        List<PointF> a = first.points();
        List<PointF> b = second.points();
        float squared = 0f;
        for (int i = 0; i < 4; i++) {
            float distance = DocumentQuad.distance(a.get(i), b.get(i)) / diagonal;
            squared += distance * distance;
        }
        return (float) Math.sqrt(squared / 4f);
    }

    static float quadMaxCornerDistance(DocumentQuad first, DocumentQuad second,
                                       int width, int height) {
        float diagonal = Math.max(1f, (float) Math.hypot(width, height));
        List<PointF> a = first.points();
        List<PointF> b = second.points();
        float maximum = 0f;
        for (int i = 0; i < 4; i++) {
            maximum = Math.max(
                maximum,
                DocumentQuad.distance(a.get(i), b.get(i)) / diagonal
            );
        }
        return maximum;
    }

    static DocumentQuad blend(DocumentQuad previous, DocumentQuad current, float currentWeight) {
        List<PointF> a = previous.points();
        List<PointF> b = current.points();
        List<PointF> result = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            result.add(new PointF(
                a.get(i).x + (b.get(i).x - a.get(i).x) * currentWeight,
                a.get(i).y + (b.get(i).y - a.get(i).y) * currentWeight
            ));
        }
        return new DocumentQuad(result.get(0), result.get(1), result.get(2), result.get(3));
    }

    static MatOfPoint2f points(DocumentQuad quad) {
        return new MatOfPoint2f(
            new org.opencv.core.Point(quad.topLeft.x, quad.topLeft.y),
            new org.opencv.core.Point(quad.topRight.x, quad.topRight.y),
            new org.opencv.core.Point(quad.bottomRight.x, quad.bottomRight.y),
            new org.opencv.core.Point(quad.bottomLeft.x, quad.bottomLeft.y)
        );
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private enum Strategy { STANDARD, DOG, CLAHE, ADAPTIVE }

    private static final class ContourArea {
        final MatOfPoint contour;
        final double area;
        ContourArea(MatOfPoint contour, double area) {
            this.contour = contour;
            this.area = area;
        }
    }

    private static final class Candidate {
        final DocumentQuad quad;
        final float score;
        final float areaFraction;
        final float centerOffset;
        final float observedAspect;
        Candidate(DocumentQuad quad, float score, float areaFraction,
                  float centerOffset, float observedAspect) {
            this.quad = quad;
            this.score = score;
            this.areaFraction = areaFraction;
            this.centerOffset = centerOffset;
            this.observedAspect = observedAspect;
        }
    }
}
