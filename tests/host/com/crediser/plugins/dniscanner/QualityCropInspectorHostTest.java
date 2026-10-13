package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Diagnostic utility for evaluating a rectangular region from a captured screen. */
public final class QualityCropInspectorHostTest {
    private QualityCropInspectorHostTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected image x y width height");
        }
        BufferedImage source = ImageIO.read(new File(args[0]));
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int width = Integer.parseInt(args[3]);
        int height = Integer.parseInt(args[4]);
        BufferedImage image = source.getSubimage(x, y, width, height);
        byte[] luma = new byte[width * height];
        byte[] colorfulness = new byte[width * height];
        byte[] warmth = new byte[width * height];
        byte[] skin = new byte[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int rgb = image.getRGB(column, row);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                int index = row * width + column;
                luma[index] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
                colorfulness[index] = (byte) (Math.max(red, Math.max(green, blue))
                    - Math.min(red, Math.min(green, blue)));
                warmth[index] = (byte) Math.max(0, red - ((green + blue) / 2));
                skin[index] = (byte) skinLikelihood(red, green, blue);
            }
        }
        LumaFrame frame = new LumaFrame(width, height, luma, colorfulness, warmth, skin);
        DocumentQuad quad = new DocumentQuad(
            new PointF(2f, 2f), new PointF(width - 3f, 2f),
            new PointF(width - 3f, height - 3f), new PointF(2f, height - 3f)
        );
        QualityEvaluator evaluator = new QualityEvaluator(ScannerConfig.DEFAULT);
        QualityMetrics metrics = evaluator.evaluate(frame, quad);
        System.out.printf(
            "sharpness=%.1f brightness=%.1f clipped=%.3f glare=%.3f blob=%.3f illumination=%.3f shadow=%.3f edgeGap=%.3f warmRun=%.3f skinRun=%.3f state=%s%n",
            metrics.sharpness, metrics.brightness, metrics.clippedFraction,
            metrics.glareFraction, metrics.glareBlobFraction,
            metrics.illuminationSpread, metrics.shadowBoundary,
            metrics.edgeGapFraction, metrics.warmEdgeRunFraction,
            metrics.skinEdgeRunFraction,
            evaluator.classify(
                new DocumentDetection(quad, 0.95f, 0.55f, 0f, 1.59f),
                metrics
            )
        );
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
