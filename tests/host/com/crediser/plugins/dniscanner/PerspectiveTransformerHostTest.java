package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Exercises the projective warp with a skewed synthetic color card. */
public final class PerspectiveTransformerHostTest {
    private PerspectiveTransformerHostTest() {
    }

    public static void main(String[] args) {
        if (ScannerConfig.DEFAULT.maxOutputLongEdge != 1280) {
            throw new AssertionError("The default maximum output long edge must be 1280 px");
        }
        if (ScannerConfig.DEFAULT.cropPaddingPercent != ScannerConfig.CROP_PADDING_PERCENT) {
            throw new AssertionError("The default crop safety padding must be 3 percent");
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
        Bitmap corrected = transformer.transform(new Bitmap(source), quad);
        float aspect = corrected.getWidth() / (float) corrected.getHeight();
        System.out.printf("TRANSFORMED width=%d height=%d aspect=%.4f%n",
            corrected.getWidth(), corrected.getHeight(), aspect);
        if (corrected.getWidth() > ScannerConfig.DEFAULT.maxOutputLongEdge
            || Math.abs(aspect - ScannerConfig.DEFAULT.expectedDocumentAspect) > 0.01f) {
            throw new AssertionError("Unexpected perspective-corrected dimensions");
        }
        int center = corrected.getPixel(corrected.getWidth() / 2, corrected.getHeight() / 2);
        if (((center >> 16) & 0xff) < 100) {
            throw new AssertionError("The corrected card center was not sampled from the card");
        }
        int paddedCorner = corrected.getPixel(2, 2);
        if (((paddedCorner >> 16) & 0xff) > 80) {
            throw new AssertionError("The corrected image did not preserve outward safety padding");
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
            new PointF(100f, 8f),
            new PointF(900f, 8f),
            new PointF(900f, 508f),
            new PointF(100f, 508f)
        );
        if (transformer.hasSufficientSourceMargin(clippedTop, 1000, 700)) {
            throw new AssertionError("A document too close to the source boundary must be retried");
        }
        try {
            transformer.transform(new Bitmap(source), clippedTop);
            throw new AssertionError("Transform must not silently clamp incomplete crop padding");
        } catch (IllegalArgumentException expected) {
            // Expected: the scanner must retry instead of returning a clipped DNI.
        }

        ScannerConfig compactConfig = ScannerConfig.withMaxOutputLongEdge(768);
        Bitmap compact = new PerspectiveTransformer(compactConfig)
            .transform(new Bitmap(source), quad);
        float compactAspect = compact.getWidth() / (float) compact.getHeight();
        if (compact.getWidth() != 768
            || Math.abs(compactAspect - compactConfig.expectedDocumentAspect) > 0.01f) {
            throw new AssertionError("The configured output maximum was not applied");
        }

        assertInvalidMaximum(639);
        assertInvalidMaximum(2049);
    }

    private static void assertInvalidMaximum(int value) {
        try {
            ScannerConfig.withMaxOutputLongEdge(value);
            throw new AssertionError("Expected invalid maximum to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
