package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Exercises the pixel-preserving crop with a skewed synthetic color card. */
public final class PerspectiveTransformerHostTest {
    private PerspectiveTransformerHostTest() {
    }

    public static void main(String[] args) {
        if (ScannerConfig.DEFAULT.maxOutputLongEdge != 1280) {
            throw new AssertionError("The default maximum output long edge must be 1280 px");
        }
        if (ScannerConfig.DEFAULT.preferredCaptureLongEdge != 1600) {
            throw new AssertionError("The default preferred capture long edge must be 1600 px");
        }
        if (ScannerConfig.DEFAULT.minEffectiveDocumentLongEdge != 0) {
            throw new AssertionError("Effective document resolution must be opt-in by default");
        }
        if (ScannerConfig.DEFAULT.minStillSharpnessVariance != 115.0) {
            throw new AssertionError("The legacy Android still sharpness default must be preserved");
        }
        if (Math.abs(
                ScannerConfig.DEFAULT.cropPaddingFraction
                    - ScannerConfig.CROP_PADDING_PERCENT / 100f
            ) > 0.0001f) {
            throw new AssertionError("The default crop safety padding must be 6 percent");
        }

        int width = 1100;
        int height = 760;
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(new Color(35, 38, 42));
        graphics.fillRect(0, 0, width, height);
        java.awt.Polygon card = new java.awt.Polygon(
            new int[] { 130, 950, 885, 175 },
            new int[] { 90, 145, 665, 610 },
            4
        );
        graphics.setColor(new Color(205, 225, 238));
        graphics.fillPolygon(card);
        graphics.setColor(new Color(30, 90, 150));
        graphics.fillOval(250, 220, 190, 270);
        graphics.setColor(Color.BLACK);
        for (int y = 480; y < 575; y += 24) {
            graphics.fillRect(460, y, 330, 8);
        }
        graphics.dispose();

        DocumentQuad quad = new DocumentQuad(
            new PointF(130f, 90f),
            new PointF(950f, 145f),
            new PointF(885f, 665f),
            new PointF(175f, 610f)
        );
        PerspectiveTransformer transformer = new PerspectiveTransformer(ScannerConfig.DEFAULT);
        if (!transformer.hasSufficientSourceMargin(quad, width, height)) {
            throw new AssertionError("A well-contained document must pass crop-integrity validation");
        }
        Bitmap corrected = transformer.cropWithoutWarp(new Bitmap(source), quad);
        float aspect = corrected.getWidth() / (float) corrected.getHeight();
        System.out.printf("CROPPED width=%d height=%d aspect=%.4f%n",
            corrected.getWidth(), corrected.getHeight(), aspect);
        if (corrected.getWidth() > ScannerConfig.DEFAULT.maxOutputLongEdge) {
            throw new AssertionError("Unexpected cropped dimensions");
        }
        int center = corrected.getPixel(corrected.getWidth() / 2, corrected.getHeight() / 2);
        if (((center >> 16) & 0xff) < 100) {
            throw new AssertionError("The corrected card center was not sampled from the card");
        }
        int paddedCorner = corrected.getPixel(2, 2);
        if (((paddedCorner >> 16) & 0xff) > 80) {
            throw new AssertionError("The corrected image did not preserve outward safety padding");
        }
        int firstCardPixelAtQuarter = firstLightPixelFromTop(
            corrected,
            corrected.getWidth() / 4
        );
        int firstCardPixelAtThreeQuarters = firstLightPixelFromTop(
            corrected,
            corrected.getWidth() * 3 / 4
        );
        if (firstCardPixelAtQuarter < 0 || firstCardPixelAtThreeQuarters < 0
            || Math.abs(firstCardPixelAtQuarter - firstCardPixelAtThreeQuarters) < 10) {
            throw new AssertionError(
                "A pixel-preserving crop must not straighten the real document edge"
            );
        }

        DocumentQuad axisAligned = new DocumentQuad(
            new PointF(100f, 80f),
            new PointF(900f, 80f),
            new PointF(900f, 580f),
            new PointF(100f, 580f)
        );
        DocumentQuad independentlyExpanded = transformer.expandedQuad(
            axisAligned,
            ScannerConfig.DEFAULT.cropPaddingFraction,
            1000,
            700,
            0f
        );
        if (independentlyExpanded == null
            || independentlyExpanded.topLeft.x >= axisAligned.topLeft.x
            || independentlyExpanded.topLeft.y >= axisAligned.topLeft.y
            || independentlyExpanded.bottomRight.x <= axisAligned.bottomRight.x
            || independentlyExpanded.bottomRight.y <= axisAligned.bottomRight.y) {
            throw new AssertionError("Every document side must expand independently");
        }

        DocumentQuad clippedTop = new DocumentQuad(
            new PointF(100f, 3f),
            new PointF(900f, 3f),
            new PointF(900f, 503f),
            new PointF(100f, 503f)
        );
        if (transformer.hasSufficientSourceMargin(clippedTop, 1000, 700)) {
            throw new AssertionError("A document too close to the source boundary must be retried");
        }
        try {
            transformer.cropWithoutWarp(new Bitmap(source), clippedTop);
            throw new AssertionError("Crop must not silently clamp incomplete padding");
        } catch (IllegalArgumentException expected) {
            // Expected: the scanner must retry instead of returning a clipped DNI.
        }

        DocumentQuad completeNearBoundary = new DocumentQuad(
            new PointF(8f, 80f),
            new PointF(992f, 80f),
            new PointF(992f, 580f),
            new PointF(8f, 580f)
        );
        if (transformer.hasSufficientSourceMargin(completeNearBoundary, 1000, 700)) {
            throw new AssertionError(
                "A document without the full safety margin must be retried"
            );
        }
        try {
            transformer.cropWithoutWarp(new Bitmap(source), completeNearBoundary);
            throw new AssertionError("Crop must require the full safety margin");
        } catch (IllegalArgumentException expected) {
            // Expected: preserving all four borders is mandatory.
        }

        ScannerConfig compactConfig = ScannerConfig.withMaxOutputLongEdge(768);
        Bitmap compact = new PerspectiveTransformer(compactConfig)
            .cropWithoutWarp(new Bitmap(source), quad);
        float compactAspect = compact.getWidth() / (float) compact.getHeight();
        if (compact.getWidth() != 768
            || Math.abs(compactAspect - aspect) > 0.01f) {
            throw new AssertionError("The configured output maximum was not applied");
        }

        DocumentQuad lowResolutionQuad = new DocumentQuad(
            new PointF(50f, 45f),
            new PointF(550f, 45f),
            new PointF(550f, 360f),
            new PointF(50f, 360f)
        );
        BufferedImage lowResolutionSource = new BufferedImage(
            600,
            405,
            BufferedImage.TYPE_INT_ARGB
        );
        Bitmap lowResolution = transformer.cropWithoutWarp(
            new Bitmap(lowResolutionSource),
            lowResolutionQuad
        );
        if (lowResolution.getWidth() >= 640) {
            throw new AssertionError("Low-resolution captures must not be upscaled");
        }

        ScannerConfig strictStillConfig = ScannerConfig.forSession(
            2048,
            1280,
            1000,
            158.0,
            ScanProfile.DNI
        );
        if (strictStillConfig.preferredCaptureLongEdge != 2048
            || strictStillConfig.maxOutputLongEdge != 1280
            || strictStillConfig.minEffectiveDocumentLongEdge != 1000
            || strictStillConfig.minStillSharpnessVariance != 158.0
            || strictStillConfig.jpegQuality != 95) {
            throw new AssertionError("Strict still-image thresholds were not preserved");
        }

        assertInvalidMaximum(639);
        assertInvalidMaximum(2049);
        assertInvalidPreferredCapture(1279);
        assertInvalidPreferredCapture(2049);
    }

    private static int firstLightPixelFromTop(Bitmap bitmap, int x) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            int pixel = bitmap.getPixel(x, y);
            if (((pixel >> 16) & 0xff) > 150
                && ((pixel >> 8) & 0xff) > 150
                && (pixel & 0xff) > 150) {
                return y;
            }
        }
        return -1;
    }

    private static void assertInvalidMaximum(int value) {
        try {
            ScannerConfig.withMaxOutputLongEdge(value);
            throw new AssertionError("Expected invalid maximum to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertInvalidPreferredCapture(int value) {
        try {
            ScannerConfig.forSession(
                value,
                ScannerConfig.DEFAULT_MAX_OUTPUT_LONG_EDGE,
                ScanProfile.DNI
            );
            throw new AssertionError("Expected invalid preferred capture size: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
