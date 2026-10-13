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
        DocumentQuad quad = inset(originalQuad, config.cropInsetFraction);
        float averageWidth = (quad.topWidth() + quad.bottomWidth()) * 0.5f;
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        int naturalWidth = Math.max(1, Math.round(Math.max(
            averageWidth,
            averageHeight * config.expectedDocumentAspect
        )));
        int targetWidth = Math.min(config.maxOutputLongEdge, naturalWidth);
        targetWidth = Math.max(640, targetWidth);
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

    private DocumentQuad inset(DocumentQuad quad, float fraction) {
        PointF center = quad.center();
        return new DocumentQuad(
            toward(quad.topLeft, center, fraction),
            toward(quad.topRight, center, fraction),
            toward(quad.bottomRight, center, fraction),
            toward(quad.bottomLeft, center, fraction)
        );
    }

    private PointF toward(PointF point, PointF center, float fraction) {
        return new PointF(
            point.x + (center.x - point.x) * fraction,
            point.y + (center.y - point.y) * fraction
        );
    }

}
