package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

/** Ensures a strong internal rectangle can be expanded to the physical card edge. */
public final class OutwardEdgeRefinementHostTest {
    private OutwardEdgeRefinementHostTest() {
    }

    public static void main(String[] args) {
        int width = 640;
        int height = 420;
        byte[] luma = new byte[width * height];
        fill(luma, 28);
        fillRect(luma, width, 72, 58, 568, 362, 190);
        drawRect(luma, width, 98, 84, 542, 336, 12, 3);

        DocumentQuad inner = new DocumentQuad(
            new PointF(98f, 84f),
            new PointF(542f, 84f),
            new PointF(542f, 336f),
            new PointF(98f, 336f)
        );
        DocumentDetection original = new DocumentDetection(
            inner,
            0.9f,
            inner.area() / (width * (float) height),
            0f,
            444f / 252f
        );
        DocumentDetection refined = new DocumentDetector(ScannerConfig.DEFAULT)
            .refineOutwardEdges(new LumaFrame(width, height, luma), original);

        if (refined.quad.topLeft.x > 80f
            || refined.quad.topLeft.y > 66f
            || refined.quad.bottomRight.x < 560f
            || refined.quad.bottomRight.y < 354f) {
            throw new AssertionError(
                "Outward refinement did not recover the enclosing card edge: "
                    + refined.quad.topLeft.x + "," + refined.quad.topLeft.y + " - "
                    + refined.quad.bottomRight.x + "," + refined.quad.bottomRight.y
            );
        }
    }

    private static void fill(byte[] values, int value) {
        for (int index = 0; index < values.length; index++) {
            values[index] = (byte) value;
        }
    }

    private static void fillRect(byte[] values, int width, int left, int top,
                                 int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                values[y * width + x] = (byte) value;
            }
        }
    }

    private static void drawRect(byte[] values, int width, int left, int top,
                                 int right, int bottom, int value, int thickness) {
        fillRect(values, width, left, top, right, top + thickness - 1, value);
        fillRect(values, width, left, bottom - thickness + 1, right, bottom, value);
        fillRect(values, width, left, top, left + thickness - 1, bottom, value);
        fillRect(values, width, right - thickness + 1, top, right, bottom, value);
    }
}
