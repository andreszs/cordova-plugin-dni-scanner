package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Deterministic host tests for exposure, focus and severe-glare gates. */
public final class QualityEvaluatorHostTest {
    private static final int WIDTH = 480;
    private static final int HEIGHT = 300;
    private static final DocumentQuad QUAD = new DocumentQuad(
        new PointF(8f, 8f),
        new PointF(WIDTH - 9f, 8f),
        new PointF(WIDTH - 9f, HEIGHT - 9f),
        new PointF(8f, HEIGHT - 9f)
    );

    private QualityEvaluatorHostTest() {
    }

    public static void main(String[] args) {
        ScannerConfig config = ScannerConfig.DEFAULT;
        QualityEvaluator evaluator = new QualityEvaluator(config);
        DocumentDetection detection = new DocumentDetection(QUAD, 0.95f, 0.55f, 0f, 1.59f);

        assertState(evaluator, detection, textured(128), AnalysisResult.State.READY, "textured");
        assertState(evaluator, detection, constant(128), AnalysisResult.State.WAITING_FOR_FOCUS,
            "out-of-focus");
        assertState(evaluator, detection, constant(18), AnalysisResult.State.IMPROVE_LIGHTING,
            "underexposed");
        assertState(evaluator, detection, constant(242), AnalysisResult.State.REDUCE_LIGHTING,
            "overexposed");
        assertState(evaluator, detection, withGlare(textured(128)), AnalysisResult.State.AVOID_GLARE,
            "severe-glare");
        assertState(evaluator, detection, withModerateGlare(textured(128)),
            AnalysisResult.State.AVOID_GLARE, "specular-glare-below-clipping");
        assertState(evaluator, detection, withPalePrintedPanel(textured(200)),
            AnalysisResult.State.READY, "pale-printed-panel-is-not-glare");
        assertState(evaluator, detection, withGlareOnPaleCard(softTextured(205)),
            AnalysisResult.State.AVOID_GLARE, "localized-glare-on-pale-card");
        assertState(evaluator, detection, withWarmEdgeObstruction(textured(128)),
            AnalysisResult.State.EDGE_OCCLUDED, "finger-over-edge");
        assertState(evaluator, detection, withSkinEdgeObstruction(textured(128)),
            AnalysisResult.State.EDGE_OCCLUDED, "skin-over-edge");
        assertState(evaluator, detection, withSkinInsideCardOnly(textured(128)),
            AnalysisResult.State.READY, "skin-colored-card-edge-without-crossing");
        assertState(evaluator, detection, withUnevenLighting(textured(158)),
            AnalysisResult.State.UNEVEN_LIGHTING, "uneven-lighting");
        assertStateWithChecks(
            evaluator,
            detection,
            withSkinEdgeObstruction(textured(128)),
            new ScanChecks(true, true, true, true, true, true, true, true, false),
            AnalysisResult.State.READY,
            "edge-occlusion-disabled"
        );
        assertStateWithChecks(
            evaluator,
            detection,
            withUnevenLighting(textured(158)),
            new ScanChecks(true, true, true, true, true, true, true, false, true),
            AnalysisResult.State.READY,
            "shadows-disabled"
        );
        assertStillEdgePolicy(evaluator);
        assertGenericCardPolicy(detection);

        if (args.length == 1) {
            inspectReferences(evaluator, new File(args[0]));
        }
    }

    private static void assertGenericCardPolicy(DocumentDetection detection) {
        ScannerConfig genericConfig = ScannerConfig.forSession(
            ScannerConfig.DEFAULT_MAX_OUTPUT_LONG_EDGE,
            ScannerConfig.DEFAULT_GUIDE_MARGIN_PERCENT,
            ScannerConfig.DEFAULT_CROP_PADDING_PERCENT,
            ScanProfile.GENERIC_CARD
        );
        QualityEvaluator evaluator = new QualityEvaluator(genericConfig);
        QualityMetrics brandedCard = new QualityMetrics(
            500.0, 170f, 0.02f, 0.08f, 0.17f, 0.16f, 1f,
            0.03f, 0.32f, 0.325f, 0.303f, 0.32f
        );
        AnalysisResult.State actual = evaluator.classify(detection, brandedCard);
        if (actual != AnalysisResult.State.READY) {
            throw new AssertionError(
                "generic-card-colors: expected READY but got " + actual
            );
        }
        System.out.println("generic-card-colors expected=READY actual=READY");
    }

    private static void assertStillEdgePolicy(QualityEvaluator evaluator) {
        QualityMetrics moderateSkinOnly = edgeMetrics(0.00f, 0.14f);
        QualityMetrics dniV2FalsePositive = edgeMetrics(0.00f, 0.25f);
        QualityMetrics moderateSkinWithWeakGap = edgeMetrics(0.09f, 0.14f);
        QualityMetrics moderateSkinWithCorroboratingGap = edgeMetrics(0.11f, 0.14f);
        QualityMetrics strongPreviewSkin = edgeMetrics(0.00f, 0.31f);
        QualityMetrics strongStillSkin = edgeMetrics(0.00f, 0.36f);

        assertBoolean(evaluator.hasEdgeOcclusion(moderateSkinOnly), false,
            "preview-moderate-skin");
        assertBoolean(evaluator.hasEdgeOcclusion(dniV2FalsePositive), false,
            "preview-dni-v2-pink-substrate");
        assertBoolean(evaluator.hasStillEdgeOcclusion(dniV2FalsePositive), false,
            "still-dni-v2-pink-substrate");
        assertBoolean(evaluator.hasStillEdgeOcclusion(moderateSkinOnly), false,
            "still-moderate-skin-only");
        assertBoolean(evaluator.hasEdgeOcclusion(moderateSkinWithWeakGap), false,
            "preview-moderate-skin-weak-gap");
        assertBoolean(evaluator.hasStillEdgeOcclusion(moderateSkinWithWeakGap), false,
            "still-moderate-skin-weak-gap");
        assertBoolean(evaluator.hasEdgeOcclusion(moderateSkinWithCorroboratingGap), true,
            "preview-moderate-skin-corroborated");
        assertBoolean(evaluator.hasStillEdgeOcclusion(moderateSkinWithCorroboratingGap), true,
            "still-moderate-skin-corroborated");
        assertBoolean(evaluator.hasEdgeOcclusion(strongPreviewSkin), true,
            "preview-strong-skin");
        assertBoolean(evaluator.hasStillEdgeOcclusion(strongPreviewSkin), false,
            "still-preview-only-strong-skin");
        assertBoolean(evaluator.hasStillEdgeOcclusion(strongStillSkin), true,
            "still-strong-skin");
    }

    private static QualityMetrics edgeMetrics(float edgeGap, float skinRun) {
        return new QualityMetrics(
            500.0, 128f, 0f, 0f, 0f, 0f, 1f,
            edgeGap, 0f, 0f, 0f, skinRun
        );
    }

    private static void assertBoolean(boolean actual, boolean expected, String name) {
        System.out.printf("%s expected=%s actual=%s%n", name, expected, actual);
        if (actual != expected) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertState(QualityEvaluator evaluator, DocumentDetection detection,
                                    LumaFrame frame, AnalysisResult.State expected, String name) {
        QualityMetrics metrics = evaluator.evaluate(frame, QUAD);
        AnalysisResult.State actual = evaluator.classify(detection, metrics);
        System.out.printf(
            "%s expected=%s actual=%s sharpness=%.1f brightness=%.1f glare=%.3f blob=%.3f edgeGap=%.3f warmRun=%.3f skinRun=%.3f illumination=%.3f shadow=%.3f%n",
            name, expected, actual, metrics.sharpness, metrics.brightness,
            metrics.glareFraction, metrics.glareBlobFraction,
            metrics.edgeGapFraction, metrics.warmEdgeRunFraction,
            metrics.skinEdgeRunFraction,
            metrics.illuminationSpread, metrics.shadowBoundary
        );
        if (actual != expected) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertStateWithChecks(QualityEvaluator evaluator,
                                              DocumentDetection detection,
                                              LumaFrame frame, ScanChecks checks,
                                              AnalysisResult.State expected, String name) {
        QualityMetrics metrics = evaluator.evaluate(frame, QUAD);
        AnalysisResult.State actual = evaluator.classify(detection, metrics, checks);
        System.out.printf("%s expected=%s actual=%s%n", name, expected, actual);
        if (actual != expected) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }

    private static LumaFrame constant(int value) {
        byte[] data = new byte[WIDTH * HEIGHT];
        java.util.Arrays.fill(data, (byte) value);
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame textured(int base) {
        byte[] data = new byte[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int pattern = ((x / 5 + y / 7) & 1) == 0 ? -34 : 34;
                data[y * WIDTH + x] = (byte) Math.max(0, Math.min(255, base + pattern));
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame softTextured(int base) {
        byte[] data = new byte[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int pattern = ((x / 5 + y / 7) & 1) == 0 ? -12 : 12;
                data[y * WIDTH + x] = (byte) Math.max(0, Math.min(255, base + pattern));
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame withGlare(LumaFrame source) {
        byte[] data = source.data.clone();
        for (int y = 70; y < 220; y++) {
            for (int x = 130; x < 350; x++) {
                data[y * WIDTH + x] = (byte) 250;
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame withModerateGlare(LumaFrame source) {
        byte[] data = source.data.clone();
        for (int y = 82; y < 208; y++) {
            for (int x = 145; x < 335; x++) {
                data[y * WIDTH + x] = (byte) 226;
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame withPalePrintedPanel(LumaFrame source) {
        byte[] data = source.data.clone();
        // Roughly 12% of the card: representative of a smooth white printed
        // region on otherwise pale DNI stock. Its contrast is too gradual to
        // be a specular reflection.
        for (int y = 82; y < 172; y++) {
            for (int x = 120; x < 310; x++) {
                data[y * WIDTH + x] = (byte) 232;
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame withGlareOnPaleCard(LumaFrame source) {
        byte[] data = source.data.clone();
        for (int y = 82; y < 188; y++) {
            for (int x = 135; x < 325; x++) {
                data[y * WIDTH + x] = (byte) 250;
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static LumaFrame withWarmEdgeObstruction(LumaFrame source) {
        byte[] colorfulness = new byte[WIDTH * HEIGHT];
        byte[] warmth = new byte[WIDTH * HEIGHT];
        for (int y = Math.round(HEIGHT * 0.86f); y < HEIGHT; y++) {
            for (int x = Math.round(WIDTH * 0.48f); x < Math.round(WIDTH * 0.92f); x++) {
                warmth[y * WIDTH + x] = (byte) 100;
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, source.data.clone(), colorfulness, warmth);
    }

    private static LumaFrame withSkinEdgeObstruction(LumaFrame source) {
        byte[] colorfulness = new byte[WIDTH * HEIGHT];
        byte[] warmth = new byte[WIDTH * HEIGHT];
        byte[] skin = new byte[WIDTH * HEIGHT];
        for (int y = 0; y < Math.round(HEIGHT * 0.11f); y++) {
            for (int x = Math.round(WIDTH * 0.04f); x < Math.round(WIDTH * 0.42f); x++) {
                skin[y * WIDTH + x] = (byte) 90;
            }
        }
        return new LumaFrame(
            WIDTH,
            HEIGHT,
            source.data.clone(),
            colorfulness,
            warmth,
            skin
        );
    }

    private static LumaFrame withSkinInsideCardOnly(LumaFrame source) {
        byte[] colorfulness = new byte[WIDTH * HEIGHT];
        byte[] warmth = new byte[WIDTH * HEIGHT];
        byte[] skin = new byte[WIDTH * HEIGHT];
        for (int y = 8; y < Math.round(HEIGHT * 0.13f); y++) {
            for (int x = Math.round(WIDTH * 0.04f); x < Math.round(WIDTH * 0.30f); x++) {
                skin[y * WIDTH + x] = (byte) 90;
            }
        }
        return new LumaFrame(
            WIDTH,
            HEIGHT,
            source.data.clone(),
            colorfulness,
            warmth,
            skin
        );
    }

    private static LumaFrame withUnevenLighting(LumaFrame source) {
        byte[] data = source.data.clone();
        int transitionStart = Math.round(WIDTH * 0.44f);
        int transitionEnd = Math.round(WIDTH * 0.56f);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                float factor;
                if (x <= transitionStart) {
                    factor = 0.58f;
                } else if (x >= transitionEnd) {
                    factor = 1f;
                } else {
                    factor = 0.58f + 0.42f * (x - transitionStart)
                        / (float) (transitionEnd - transitionStart);
                }
                int index = y * WIDTH + x;
                data[index] = (byte) Math.round((data[index] & 0xff) * factor);
            }
        }
        return new LumaFrame(WIDTH, HEIGHT, data);
    }

    private static void inspectReferences(QualityEvaluator evaluator, File directory) {
        File[] files = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        });
        if (files == null) {
            throw new AssertionError("Reference directory is not readable: " + directory);
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            try {
                BufferedImage image = ImageIO.read(file);
                int targetWidth = WIDTH;
                int targetHeight = Math.max(1, Math.round(image.getHeight() * WIDTH
                    / (float) image.getWidth()));
                BufferedImage scaled = new BufferedImage(
                    targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB
                );
                java.awt.Graphics2D graphics = scaled.createGraphics();
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
                graphics.dispose();
                byte[] luma = new byte[targetWidth * targetHeight];
                byte[] colorfulness = new byte[targetWidth * targetHeight];
                byte[] warmth = new byte[targetWidth * targetHeight];
                byte[] skin = new byte[targetWidth * targetHeight];
                for (int y = 0; y < targetHeight; y++) {
                    for (int x = 0; x < targetWidth; x++) {
                        int rgb = scaled.getRGB(x, y);
                        int red = (rgb >>> 16) & 0xff;
                        int green = (rgb >>> 8) & 0xff;
                        int blue = rgb & 0xff;
                        luma[y * targetWidth + x] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
                        colorfulness[y * targetWidth + x] = (byte) (
                            Math.max(red, Math.max(green, blue))
                                - Math.min(red, Math.min(green, blue))
                        );
                        warmth[y * targetWidth + x] = (byte) Math.max(
                            0, red - ((green + blue) / 2)
                        );
                        skin[y * targetWidth + x] = (byte) skinLikelihood(red, green, blue);
                    }
                }
                LumaFrame frame = new LumaFrame(
                    targetWidth, targetHeight, luma, colorfulness, warmth, skin
                );
                DocumentQuad quad = new DocumentQuad(
                    new PointF(1f, 1f),
                    new PointF(targetWidth - 2f, 1f),
                    new PointF(targetWidth - 2f, targetHeight - 2f),
                    new PointF(1f, targetHeight - 2f)
                );
                DocumentDetection referenceDetection = new DocumentDetection(
                    quad, 0.95f, 0.55f, 0f, 1.59f
                );
                QualityMetrics metrics = evaluator.evaluate(frame, quad);
                AnalysisResult.State state = evaluator.classify(referenceDetection, metrics);
                System.out.printf(
                    "reference=%s state=%s sharpness=%.1f brightness=%.1f clipped=%.3f glare=%.3f blob=%.3f edgeGap=%.3f warmRun=%.3f skinRun=%.3f illumination=%.3f shadow=%.3f%n",
                    file.getName(), state, metrics.sharpness, metrics.brightness,
                    metrics.clippedFraction, metrics.glareFraction, metrics.glareBlobFraction,
                    metrics.edgeGapFraction, metrics.warmEdgeRunFraction,
                    metrics.skinEdgeRunFraction,
                    metrics.illuminationSpread, metrics.shadowBoundary
                );
            } catch (Exception error) {
                throw new AssertionError("Could not inspect " + file, error);
            }
        }
    }

    private static int skinLikelihood(int red, int green, int blue) {
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        int cb = clamp(Math.round(128f - 0.169f * red - 0.331f * green + 0.5f * blue));
        int cr = clamp(Math.round(128f + 0.5f * red - 0.419f * green - 0.081f * blue));
        boolean plausibleTone = red > 45 && green > 25 && blue > 15
            && maximum - minimum >= 16 && red >= green + 6 && red >= blue + 10;
        boolean plausibleChroma = cb >= 72 && cb <= 132 && cr >= 132 && cr <= 184;
        if (!plausibleTone || !plausibleChroma) {
            return 0;
        }
        int cbScore = 100 - Math.min(100, Math.abs(cb - 102) * 3);
        int crScore = 100 - Math.min(100, Math.abs(cr - 156) * 3);
        return clamp(Math.min(cbScore, crScore));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
