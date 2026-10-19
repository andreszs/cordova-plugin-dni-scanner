package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import java.util.ArrayDeque;

final class QualityEvaluator {
    private final ScannerConfig config;

    QualityEvaluator(ScannerConfig config) {
        this.config = config;
    }

    QualityMetrics evaluate(LumaFrame frame, DocumentQuad quad) {
        int minX = clamp((int) Math.floor(minX(quad)), 1, frame.width - 2);
        int maxX = clamp((int) Math.ceil(maxX(quad)), 1, frame.width - 2);
        int minY = clamp((int) Math.floor(minY(quad)), 1, frame.height - 2);
        int maxY = clamp((int) Math.ceil(maxY(quad)), 1, frame.height - 2);

        long brightnessSum = 0L;
        int darkPixels = 0;
        int clippedPixels = 0;
        int totalPixels = 0;
        double laplacianSum = 0.0;
        double laplacianSquareSum = 0.0;
        int laplacianSamples = 0;

        for (int y = minY; y <= maxY; y += 2) {
            for (int x = minX; x <= maxX; x += 2) {
                if (!contains(quad, x + 0.5f, y + 0.5f)) {
                    continue;
                }
                int value = frame.unsignedAt(x, y);
                brightnessSum += value;
                totalPixels++;
                if (value <= config.darkPixelThreshold) {
                    darkPixels++;
                }
                if (value >= config.clippedPixelThreshold) {
                    clippedPixels++;
                }

                int laplacian = 4 * value - frame.unsignedAt(x - 1, y)
                    - frame.unsignedAt(x + 1, y) - frame.unsignedAt(x, y - 1)
                    - frame.unsignedAt(x, y + 1);
                laplacianSum += laplacian;
                laplacianSquareSum += (double) laplacian * laplacian;
                laplacianSamples++;

            }
        }

        if (totalPixels == 0) {
            return new QualityMetrics(0.0, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f);
        }
        double laplacianMean = laplacianSamples == 0 ? 0.0 : laplacianSum / laplacianSamples;
        double sharpness = laplacianSamples == 0 ? 0.0
            : Math.max(0.0, laplacianSquareSum / laplacianSamples - laplacianMean * laplacianMean);
        float brightness = brightnessSum / (float) totalPixels;
        int adaptiveGlareThreshold = Math.min(250, Math.max(
            config.glarePixelThreshold,
            Math.round(brightness + config.minGlareBrightnessContrast)
        ));
        boolean[] glareMask = new boolean[frame.width * frame.height];
        for (int y = minY; y <= maxY; y += 2) {
            for (int x = minX; x <= maxX; x += 2) {
                if (!contains(quad, x + 0.5f, y + 0.5f)) {
                    continue;
                }
                int value = frame.unsignedAt(x, y);
                int colorfulness = frame.colorfulnessAt(x, y);
                boolean neutralHighlight = colorfulness < 0
                    || colorfulness <= config.maxGlareColorfulness;
                if (value >= adaptiveGlareThreshold
                    && localRange(frame, x, y) <= config.maxGlareLocalRange
                    && neutralHighlight) {
                    glareMask[y * frame.width + x] = true;
                }
            }
        }
        GlareMetrics glare = validatedGlareMetrics(
            glareMask,
            frame,
            quad,
            minX,
            maxX,
            minY,
            maxY,
            totalPixels
        );
        float glareFraction = glare.acceptedPixels / (float) totalPixels;
        float glareBlobFraction = glare.largestAcceptedBlob / (float) totalPixels;
        // If most of a pale card is bright, the mask describes the document
        // stock or overall exposure rather than a localized specular reflection.
        // Exposure clipping is evaluated independently below.
        if (glareFraction > config.maxLocalizedGlareCoverage) {
            glareFraction = 0f;
            glareBlobFraction = 0f;
        }

        float edgeGapFraction = edgeGapFraction(frame, quad);
        float warmEdgeRunFraction = warmEdgeRunFraction(frame, quad);
        float skinEdgeRunFraction = skinEdgeRunFraction(frame, quad);
        IlluminationMetrics illumination = illuminationMetrics(frame, quad);
        return new QualityMetrics(
            sharpness,
            brightness,
            darkPixels / (float) totalPixels,
            clippedPixels / (float) totalPixels,
            glareFraction,
            glareBlobFraction,
            perspectiveScore(quad),
            edgeGapFraction,
            warmEdgeRunFraction,
            illumination.spread,
            illumination.shadowBoundary,
            skinEdgeRunFraction
        );
    }

    AnalysisResult.State classify(DocumentDetection detection, QualityMetrics quality) {
        return classify(detection, quality, ScanChecks.defaults());
    }

    AnalysisResult.State classify(DocumentDetection detection, QualityMetrics quality,
                                  ScanChecks checks) {
        if (checks.documentSize && detection.areaFraction < config.minDocumentArea) {
            return AnalysisResult.State.MOVE_CLOSER;
        }
        if (checks.documentSize && detection.areaFraction > config.maxDocumentArea) {
            return AnalysisResult.State.MOVE_AWAY;
        }
        if (checks.centering && detection.centerOffset > config.maxCenterOffset) {
            return AnalysisResult.State.CENTER_DOCUMENT;
        }
        if (checks.perspective && quality.perspectiveScore < config.minPerspectiveScore) {
            return AnalysisResult.State.ADJUST_PERSPECTIVE;
        }
        if (checks.brightness
            && (quality.brightness < config.minBrightness
                || quality.darkFraction > config.maxDarkFraction)) {
            return AnalysisResult.State.IMPROVE_LIGHTING;
        }
        if (checks.brightness && quality.brightness > config.maxBrightness) {
            return AnalysisResult.State.REDUCE_LIGHTING;
        }
        if (checks.edgeOcclusion && hasEdgeOcclusion(quality)) {
            return AnalysisResult.State.EDGE_OCCLUDED;
        }
        if (checks.glare
            && (quality.glareFraction > config.maxGlareFraction
                || quality.glareBlobFraction > config.maxGlareBlobFraction)) {
            return AnalysisResult.State.AVOID_GLARE;
        }
        if (checks.brightness && quality.clippedFraction > config.maxClippedFraction) {
            return AnalysisResult.State.REDUCE_LIGHTING;
        }
        if (checks.shadows && hasUnevenLighting(quality)) {
            return AnalysisResult.State.UNEVEN_LIGHTING;
        }
        if (checks.sharpness && quality.sharpness < config.minSharpnessVariance) {
            return AnalysisResult.State.WAITING_FOR_FOCUS;
        }
        return AnalysisResult.State.READY;
    }

    boolean hasUnevenLighting(QualityMetrics quality) {
        return quality.shadowBoundary > config.maxSevereShadowBoundary
            || (quality.illuminationSpread > config.maxIlluminationSpread
                && quality.shadowBoundary > config.maxShadowBoundary);
    }

    boolean hasEdgeOcclusion(QualityMetrics quality) {
        return quality.edgeGapFraction > config.maxEdgeGapFraction
            || quality.warmEdgeRunFraction > config.maxWarmEdgeRunFraction
            || hasPreviewSkinEdgeOcclusion(quality);
    }

    boolean hasStillEdgeOcclusion(QualityMetrics quality) {
        return quality.edgeGapFraction > config.maxEdgeGapFraction
            || quality.warmEdgeRunFraction > config.maxWarmEdgeRunFraction
            || hasStillSkinEdgeOcclusion(quality);
    }

    boolean hasPreviewSkinEdgeOcclusion(QualityMetrics quality) {
        boolean strongSkinCrossing = quality.skinEdgeRunFraction
            > config.maxUncorroboratedSkinEdgeRunFraction;
        boolean corroboratedSkinCrossing = quality.skinEdgeRunFraction
                > config.maxSkinEdgeRunFraction
            && quality.edgeGapFraction
                > config.skinCorroboratingEdgeGapFraction;
        return strongSkinCrossing || corroboratedSkinCrossing;
    }

    boolean hasStillSkinEdgeOcclusion(QualityMetrics quality) {
        boolean strongSkinCrossing = quality.skinEdgeRunFraction
            > config.maxStillSkinEdgeRunFraction;
        boolean corroboratedSkinCrossing = quality.skinEdgeRunFraction
                > config.maxSkinEdgeRunFraction
            && quality.edgeGapFraction
                > config.stillSkinCorroboratingEdgeGapFraction;
        return strongSkinCrossing || corroboratedSkinCrossing;
    }

    private IlluminationMetrics illuminationMetrics(LumaFrame frame, DocumentQuad quad) {
        int columns = config.illuminationGridColumns;
        int rows = config.illuminationGridRows;
        int[] tiles = new int[columns * rows];
        int[] histogram = new int[256];
        int samplesPerAxis = config.illuminationSamplesPerAxis;
        float usable = 1f - 2f * config.illuminationGridInset;

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                java.util.Arrays.fill(histogram, 0);
                int sampleCount = 0;
                for (int sampleY = 0; sampleY < samplesPerAxis; sampleY++) {
                    float v = config.illuminationGridInset + usable
                        * (row + (sampleY + 0.5f) / samplesPerAxis) / rows;
                    for (int sampleX = 0; sampleX < samplesPerAxis; sampleX++) {
                        float u = config.illuminationGridInset + usable
                            * (column + (sampleX + 0.5f) / samplesPerAxis) / columns;
                        PointF point = bilinearPoint(quad, u, v);
                        int x = clamp(Math.round(point.x), 0, frame.width - 1);
                        int y = clamp(Math.round(point.y), 0, frame.height - 1);
                        histogram[frame.unsignedAt(x, y)]++;
                        sampleCount++;
                    }
                }
                // The upper quartile approximates the local card substrate and
                // suppresses dark text, portraits and machine-readable codes.
                tiles[row * columns + column] = histogramPercentile(
                    histogram,
                    sampleCount,
                    0.78f
                );
            }
        }

        int[] sortedTiles = tiles.clone();
        java.util.Arrays.sort(sortedTiles);
        int low = arrayPercentile(sortedTiles, config.illuminationLowPercentile);
        int high = arrayPercentile(sortedTiles, config.illuminationHighPercentile);
        float spread = (high - low) / 255f;
        float boundary = coherentShadowBoundary(tiles, columns, rows) / 255f;
        return new IlluminationMetrics(spread, boundary);
    }

    private float coherentShadowBoundary(int[] tiles, int columns, int rows) {
        float maximum = 0f;
        for (int column = 0; column < columns - 1; column++) {
            int positive = 0;
            int negative = 0;
            for (int row = 0; row < rows; row++) {
                int difference = tiles[row * columns + column + 1]
                    - tiles[row * columns + column];
                if (difference >= config.minShadowTileDifference) {
                    positive += difference;
                } else if (difference <= -config.minShadowTileDifference) {
                    negative += -difference;
                }
            }
            maximum = Math.max(maximum, Math.max(positive, negative) / (float) rows);
        }
        for (int row = 0; row < rows - 1; row++) {
            int positive = 0;
            int negative = 0;
            for (int column = 0; column < columns; column++) {
                int difference = tiles[(row + 1) * columns + column]
                    - tiles[row * columns + column];
                if (difference >= config.minShadowTileDifference) {
                    positive += difference;
                } else if (difference <= -config.minShadowTileDifference) {
                    negative += -difference;
                }
            }
            maximum = Math.max(maximum, Math.max(positive, negative) / (float) columns);
        }
        return maximum;
    }

    private int histogramPercentile(int[] histogram, int count, float percentile) {
        int target = Math.max(1, Math.round(count * percentile));
        int cumulative = 0;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative >= target) {
                return value;
            }
        }
        return 255;
    }

    private int arrayPercentile(int[] sorted, float percentile) {
        int index = Math.round((sorted.length - 1) * percentile);
        return sorted[clamp(index, 0, sorted.length - 1)];
    }

    private static final class IlluminationMetrics {
        final float spread;
        final float shadowBoundary;

        IlluminationMetrics(float spread, float shadowBoundary) {
            this.spread = spread;
            this.shadowBoundary = shadowBoundary;
        }
    }

    private float edgeGapFraction(LumaFrame frame, DocumentQuad quad) {
        PointF[] points = { quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft };
        PointF center = quad.center();
        int samples = config.edgeOcclusionSamples;
        int longestGap = 0;
        int supportedEdges = 0;
        float normalDistance = Math.max(2f, Math.min(quad.leftHeight(), quad.topWidth()) * 0.018f);

        // A corrected output image ends exactly on the quad and has no exterior
        // pixels to compare. Its obstruction check is supplied by the warm-edge
        // signal below; continuity is evaluated on preview and raw still frames.
        float requiredMargin = normalDistance * 1.5f + 1f;
        for (PointF point : points) {
            if (point.x < requiredMargin || point.y < requiredMargin
                || point.x >= frame.width - requiredMargin
                || point.y >= frame.height - requiredMargin) {
                return 0f;
            }
        }

        for (int edge = 0; edge < 4; edge++) {
            PointF start = points[edge];
            PointF end = points[(edge + 1) % 4];
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1f) {
                continue;
            }
            float nx = -dy / length;
            float ny = dx / length;
            PointF middle = new PointF((start.x + end.x) * 0.5f, (start.y + end.y) * 0.5f);
            if ((center.x - middle.x) * nx + (center.y - middle.y) * ny < 0f) {
                nx = -nx;
                ny = -ny;
            }

            int currentGap = 0;
            int edgeSupported = 0;
            for (int sample = 0; sample < samples; sample++) {
                float usable = 1f - 2f * config.edgeOcclusionInset;
                float t = config.edgeOcclusionInset
                    + usable * (sample + 0.5f) / samples;
                float px = start.x + dx * t;
                float py = start.y + dy * t;
                int inner = sampleLuma(frame, px + nx * normalDistance, py + ny * normalDistance);
                int outer = sampleLuma(frame, px - nx * normalDistance, py - ny * normalDistance);
                int innerFar = sampleLuma(frame, px + nx * normalDistance * 1.65f,
                    py + ny * normalDistance * 1.65f);
                int outerFar = sampleLuma(frame, px - nx * normalDistance * 1.65f,
                    py - ny * normalDistance * 1.65f);
                int contrast = Math.max(Math.abs(inner - outer), Math.abs(innerFar - outerFar));
                if (contrast >= config.minVisibleEdgeContrast) {
                    currentGap = 0;
                    edgeSupported++;
                } else {
                    currentGap++;
                    longestGap = Math.max(longestGap, currentGap);
                }
            }
            if (edgeSupported >= samples / 2) {
                supportedEdges++;
            }
        }
        // Do not call a uniformly low-contrast scene an occluded edge. The
        // warmer, localized signal can still identify a hand in that case.
        return supportedEdges < 2 ? 0f : longestGap / (float) samples;
    }

    private float warmEdgeRunFraction(LumaFrame frame, DocumentQuad quad) {
        if (frame.warmth == null) {
            return 0f;
        }
        int segments = config.warmEdgeSegments;
        int longestRun = 0;
        for (int edge = 0; edge < 4; edge++) {
            int currentRun = 0;
            for (int segment = 0; segment < segments; segment++) {
                long sum = 0L;
                int count = 0;
                for (int along = 0; along < 4; along++) {
                    float edgePosition = (segment + (along + 0.5f) / 4f) / segments;
                    for (int depthSample = 0; depthSample < 4; depthSample++) {
                        float depth = config.warmEdgeBandFraction
                            * (depthSample + 0.5f) / 4f;
                        float u;
                        float v;
                        switch (edge) {
                            case 0:
                                u = edgePosition;
                                v = depth;
                                break;
                            case 1:
                                u = 1f - depth;
                                v = edgePosition;
                                break;
                            case 2:
                                u = edgePosition;
                                v = 1f - depth;
                                break;
                            default:
                                u = depth;
                                v = edgePosition;
                                break;
                        }
                        PointF point = bilinearPoint(quad, u, v);
                        int x = clamp(Math.round(point.x), 0, frame.width - 1);
                        int y = clamp(Math.round(point.y), 0, frame.height - 1);
                        sum += frame.warmthAt(x, y);
                        count++;
                    }
                }
                float average = count == 0 ? 0f : sum / (float) count;
                if (average >= config.minWarmEdgeDominance) {
                    currentRun++;
                    longestRun = Math.max(longestRun, currentRun);
                } else {
                    currentRun = 0;
                }
            }
        }
        return longestRun / (float) segments;
    }

    private float skinEdgeRunFraction(LumaFrame frame, DocumentQuad quad) {
        if (frame.skinLikelihood == null) {
            return 0f;
        }
        int segments = config.skinEdgeSegments;
        int longestRun = 0;
        for (int edge = 0; edge < 4; edge++) {
            int currentRun = 0;
            for (int segment = 0; segment < segments; segment++) {
                int crossingSamples = 0;
                int count = 0;
                for (int along = 0; along < 4; along++) {
                    float edgePosition = (segment + (along + 0.5f) / 4f) / segments;
                    for (int depthSample = 0; depthSample < 4; depthSample++) {
                        float depth = config.skinEdgeBandFraction
                            * (depthSample + 0.5f) / 4f;
                        PointF inside = edgeBandPoint(quad, edge, edgePosition, depth);
                        PointF outside = edgeBandPoint(quad, edge, edgePosition, -depth);
                        int insideX = Math.round(inside.x);
                        int insideY = Math.round(inside.y);
                        int outsideX = Math.round(outside.x);
                        int outsideY = Math.round(outside.y);
                        if (!isInsideFrame(frame, insideX, insideY)
                            || !isInsideFrame(frame, outsideX, outsideY)) {
                            continue;
                        }
                        count++;
                        if (frame.skinLikelihoodAt(insideX, insideY)
                                < config.minSkinLikelihood
                            || frame.skinLikelihoodAt(outsideX, outsideY)
                                < config.minSkinLikelihood) {
                            continue;
                        }
                        if (Math.abs(frame.unsignedAt(insideX, insideY)
                                - frame.unsignedAt(outsideX, outsideY))
                                <= config.maxSkinCrossingLumaDifference
                            && channelDifference(
                                frame.colorfulnessAt(insideX, insideY),
                                frame.colorfulnessAt(outsideX, outsideY)
                            ) <= config.maxSkinCrossingColorfulnessDifference
                            && channelDifference(
                                frame.warmthAt(insideX, insideY),
                                frame.warmthAt(outsideX, outsideY)
                            ) <= config.maxSkinCrossingWarmthDifference) {
                            crossingSamples++;
                        }
                    }
                }
                float coverage = count == 0 ? 0f : crossingSamples / (float) count;
                if (coverage >= config.minSkinSegmentCoverage) {
                    currentRun++;
                    longestRun = Math.max(longestRun, currentRun);
                } else {
                    currentRun = 0;
                }
            }
        }
        return longestRun / (float) segments;
    }

    private PointF edgeBandPoint(DocumentQuad quad, int edge, float edgePosition,
                                 float signedDepth) {
        switch (edge) {
            case 0:
                return bilinearPoint(quad, edgePosition, signedDepth);
            case 1:
                return bilinearPoint(quad, 1f - signedDepth, edgePosition);
            case 2:
                return bilinearPoint(quad, edgePosition, 1f - signedDepth);
            default:
                return bilinearPoint(quad, signedDepth, edgePosition);
        }
    }

    private boolean isInsideFrame(LumaFrame frame, int x, int y) {
        return x >= 0 && y >= 0 && x < frame.width && y < frame.height;
    }

    private int channelDifference(int first, int second) {
        if (first < 0 || second < 0) {
            return 0;
        }
        return Math.abs(first - second);
    }

    private PointF bilinearPoint(DocumentQuad quad, float u, float v) {
        float topX = quad.topLeft.x + (quad.topRight.x - quad.topLeft.x) * u;
        float topY = quad.topLeft.y + (quad.topRight.y - quad.topLeft.y) * u;
        float bottomX = quad.bottomLeft.x + (quad.bottomRight.x - quad.bottomLeft.x) * u;
        float bottomY = quad.bottomLeft.y + (quad.bottomRight.y - quad.bottomLeft.y) * u;
        return new PointF(topX + (bottomX - topX) * v, topY + (bottomY - topY) * v);
    }

    private int sampleLuma(LumaFrame frame, float x, float y) {
        return frame.unsignedAt(
            clamp(Math.round(x), 0, frame.width - 1),
            clamp(Math.round(y), 0, frame.height - 1)
        );
    }

    private float perspectiveScore(DocumentQuad quad) {
        float top = quad.topWidth();
        float bottom = quad.bottomWidth();
        float left = quad.leftHeight();
        float right = quad.rightHeight();
        float widthBalance = ratioScore(top, bottom);
        float heightBalance = ratioScore(left, right);
        float diagonalBalance = ratioScore(
            DocumentQuad.distance(quad.topLeft, quad.bottomRight),
            DocumentQuad.distance(quad.topRight, quad.bottomLeft)
        );
        float angleScore = 0f;
        PointF[] points = { quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft };
        for (int i = 0; i < 4; i++) {
            PointF previous = points[(i + 3) % 4];
            PointF current = points[i];
            PointF next = points[(i + 1) % 4];
            float ax = previous.x - current.x;
            float ay = previous.y - current.y;
            float bx = next.x - current.x;
            float by = next.y - current.y;
            float denominator = (float) Math.sqrt((ax * ax + ay * ay) * (bx * bx + by * by));
            float cosine = denominator < 1e-4f ? 1f : Math.abs((ax * bx + ay * by) / denominator);
            angleScore += Math.max(0f, 1f - cosine);
        }
        angleScore /= 4f;
        return clamp01(0.30f * widthBalance + 0.25f * heightBalance
            + 0.20f * diagonalBalance + 0.25f * angleScore);
    }

    private GlareMetrics validatedGlareMetrics(boolean[] mask, LumaFrame frame,
                                               DocumentQuad quad,
                                               int minX, int maxX,
                                               int minY, int maxY,
                                               int totalPixels) {
        boolean[] visited = new boolean[mask.length];
        int acceptedPixels = 0;
        int largestAcceptedBlob = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        ArrayDeque<Integer> component = new ArrayDeque<>();
        int[] directionX = { -1, 1, 0, 0 };
        int[] directionY = { 0, 0, -1, 1 };
        int edgeInset = Math.max(4, Math.round(
            Math.min(maxX - minX, maxY - minY) * config.glareDocumentEdgeInset
        ));
        for (int y = minY; y <= maxY; y += 2) {
            for (int x = minX; x <= maxX; x += 2) {
                int start = y * frame.width + x;
                if (!mask[start] || visited[start]) {
                    continue;
                }
                visited[start] = true;
                queue.add(start);
                int size = 0;
                long componentLuma = 0L;
                int componentMinX = x;
                int componentMaxX = x;
                int componentMinY = y;
                int componentMaxY = y;
                component.clear();
                while (!queue.isEmpty()) {
                    int index = queue.removeFirst();
                    component.add(index);
                    size++;
                    int currentX = index % frame.width;
                    int currentY = index / frame.width;
                    componentLuma += frame.unsignedAt(currentX, currentY);
                    componentMinX = Math.min(componentMinX, currentX);
                    componentMaxX = Math.max(componentMaxX, currentX);
                    componentMinY = Math.min(componentMinY, currentY);
                    componentMaxY = Math.max(componentMaxY, currentY);
                    for (int direction = 0; direction < directionX.length; direction++) {
                        int nextX = currentX + directionX[direction] * 2;
                        int nextY = currentY + directionY[direction] * 2;
                        if (nextX >= minX && nextX <= maxX
                            && nextY >= minY && nextY <= maxY) {
                            int next = nextY * frame.width + nextX;
                            if (mask[next] && !visited[next]) {
                                visited[next] = true;
                                queue.add(next);
                            }
                        }
                    }
                }

                long haloLuma = 0L;
                int haloSamples = 0;
                for (int index : component) {
                    int currentX = index % frame.width;
                    int currentY = index / frame.width;
                    for (int direction = 0; direction < directionX.length; direction++) {
                        int haloX = currentX
                            + directionX[direction] * config.glareHaloSampleDistance;
                        int haloY = currentY
                            + directionY[direction] * config.glareHaloSampleDistance;
                        if (haloX < minX || haloX > maxX
                            || haloY < minY || haloY > maxY
                            || !contains(quad, haloX + 0.5f, haloY + 0.5f)) {
                            continue;
                        }
                        int haloIndex = haloY * frame.width + haloX;
                        if (!mask[haloIndex]) {
                            haloLuma += frame.unsignedAt(haloX, haloY);
                            haloSamples++;
                        }
                    }
                }

                if (haloSamples < config.minGlareHaloSamples) {
                    continue;
                }
                float componentMean = componentLuma / (float) size;
                float haloMean = haloLuma / (float) haloSamples;
                float componentFraction = size / (float) totalPixels;
                boolean nearDocumentEdge = componentMinX <= minX + edgeInset
                    || componentMaxX >= maxX - edgeInset
                    || componentMinY <= minY + edgeInset
                    || componentMaxY >= maxY - edgeInset;
                int requiredContrast = config.minGlareHaloContrast;
                if (componentFraction >= config.largeGlareComponentFraction) {
                    requiredContrast = Math.max(
                        requiredContrast,
                        config.minLargeGlareHaloContrast
                    );
                }
                if (nearDocumentEdge) {
                    requiredContrast = Math.max(
                        requiredContrast,
                        config.minEdgeGlareHaloContrast
                    );
                }
                float haloContrast = componentMean - haloMean;
                boolean nearClippedHighlight = componentMean
                        >= config.nearClippedGlareMean
                    && haloContrast >= config.minNearClippedGlareHaloContrast;
                if (haloContrast >= requiredContrast || nearClippedHighlight) {
                    acceptedPixels += size;
                    largestAcceptedBlob = Math.max(largestAcceptedBlob, size);
                }
            }
        }
        return new GlareMetrics(acceptedPixels, largestAcceptedBlob);
    }

    private static final class GlareMetrics {
        final int acceptedPixels;
        final int largestAcceptedBlob;

        GlareMetrics(int acceptedPixels, int largestAcceptedBlob) {
            this.acceptedPixels = acceptedPixels;
            this.largestAcceptedBlob = largestAcceptedBlob;
        }
    }

    private int localRange(LumaFrame frame, int x, int y) {
        int minimum = 255;
        int maximum = 0;
        for (int offsetY = -2; offsetY <= 2; offsetY++) {
            for (int offsetX = -2; offsetX <= 2; offsetX++) {
                int px = clamp(x + offsetX, 0, frame.width - 1);
                int py = clamp(y + offsetY, 0, frame.height - 1);
                int value = frame.unsignedAt(px, py);
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        return maximum - minimum;
    }

    static boolean contains(DocumentQuad quad, float x, float y) {
        PointF[] points = { quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft };
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            PointF a = points[i];
            PointF b = points[(i + 1) % 4];
            float cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
            if (Math.abs(cross) < 1e-3f) {
                continue;
            }
            if (sign == 0f) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    private static float ratioScore(float first, float second) {
        float maximum = Math.max(first, second);
        return maximum < 1e-4f ? 0f : Math.min(first, second) / maximum;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float minX(DocumentQuad quad) {
        return Math.min(Math.min(quad.topLeft.x, quad.topRight.x),
            Math.min(quad.bottomRight.x, quad.bottomLeft.x));
    }

    private static float maxX(DocumentQuad quad) {
        return Math.max(Math.max(quad.topLeft.x, quad.topRight.x),
            Math.max(quad.bottomRight.x, quad.bottomLeft.x));
    }

    private static float minY(DocumentQuad quad) {
        return Math.min(Math.min(quad.topLeft.y, quad.topRight.y),
            Math.min(quad.bottomRight.y, quad.bottomLeft.y));
    }

    private static float maxY(DocumentQuad quad) {
        return Math.max(Math.max(quad.topLeft.y, quad.topRight.y),
            Math.max(quad.bottomRight.y, quad.bottomLeft.y));
    }
}
