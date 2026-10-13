package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public final class MoireDetectorHostTest {
    private MoireDetectorHostTest() {
    }

    public static void main(String[] args) throws Exception {
        assertSyntheticPeriodicity();
        assertCheckIsOptIn();
        for (String path : args) {
            BufferedImage image = ImageIO.read(new File(path));
            LumaFrame frame = fromImage(image);
            DocumentQuad quad = fullQuad(frame.width, frame.height);
            MoireDetector.Metrics metrics = new MoireDetector(ScannerConfig.DEFAULT)
                .evaluate(frame, quad);
            System.out.printf("%s moire=%.4f coverage=%.4f%n",
                new File(path).getName(), metrics.score, metrics.coverage);
        }
    }

    private static void assertCheckIsOptIn() {
        DocumentQuad quad = fullQuad(320, 200);
        DocumentDetection detection = new DocumentDetection(quad, 0.95f, 0.50f, 0f, 1.6f);
        QualityMetrics quality = new QualityMetrics(
            1000.0, 128f, 0f, 0f, 0f, 0f, 0.95f,
            0f, 0f, 0f, 0f, 0f, 0.50f, 0.75f
        );
        QualityEvaluator evaluator = new QualityEvaluator(ScannerConfig.DEFAULT);
        if (evaluator.classify(detection, quality) != AnalysisResult.State.READY) {
            throw new AssertionError("Moiré must not block with default checks.");
        }
        ScanChecks enabled = new ScanChecks(
            true, true, true, true, true, true, true, true, true, true, true
        );
        if (evaluator.classify(detection, quality, enabled)
            != AnalysisResult.State.SCREEN_MOIRE) {
            throw new AssertionError("Moiré must block when explicitly enabled.");
        }
    }

    private static void assertSyntheticPeriodicity() {
        int width = 320;
        int height = 200;
        byte[] plain = new byte[width * height];
        byte[] periodic = new byte[width * height];
        int random = 0x13579bdf;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                random ^= random << 13;
                random ^= random >>> 17;
                random ^= random << 5;
                plain[index] = (byte) (150 + (random & 15));
                int pattern = ((x / 3 + y / 3) & 1) == 0 ? 32 : -32;
                periodic[index] = (byte) (160 + pattern);
            }
        }
        MoireDetector detector = new MoireDetector(ScannerConfig.DEFAULT);
        DocumentQuad quad = fullQuad(width, height);
        MoireDetector.Metrics plainMetrics = detector.evaluate(
            new LumaFrame(width, height, plain), quad
        );
        MoireDetector.Metrics periodicMetrics = detector.evaluate(
            new LumaFrame(width, height, periodic), quad
        );
        if (periodicMetrics.score <= plainMetrics.score + 0.15f
            || periodicMetrics.coverage < 0.50f) {
            throw new AssertionError("Periodic screen pattern was not separated: plain="
                + plainMetrics.score + " periodic=" + periodicMetrics.score);
        }
    }

    private static LumaFrame fromImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] luma = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                luma[y * width + x] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
            }
        }
        return new LumaFrame(width, height, luma);
    }

    private static DocumentQuad fullQuad(int width, int height) {
        return new DocumentQuad(
            new PointF(0f, 0f),
            new PointF(width - 1f, 0f),
            new PointF(width - 1f, height - 1f),
            new PointF(0f, height - 1f)
        );
    }
}
