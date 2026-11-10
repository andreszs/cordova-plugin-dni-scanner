package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

/** Regression fixtures captured from the Xiaomi live scanner audit. */
public final class DocumentShapeGateHostTest {
    private DocumentShapeGateHostTest() {
    }

    public static void main(String[] args) {
        DocumentQuad successfulStill = quad(
            63f, 250f, 272f, 248f, 276f, 375f, 66f, 378f
        );
        assertAccepted(successfulStill, 324, 640,
            "The successful Xiaomi still must remain a valid candidate");

        DocumentQuad glareCorner = quad(
            57f, 205f, 198f, 200f, 244f, 345f, 61f, 290f
        );
        assertRejected(glareCorner, 259, 512,
            "A table-glare corner must never seed a track");

        DocumentQuad draggedBottomRight = quad(
            37.1f, 204f, 214.3f, 199.3f, 233.3f, 372.2f, 40.5f, 311.5f
        );
        assertRejected(draggedBottomRight, 259, 512,
            "A grossly displaced bottom-right corner must be rejected");

        DocumentQuad rotatedRectangle = rotatedRectangle(
            160f, 260f, 190f, 120f, 17f
        );
        assertAccepted(rotatedRectangle, 320, 520,
            "The shape gate must not confuse a valid rotated card with deformation");

        DocumentQuad moderatePerspective = quad(
            55f, 180f, 270f, 190f, 255f, 325f, 68f, 315f
        );
        assertAccepted(moderatePerspective, 324, 512,
            "Clean moderate perspective must remain detectable");
    }

    private static DocumentQuad quad(float... values) {
        return new DocumentQuad(
            new PointF(values[0], values[1]),
            new PointF(values[2], values[3]),
            new PointF(values[4], values[5]),
            new PointF(values[6], values[7])
        );
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

    private static void assertAccepted(DocumentQuad quad, int width, int height,
                                       String message) {
        if (!DocumentShapeGate.accepts(quad, width, height)) {
            throw new AssertionError(message);
        }
    }

    private static void assertRejected(DocumentQuad quad, int width, int height,
                                       String message) {
        if (DocumentShapeGate.accepts(quad, width, height)) {
            throw new AssertionError(message);
        }
    }
}
