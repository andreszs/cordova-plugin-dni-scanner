package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;

final class PerspectiveTransformer {
    private final ScannerConfig config;

    PerspectiveTransformer(ScannerConfig config) {
        this.config = config;
    }

    Bitmap transform(Bitmap source, DocumentQuad originalQuad) {
        if (!hasSufficientSourceMargin(
                originalQuad,
                source.getWidth(),
                source.getHeight())) {
            throw new IllegalArgumentException(
                "No hay margen suficiente alrededor de los cuatro bordes del documento."
            );
        }
        DocumentQuad quad = expandedQuad(
            originalQuad,
            config.cropPaddingFraction,
            source.getWidth(),
            source.getHeight(),
            0f
        );
        if (quad == null) {
            throw new IllegalArgumentException("No se pudo expandir el recorte del documento.");
        }
        int naturalWidth = effectiveDocumentLongEdge(quad);
        int targetWidth = Math.min(config.maxOutputLongEdge, naturalWidth);
        targetWidth = Math.max(1, targetWidth);
        int targetHeight = Math.max(1, Math.round(targetWidth / config.expectedDocumentAspect));

        float[] sourcePoints = {
            quad.topLeft.x, quad.topLeft.y,
            quad.topRight.x, quad.topRight.y,
            quad.bottomRight.x, quad.bottomRight.y,
            quad.bottomLeft.x, quad.bottomLeft.y
        };
        float[] destinationPoints = {
            0f, 0f,
            targetWidth - 1f, 0f,
            targetWidth - 1f, targetHeight - 1f,
            0f, targetHeight - 1f
        };
        Matrix transform = new Matrix();
        if (!transform.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) {
            throw new IllegalArgumentException("No se pudo calcular la homografía.");
        }
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
        );
        // Canvas delegates the projective sampling to Android's native graphics
        // engine. This preserves bilinear color output while avoiding millions of
        // Java-level divisions and interpolations on legacy ARM processors.
        canvas.drawBitmap(source, transform, paint);
        return output;
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
