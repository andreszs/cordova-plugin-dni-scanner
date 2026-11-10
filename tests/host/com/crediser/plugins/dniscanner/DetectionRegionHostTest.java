package com.crediser.plugins.dniscanner;

import android.graphics.PointF;

/** Verifies that the visual guide is decoupled from the safe detection viewport. */
public final class DetectionRegionHostTest {
    private DetectionRegionHostTest() {
    }

    public static void main(String[] args) {
        DetectionRegion region = new DetectionRegion(
            1920f, 1080f,
            496f, 280f, 1424f, 866f,
            0.10f
        );

        DocumentQuad inside = quadFromView(region, 488f, 272f, 1432f, 874f, 512, 288);
        if (!region.contains(inside, 512, 288)) {
            throw new AssertionError("The configured guide margin rejected a valid candidate");
        }

        DocumentQuad slightlyOutside = quadFromView(
            region, 468f, 252f, 1452f, 894f, 512, 288
        );
        if (!region.contains(slightlyOutside, 512, 288)) {
            throw new AssertionError(
                "A card aligned with the dotted guide needs outward hand-framing tolerance"
            );
        }

        DocumentQuad outsideGuideInsideViewport = quadFromView(
            region, 355f, 95f, 1565f, 1015f, 512, 288
        );
        if (!region.contains(outsideGuideInsideViewport, 512, 288)) {
            throw new AssertionError("The visual guide was incorrectly used as a hard ROI");
        }

        DocumentQuad touchingPhysicalBoundary = quadFromView(
            region, 3f, 3f, 1600f, 1000f, 512, 288
        );
        if (region.contains(touchingPhysicalBoundary, 512, 288)) {
            throw new AssertionError("A rectangle touching the physical viewport was accepted");
        }

        float targetArea = region.targetAreaFraction(512, 288);
        if (Math.abs(targetArea - 0.2621f) > 0.002f) {
            throw new AssertionError("Unexpected guide target area: " + targetArea);
        }
        if (Math.abs(region.marginPixels() - 58.6f) > 0.1f) {
            throw new AssertionError("Unexpected guide margin in view pixels");
        }

        float[] frameBounds = region.frameBounds(512, 288);
        float scale = Math.max(region.viewWidth / 512f, region.viewHeight / 288f);
        float offsetX = (region.viewWidth - 512f * scale) * 0.5f;
        float offsetY = (region.viewHeight - 288f * scale) * 0.5f;
        float mappedLeft = offsetX + frameBounds[0] * scale;
        float mappedTop = offsetY + frameBounds[1] * scale;
        float safeInset = Math.min(region.viewWidth, region.viewHeight) * 0.015f;
        if (Math.abs(mappedLeft - safeInset) > 1f
            || Math.abs(mappedTop - safeInset) > 1f) {
            throw new AssertionError(
                "Fallback search did not use the safe viewport"
            );
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
