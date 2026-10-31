package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

final class PerspectiveTransformer {
    private final ScannerConfig config;

    PerspectiveTransformer(ScannerConfig config) {
        this.config = config;
    }

    Bitmap cropWithoutWarp(Bitmap source, DocumentQuad originalQuad) {
        DocumentQuad expanded = expandedQuad(
            originalQuad,
            config.cropPaddingFraction,
            source.getWidth(),
            source.getHeight(),
            config.cropIntegrityGuardPixels
        );
        if (expanded == null) {
            throw new IllegalArgumentException(
                "No hay margen suficiente alrededor de los cuatro bordes del documento."
            );
        }
        int left = Math.max(0, (int) Math.floor(minimumX(expanded)));
        int top = Math.max(0, (int) Math.floor(minimumY(expanded)));
        int right = Math.min(source.getWidth(), (int) Math.ceil(maximumX(expanded)) + 1);
        int bottom = Math.min(source.getHeight(), (int) Math.ceil(maximumY(expanded)) + 1);
        int width = right - left;
        int height = bottom - top;
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("El recorte detectado no tiene dimensiones válidas.");
        }
        Bitmap cropped = Bitmap.createBitmap(source, left, top, width, height);
        int longEdge = Math.max(width, height);
        if (longEdge <= config.maxOutputLongEdge) {
            return cropped;
        }
        float scale = config.maxOutputLongEdge / (float) longEdge;
        Bitmap resized = Bitmap.createScaledBitmap(
            cropped,
            Math.max(1, Math.round(width * scale)),
            Math.max(1, Math.round(height * scale)),
            true
        );
        cropped.recycle();
        return resized;
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
        float requiredFraction = Math.max(
            config.cropPaddingFraction,
            config.minimumCropIntegrityClearanceFraction
        );
        return expandedQuad(
            quad,
            requiredFraction,
            sourceWidth,
            sourceHeight,
            config.cropIntegrityGuardPixels
        ) != null;
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
