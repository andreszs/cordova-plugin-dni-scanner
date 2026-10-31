package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

/** Verifies the mandatory worst-corner perspective gate independently of roll. */
public final class PerspectiveGateHostTest {
    private PerspectiveGateHostTest() {
    }

    public static void main(String[] args) {
        DocumentQuad rolledRectangle = rotatedRectangle(500f, 350f, 800f, 500f, 17f);
        assertDeviation(rolledRectangle, 0f, 0.01f,
            "In-plane rotation must not be confused with perspective");
        if (rolledRectangle.maximumInPlaneRotationDegrees() < 16.9f) {
            throw new AssertionError("Large in-plane rotation was not measured");
        }

        DocumentQuad alignedRectangle = rotatedRectangle(500f, 350f, 800f, 500f, 1.5f);
        if (alignedRectangle.maximumInPlaneRotationDegrees()
            > ScannerConfig.MAX_IN_PLANE_ROTATION_DEGREES) {
            throw new AssertionError("A rectangle within the roll tolerance was rejected");
        }

        DocumentQuad cleanTrapezoid = new DocumentQuad(
            new PointF(150f, 100f), new PointF(850f, 100f),
            new PointF(900f, 600f), new PointF(100f, 600f)
        );
        if (!acceptable(cleanTrapezoid)) {
            throw new AssertionError("A clean near-isosceles trapezoid was rejected");
        }

        DocumentQuad skewedParallelogram = new DocumentQuad(
            new PointF(120f, 100f), new PointF(920f, 100f),
            new PointF(900f, 600f), new PointF(100f, 600f)
        );
        if (acceptable(skewedParallelogram)) {
            throw new AssertionError("An asymmetrically skewed quadrilateral was accepted");
        }

        DocumentQuad excessiveTrapezoid = new DocumentQuad(
            new PointF(180f, 100f), new PointF(820f, 100f),
            new PointF(900f, 600f), new PointF(100f, 600f)
        );
        if (acceptable(excessiveTrapezoid)) {
            throw new AssertionError("An excessive symmetric perspective was accepted");
        }
    }

    private static DocumentQuad rotatedRectangle(float centerX, float centerY,
                                                  float width, float height,
                                                  float degrees) {
        double radians = Math.toRadians(degrees);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new DocumentQuad(
            rotate(centerX, centerY, -width / 2f, -height / 2f, cosine, sine),
            rotate(centerX, centerY, width / 2f, -height / 2f, cosine, sine),
            rotate(centerX, centerY, width / 2f, height / 2f, cosine, sine),
            rotate(centerX, centerY, -width / 2f, height / 2f, cosine, sine)
        );
    }

    private static PointF rotate(float centerX, float centerY, float x, float y,
                                 float cosine, float sine) {
        return new PointF(
            centerX + x * cosine - y * sine,
            centerY + x * sine + y * cosine
        );
    }

    private static void assertDeviation(DocumentQuad quad, float expected, float tolerance,
                                        String message) {
        float actual = quad.maximumPerspectiveDeviationDegrees();
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(message + ": " + actual);
        }
    }

    private static boolean acceptable(DocumentQuad quad) {
        return quad.hasAcceptablePerspective(
            ScannerConfig.MAX_RECTANGLE_DEVIATION_DEGREES,
            ScannerConfig.MAX_SYMMETRIC_PERSPECTIVE_DEVIATION_DEGREES,
            ScannerConfig.MAX_TRAPEZOID_SYMMETRY_ERROR_DEGREES
        );
    }
}
