package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

final class PerspectiveTransformer {
    static final class CropResult {
        final Bitmap bitmap;
        final boolean reducedPadding;

        CropResult(Bitmap bitmap, boolean reducedPadding) {
            this.bitmap = bitmap;
            this.reducedPadding = reducedPadding;
        }
    }

    private static final class CropPlan {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final boolean reducedPadding;

        CropPlan(int left, int top, int right, int bottom, boolean reducedPadding) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.reducedPadding = reducedPadding;
        }
    }

    private final ScannerConfig config;

    PerspectiveTransformer(ScannerConfig config) {
        this.config = config;
    }

    Bitmap cropWithoutWarp(Bitmap source, DocumentQuad originalQuad) {
        return cropWithoutWarpAdaptive(source, originalQuad).bitmap;
    }

    CropResult cropWithoutWarpAdaptive(Bitmap source, DocumentQuad originalQuad) {
        CropPlan plan = adaptiveCropPlan(
            originalQuad, source.getWidth(), source.getHeight()
        );
        if (plan == null) {
            throw new IllegalArgumentException(
                "No se pudo confirmar el margen mínimo alrededor del documento."
            );
        }
        int width = plan.right - plan.left;
        int height = plan.bottom - plan.top;
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("El recorte detectado no tiene dimensiones válidas.");
        }
        Bitmap cropped = Bitmap.createBitmap(source, plan.left, plan.top, width, height);
        int longEdge = Math.max(width, height);
        if (longEdge <= config.maxOutputLongEdge) {
            return new CropResult(cropped, plan.reducedPadding);
        }
        float scale = config.maxOutputLongEdge / (float) longEdge;
        Bitmap resized = Bitmap.createScaledBitmap(
            cropped,
            Math.max(1, Math.round(width * scale)),
            Math.max(1, Math.round(height * scale)),
            true
        );
        cropped.recycle();
        return new CropResult(resized, plan.reducedPadding);
    }

    private float minimumX(DocumentQuad quad) {
        return Math.min(Math.min(quad.topLeft.x, quad.topRight.x),
            Math.min(quad.bottomRight.x, quad.bottomLeft.x));
    }

    private float maximumX(DocumentQuad quad) {
        return Math.max(Math.max(quad.topLeft.x, quad.topRight.x),
            Math.max(quad.bottomRight.x, quad.bottomLeft.x));
    }

    private float minimumY(DocumentQuad quad) {
        return Math.min(Math.min(quad.topLeft.y, quad.topRight.y),
            Math.min(quad.bottomRight.y, quad.bottomLeft.y));
    }

    private float maximumY(DocumentQuad quad) {
        return Math.max(Math.max(quad.topLeft.y, quad.topRight.y),
            Math.max(quad.bottomRight.y, quad.bottomLeft.y));
    }

    int effectiveDocumentLongEdge(DocumentQuad quad) {
        float averageWidth = (quad.topWidth() + quad.bottomWidth()) * 0.5f;
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        return Math.max(1, Math.round(Math.max(
            averageWidth,
            averageHeight * config.expectedDocumentAspect
        )));
    }

    boolean hasSufficientSourceMargin(DocumentQuad quad, int sourceWidth, int sourceHeight) {
        return adaptiveCropPlan(quad, sourceWidth, sourceHeight) != null;
    }

    boolean usesReducedPadding(DocumentQuad quad, int sourceWidth, int sourceHeight) {
        CropPlan plan = adaptiveCropPlan(quad, sourceWidth, sourceHeight);
        return plan != null && plan.reducedPadding;
    }

    /** Confirms that every detected side separates card pixels from exterior pixels. */
    boolean hasCompletePhysicalBorder(LumaFrame frame, DocumentQuad quad) {
        if (frame == null || quad == null) {
            return false;
        }
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        float aspect = (quad.topWidth() + quad.bottomWidth()) * 0.5f
            / Math.max(1f, averageHeight);
        if (aspect < ScannerConfig.MINIMUM_STILL_DOCUMENT_ASPECT
            || aspect > ScannerConfig.MAXIMUM_STILL_DOCUMENT_ASPECT) {
            return false;
        }
        float offset = Math.max(3f, Math.min(7f, averageHeight * 0.018f));
        PointF[] points = {
            quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft
        };
        for (int side = 0; side < 4; side++) {
            if (!sideSeparatesFromExterior(
                    frame, points[side], points[(side + 1) % 4], offset)) {
                return false;
            }
        }
        return true;
    }

    private boolean sideSeparatesFromExterior(LumaFrame frame, PointF first,
                                               PointF second, float offset) {
        float dx = second.x - first.x;
        float dy = second.y - first.y;
        float length = (float) Math.hypot(dx, dy);
        if (length < 2f) {
            return false;
        }
        float outwardX = dy / length;
        float outwardY = -dx / length;
        int compared = 0;
        int separated = 0;
        float totalSeparation = 0f;
        for (int sample = 0; sample < 32; sample++) {
            float t = 0.08f + 0.84f * (sample + 0.5f) / 32f;
            float x = first.x + dx * t;
            float y = first.y + dy * t;
            int insideX = Math.round(x - outwardX * offset);
            int insideY = Math.round(y - outwardY * offset);
            int outsideX = Math.round(x + outwardX * offset);
            int outsideY = Math.round(y + outwardY * offset);
            if (!inside(frame, insideX, insideY) || !inside(frame, outsideX, outsideY)) {
                continue;
            }
            float difference = Math.abs(
                frame.unsignedAt(insideX, insideY) - frame.unsignedAt(outsideX, outsideY)
            );
            int insideColor = frame.colorfulnessAt(insideX, insideY);
            int outsideColor = frame.colorfulnessAt(outsideX, outsideY);
            if (insideColor >= 0 && outsideColor >= 0) {
                difference = Math.max(
                    difference, Math.abs(insideColor - outsideColor) * 0.5f
                );
            }
            int insideWarmth = frame.warmthAt(insideX, insideY);
            int outsideWarmth = frame.warmthAt(outsideX, outsideY);
            if (insideWarmth >= 0 && outsideWarmth >= 0) {
                difference = Math.max(
                    difference, Math.abs(insideWarmth - outsideWarmth) * 0.35f
                );
            }
            compared++;
            totalSeparation += difference;
            if (difference >= 8f) {
                separated++;
            }
        }
        return compared >= 24
            && (totalSeparation / compared >= ScannerConfig.MINIMUM_PHYSICAL_BORDER_SEPARATION
                || separated / (float) compared
                    >= ScannerConfig.MINIMUM_SEPARATED_BORDER_FRACTION);
    }

    private static boolean inside(LumaFrame frame, int x, int y) {
        return x >= 0 && x < frame.width && y >= 0 && y < frame.height;
    }

    /**
     * Keeps the complete detected quadrilateral and chooses the available padding
     * independently on every output side. The 1.75% clearance is a hard proof
     * that the physical edge is present; the 6% padding remains only a target.
     */
    private CropPlan adaptiveCropPlan(DocumentQuad quad, int sourceWidth, int sourceHeight) {
        if (quad == null || sourceWidth < 2 || sourceHeight < 2) {
            return null;
        }
        float minX = minimumX(quad);
        float maxX = maximumX(quad);
        float minY = minimumY(quad);
        float maxY = maximumY(quad);
        float averageWidth = (quad.topWidth() + quad.bottomWidth()) * 0.5f;
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        if (averageWidth < 1f || averageHeight < 1f) {
            return null;
        }
        float guard = Math.max(0f, config.cropIntegrityGuardPixels);
        float minimumXPadding = averageWidth * config.minimumCropIntegrityClearanceFraction;
        float minimumYPadding = averageHeight * config.minimumCropIntegrityClearanceFraction;
        float availableLeft = minX - guard;
        float availableRight = sourceWidth - 1f - guard - maxX;
        float availableTop = minY - guard;
        float availableBottom = sourceHeight - 1f - guard - maxY;
        if (availableLeft < minimumXPadding || availableRight < minimumXPadding
            || availableTop < minimumYPadding || availableBottom < minimumYPadding) {
            return null;
        }

        float desiredXPadding = averageWidth * config.cropPaddingFraction;
        float desiredYPadding = averageHeight * config.cropPaddingFraction;
        float leftPadding = Math.min(desiredXPadding, availableLeft);
        float rightPadding = Math.min(desiredXPadding, availableRight);
        float topPadding = Math.min(desiredYPadding, availableTop);
        float bottomPadding = Math.min(desiredYPadding, availableBottom);
        boolean reduced = leftPadding + 0.5f < desiredXPadding
            || rightPadding + 0.5f < desiredXPadding
            || topPadding + 0.5f < desiredYPadding
            || bottomPadding + 0.5f < desiredYPadding;
        int left = Math.max(0, (int) Math.floor(minX - leftPadding));
        int top = Math.max(0, (int) Math.floor(minY - topPadding));
        int right = Math.min(sourceWidth, (int) Math.ceil(maxX + rightPadding) + 1);
        int bottom = Math.min(sourceHeight, (int) Math.ceil(maxY + bottomPadding) + 1);
        return right - left >= 2 && bottom - top >= 2
            ? new CropPlan(left, top, right, bottom, reduced)
            : null;
    }

    /**
     * Expands every physical side independently. Unlike center scaling, one side near
     * the image boundary cannot silently reduce or cancel the padding on all sides.
     */
    DocumentQuad expandedQuad(DocumentQuad quad, float fraction, int sourceWidth,
                              int sourceHeight, float guardPixels) {
        if (quad == null || sourceWidth < 2 || sourceHeight < 2) {
            return null;
        }
        float averageWidth = (quad.topWidth() + quad.bottomWidth()) * 0.5f;
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        if (averageWidth < 1f || averageHeight < 1f) {
            return null;
        }
        float safeFraction = Math.max(0f, fraction);
        float[] distances = {
            averageHeight * safeFraction,
            averageWidth * safeFraction,
            averageHeight * safeFraction,
            averageWidth * safeFraction
        };
        PointF[] points = {
            quad.topLeft,
            quad.topRight,
            quad.bottomRight,
            quad.bottomLeft
        };
        ShiftedEdge[] edges = new ShiftedEdge[4];
        for (int side = 0; side < 4; side++) {
            PointF start = points[side];
            PointF end = points[(side + 1) % 4];
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float length = (float) Math.hypot(dx, dy);
            if (length < 1f) {
                return null;
            }
            float outwardX = dy / length;
            float outwardY = -dx / length;
            float distance = distances[side];
            edges[side] = new ShiftedEdge(
                shift(start, outwardX, outwardY, distance),
                shift(end, outwardX, outwardY, distance)
            );
        }
        PointF topLeft = intersection(edges[3], edges[0]);
        PointF topRight = intersection(edges[0], edges[1]);
        PointF bottomRight = intersection(edges[1], edges[2]);
        PointF bottomLeft = intersection(edges[2], edges[3]);
        if (topLeft == null || topRight == null || bottomRight == null || bottomLeft == null) {
            return null;
        }
        DocumentQuad expanded = new DocumentQuad(topLeft, topRight, bottomRight, bottomLeft);
        for (PointF point : expanded.points()) {
            if (Float.isNaN(point.x) || Float.isInfinite(point.x)
                || Float.isNaN(point.y) || Float.isInfinite(point.y)
                || point.x < guardPixels || point.y < guardPixels
                || point.x > sourceWidth - 1f - guardPixels
                || point.y > sourceHeight - 1f - guardPixels) {
                return null;
            }
        }
        return expanded;
    }

    private PointF shift(PointF point, float normalX, float normalY, float distance) {
        return new PointF(
            point.x + normalX * distance,
            point.y + normalY * distance
        );
    }

    private PointF intersection(ShiftedEdge first, ShiftedEdge second) {
        float firstDx = first.end.x - first.start.x;
        float firstDy = first.end.y - first.start.y;
        float secondDx = second.end.x - second.start.x;
        float secondDy = second.end.y - second.start.y;
        float determinant = firstDx * secondDy - firstDy * secondDx;
        if (Math.abs(determinant) < 1.0e-5f) {
            return null;
        }
        float deltaX = second.start.x - first.start.x;
        float deltaY = second.start.y - first.start.y;
        float firstScale = (deltaX * secondDy - deltaY * secondDx) / determinant;
        return new PointF(
            first.start.x + firstScale * firstDx,
            first.start.y + firstScale * firstDy
        );
    }

    private static final class ShiftedEdge {
        final PointF start;
        final PointF end;

        ShiftedEdge(PointF start, PointF end) {
            this.start = start;
            this.end = end;
        }
    }

}
