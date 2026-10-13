package com.crediser.plugins.dniscanner;

/**
 * Estimates periodic high-frequency interference inside a rectified document.
 *
 * <p>The detector deliberately uses only luma samples and short one-dimensional
 * autocorrelations. It adds no native or machine-learning dependency. Text and
 * security printing normally affect isolated tiles or one direction; camera/display
 * interference tends to repeat in both directions over a much larger part of the card.</p>
 */
final class MoireDetector {
    static final class Metrics {
        final float score;
        final float coverage;

        Metrics(float score, float coverage) {
            this.score = clamp01(score);
            this.coverage = clamp01(coverage);
        }
    }

    private final ScannerConfig config;

    MoireDetector(ScannerConfig config) {
        this.config = config;
    }

    Metrics evaluate(LumaFrame frame, DocumentQuad quad) {
        int width = config.moireSampleWidth;
        int height = config.moireSampleHeight;
        float[] samples = rectify(frame, quad, width, height);
        int tileWidth = width / config.moireTileColumns;
        int tileHeight = height / config.moireTileRows;
        float scoreSum = 0f;
        int validTiles = 0;
        int periodicTiles = 0;

        for (int tileY = 0; tileY < config.moireTileRows; tileY++) {
            for (int tileX = 0; tileX < config.moireTileColumns; tileX++) {
                int left = tileX * tileWidth;
                int top = tileY * tileHeight;
                int right = tileX == config.moireTileColumns - 1
                    ? width : left + tileWidth;
                int bottom = tileY == config.moireTileRows - 1
                    ? height : top + tileHeight;
                AxisPeriodicity horizontal = horizontalPeriodicity(
                    samples, width, left, top, right, bottom
                );
                AxisPeriodicity vertical = verticalPeriodicity(
                    samples, width, left, top, right, bottom
                );
                if (!horizontal.valid || !vertical.valid) {
                    continue;
                }
                validTiles++;
                float twoDimensional = (float) Math.sqrt(
                    Math.max(0f, horizontal.correlation)
                        * Math.max(0f, vertical.correlation)
                );
                // A display lattice normally produces comparable spatial periods
                // in both directions. Allow some perspective/resampling variation.
                float periodRatio = Math.min(horizontal.period, vertical.period)
                    / (float) Math.max(horizontal.period, vertical.period);
                float tileScore = twoDimensional * (0.72f + 0.28f * periodRatio);
                scoreSum += tileScore;
                if (tileScore >= config.moireTileCorrelationThreshold) {
                    periodicTiles++;
                }
            }
        }

        if (validTiles < config.moireMinimumValidTiles) {
            return new Metrics(0f, 0f);
        }
        float meanPeriodicity = scoreSum / validTiles;
        float coverage = periodicTiles / (float) validTiles;
        // Keep the public score intuitive: a strong periodic signal must also
        // cover a meaningful portion of the document.
        float score = meanPeriodicity * (0.45f + 0.55f * coverage);
        return new Metrics(score, coverage);
    }

    private AxisPeriodicity horizontalPeriodicity(float[] values, int stride,
                                                   int left, int top,
                                                   int right, int bottom) {
        int innerWidth = right - left - 2;
        int innerHeight = bottom - top;
        if (innerWidth <= config.moireMaximumLag + 1 || innerHeight < 3) {
            return AxisPeriodicity.invalid();
        }
        float[] residual = new float[innerWidth * innerHeight];
        int index = 0;
        double energy = 0.0;
        for (int y = top; y < bottom; y++) {
            for (int x = left + 1; x < right - 1; x++) {
                float value = 2f * values[y * stride + x]
                    - values[y * stride + x - 1]
                    - values[y * stride + x + 1];
                residual[index++] = value;
                energy += value * value;
            }
        }
        return bestLag(residual, innerWidth, innerHeight, energy, true);
    }

    private AxisPeriodicity verticalPeriodicity(float[] values, int stride,
                                                 int left, int top,
                                                 int right, int bottom) {
        int innerWidth = right - left;
        int innerHeight = bottom - top - 2;
        if (innerHeight <= config.moireMaximumLag + 1 || innerWidth < 3) {
            return AxisPeriodicity.invalid();
        }
        float[] residual = new float[innerWidth * innerHeight];
        int index = 0;
        double energy = 0.0;
        for (int y = top + 1; y < bottom - 1; y++) {
            for (int x = left; x < right; x++) {
                float value = 2f * values[y * stride + x]
                    - values[(y - 1) * stride + x]
                    - values[(y + 1) * stride + x];
                residual[index++] = value;
                energy += value * value;
            }
        }
        return bestLag(residual, innerWidth, innerHeight, energy, false);
    }

    private AxisPeriodicity bestLag(float[] residual, int width, int height,
                                    double energy, boolean horizontal) {
        int sampleCount = width * height;
        if (sampleCount == 0
            || Math.sqrt(energy / sampleCount) < config.moireMinimumResidualRms) {
            return AxisPeriodicity.invalid();
        }
        float best = 0f;
        int bestLag = config.moireMinimumLag;
        for (int lag = config.moireMinimumLag; lag <= config.moireMaximumLag; lag++) {
            double numerator = 0.0;
            double firstEnergy = 0.0;
            double secondEnergy = 0.0;
            if (horizontal) {
                for (int y = 0; y < height; y++) {
                    int row = y * width;
                    for (int x = 0; x + lag < width; x++) {
                        float first = residual[row + x];
                        float second = residual[row + x + lag];
                        numerator += first * second;
                        firstEnergy += first * first;
                        secondEnergy += second * second;
                    }
                }
            } else {
                for (int y = 0; y + lag < height; y++) {
                    int firstRow = y * width;
                    int secondRow = (y + lag) * width;
                    for (int x = 0; x < width; x++) {
                        float first = residual[firstRow + x];
                        float second = residual[secondRow + x];
                        numerator += first * second;
                        firstEnergy += first * first;
                        secondEnergy += second * second;
                    }
                }
            }
            if (firstEnergy <= 0.0 || secondEnergy <= 0.0) {
                continue;
            }
            float correlation = (float) (numerator
                / Math.sqrt(firstEnergy * secondEnergy));
            if (correlation > best) {
                best = correlation;
                bestLag = lag;
            }
        }
        return new AxisPeriodicity(true, best, bestLag);
    }

    private static float[] rectify(LumaFrame frame, DocumentQuad quad,
                                   int width, int height) {
        float[] result = new float[width * height];
        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                float sourceX = bilinear(
                    quad.topLeft.x, quad.topRight.x,
                    quad.bottomRight.x, quad.bottomLeft.x, u, v
                );
                float sourceY = bilinear(
                    quad.topLeft.y, quad.topRight.y,
                    quad.bottomRight.y, quad.bottomLeft.y, u, v
                );
                result[y * width + x] = sample(frame, sourceX, sourceY);
            }
        }
        return result;
    }

    private static float sample(LumaFrame frame, float x, float y) {
        float boundedX = Math.max(0f, Math.min(frame.width - 1.001f, x));
        float boundedY = Math.max(0f, Math.min(frame.height - 1.001f, y));
        int left = (int) Math.floor(boundedX);
        int top = (int) Math.floor(boundedY);
        int right = Math.min(frame.width - 1, left + 1);
        int bottom = Math.min(frame.height - 1, top + 1);
        float fx = boundedX - left;
        float fy = boundedY - top;
        float upper = frame.unsignedAt(left, top) * (1f - fx)
            + frame.unsignedAt(right, top) * fx;
        float lower = frame.unsignedAt(left, bottom) * (1f - fx)
            + frame.unsignedAt(right, bottom) * fx;
        return upper * (1f - fy) + lower * fy;
    }

    private static float bilinear(float topLeft, float topRight,
                                  float bottomRight, float bottomLeft,
                                  float u, float v) {
        float top = topLeft + (topRight - topLeft) * u;
        float bottom = bottomLeft + (bottomRight - bottomLeft) * u;
        return top + (bottom - top) * v;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class AxisPeriodicity {
        final boolean valid;
        final float correlation;
        final int period;

        AxisPeriodicity(boolean valid, float correlation, int period) {
            this.valid = valid;
            this.correlation = correlation;
            this.period = period;
        }

        static AxisPeriodicity invalid() {
            return new AxisPeriodicity(false, 0f, 1);
        }
    }
}
