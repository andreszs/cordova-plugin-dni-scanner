package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

/** Verifies that document candidates cannot escape the visible dotted guide. */
public final class DetectionRegionHostTest {
    private DetectionRegionHostTest() {
    }

    public static void main(String[] args) {
        DetectionRegion region = new DetectionRegion(
            1920f, 1080f,
            496f, 280f, 1424f, 866f,
            0.03f
        );

        DocumentQuad inside = quadFromView(region, 488f, 272f, 1432f, 874f, 512, 288);
        if (!region.contains(inside, 512, 288)) {
            throw new AssertionError("The configured guide margin rejected a valid candidate");
        }

        DocumentQuad enclosingWindow = quadFromView(
            region, 355f, 95f, 1608f, 1015f, 512, 288
        );
        if (region.contains(enclosingWindow, 512, 288)) {
            throw new AssertionError("A rectangle outside the dotted guide was accepted");
        }

        float targetArea = region.targetAreaFraction(512, 288);
        if (Math.abs(targetArea - 0.2621f) > 0.002f) {
            throw new AssertionError("Unexpected guide target area: " + targetArea);
        }
        if (Math.abs(region.marginPixels() - 17.58f) > 0.1f) {
            throw new AssertionError("Unexpected guide margin in view pixels");
        }
    }

    private static DocumentQuad quadFromView(DetectionRegion region, float left, float top,
                                              float right, float bottom,
                                              int frameWidth, int frameHeight) {
        float scale = Math.max(
            region.viewWidth / frameWidth,
            region.viewHeight / frameHeight
        );
        float offsetX = (region.viewWidth - frameWidth * scale) * 0.5f;
        float offsetY = (region.viewHeight - frameHeight * scale) * 0.5f;
        return new DocumentQuad(
            point(left, top, scale, offsetX, offsetY),
            point(right, top, scale, offsetX, offsetY),
            point(right, bottom, scale, offsetX, offsetY),
            point(left, bottom, scale, offsetX, offsetY)
        );
    }

    private static PointF point(float viewX, float viewY, float scale,
                                float offsetX, float offsetY) {
        return new PointF((viewX - offsetX) / scale, (viewY - offsetY) / scale);
    }
}
