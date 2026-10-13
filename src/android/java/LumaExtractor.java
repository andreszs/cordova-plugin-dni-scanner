package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

final class LumaExtractor {
    private LumaExtractor() {
    }

    static LumaFrame fromImageProxy(ImageProxy image, int maxDimension) {
        // ViewPort supplies a crop rect shared with Preview and ImageCapture. Analyze that
        // region, not the complete sensor buffer, so the overlay and captured JPEG agree.
        Rect crop = image.getCropRect();
        int sourceWidth = crop.width();
        int sourceHeight = crop.height();
        int rotation = normalizeRotation(image.getImageInfo().getRotationDegrees());
        int rotatedWidth = rotation == 90 || rotation == 270 ? sourceHeight : sourceWidth;
        int rotatedHeight = rotation == 90 || rotation == 270 ? sourceWidth : sourceHeight;
        float scale = Math.min(1f, maxDimension / (float) Math.max(rotatedWidth, rotatedHeight));
        int outputWidth = Math.max(2, Math.round(rotatedWidth * scale));
        int outputHeight = Math.max(2, Math.round(rotatedHeight * scale));
        byte[] output = new byte[outputWidth * outputHeight];
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        for (int y = 0; y < outputHeight; y++) {
            int rotatedY = Math.min(rotatedHeight - 1, (int) (y / scale));
            for (int x = 0; x < outputWidth; x++) {
                int rotatedX = Math.min(rotatedWidth - 1, (int) (x / scale));
                int sourceX;
                int sourceY;
                switch (rotation) {
                    case 90:
                        sourceX = rotatedY;
                        sourceY = sourceHeight - 1 - rotatedX;
                        break;
                    case 180:
                        sourceX = sourceWidth - 1 - rotatedX;
                        sourceY = sourceHeight - 1 - rotatedY;
                        break;
                    case 270:
                        sourceX = sourceWidth - 1 - rotatedY;
                        sourceY = rotatedX;
                        break;
                    default:
                        sourceX = rotatedX;
                        sourceY = rotatedY;
                        break;
                }
                int bufferX = crop.left + sourceX;
                int bufferY = crop.top + sourceY;
                int outputIndex = y * outputWidth + x;
                int luma = buffer.get(bufferY * rowStride + bufferX * pixelStride) & 0xff;
                output[outputIndex] = (byte) luma;
            }
        }
        // Rectangle detection only needs luminance. Avoid doing an RGB conversion for
        // every preview pixel until a plausible document has actually been found; this
        // is a substantial saving on older ARM devices such as the Galaxy J7.
        return new LumaFrame(outputWidth, outputHeight, output, null, null);
    }

    static LumaFrame withColorFromImageProxy(ImageProxy image, LumaFrame lumaFrame) {
        if (image.getPlanes().length < 3) {
            return lumaFrame;
        }
        Rect crop = image.getCropRect();
        int sourceWidth = crop.width();
        int sourceHeight = crop.height();
        int rotation = normalizeRotation(image.getImageInfo().getRotationDegrees());
        int rotatedWidth = rotation == 90 || rotation == 270 ? sourceHeight : sourceWidth;
        int rotatedHeight = rotation == 90 || rotation == 270 ? sourceWidth : sourceHeight;
        float scaleX = lumaFrame.width / (float) rotatedWidth;
        float scaleY = lumaFrame.height / (float) rotatedHeight;
        byte[] colorfulness = new byte[lumaFrame.width * lumaFrame.height];
        byte[] warmth = new byte[colorfulness.length];
        byte[] skinLikelihood = new byte[colorfulness.length];

        ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        // YUV chroma is normally shared by a 2x2 luma block. Sample it once and fill
        // the corresponding reduced-frame block; the color metrics only need coarse
        // evidence for glare and edge obstruction.
        for (int y = 0; y < lumaFrame.height; y += 2) {
            int rotatedY = Math.min(rotatedHeight - 1, (int) (y / scaleY));
            for (int x = 0; x < lumaFrame.width; x += 2) {
                int rotatedX = Math.min(rotatedWidth - 1, (int) (x / scaleX));
                int sourceX;
                int sourceY;
                switch (rotation) {
                    case 90:
                        sourceX = rotatedY;
                        sourceY = sourceHeight - 1 - rotatedX;
                        break;
                    case 180:
                        sourceX = sourceWidth - 1 - rotatedX;
                        sourceY = sourceHeight - 1 - rotatedY;
                        break;
                    case 270:
                        sourceX = sourceWidth - 1 - rotatedY;
                        sourceY = rotatedX;
                        break;
                    default:
                        sourceX = rotatedX;
                        sourceY = rotatedY;
                        break;
                }
                int bufferX = crop.left + sourceX;
                int bufferY = crop.top + sourceY;
                int uIndex = (bufferY / 2) * uPlane.getRowStride()
                    + (bufferX / 2) * uPlane.getPixelStride();
                int vIndex = (bufferY / 2) * vPlane.getRowStride()
                    + (bufferX / 2) * vPlane.getPixelStride();
                if (uIndex < 0 || uIndex >= uBuffer.limit()
                    || vIndex < 0 || vIndex >= vBuffer.limit()) {
                    continue;
                }
                int luma = lumaFrame.unsignedAt(x, y);
                int rgb = yuvToRgb(luma, uBuffer.get(uIndex) & 0xff, vBuffer.get(vIndex) & 0xff);
                byte colorValue = (byte) colorfulness(
                    (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff
                );
                byte warmthValue = (byte) warmth(
                    (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff
                );
                byte skinValue = (byte) skinLikelihood(
                    (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff
                );
                int maxY = Math.min(lumaFrame.height, y + 2);
                int maxX = Math.min(lumaFrame.width, x + 2);
                for (int fillY = y; fillY < maxY; fillY++) {
                    for (int fillX = x; fillX < maxX; fillX++) {
                        int index = fillY * lumaFrame.width + fillX;
                        colorfulness[index] = colorValue;
                        warmth[index] = warmthValue;
                        skinLikelihood[index] = skinValue;
                    }
                }
            }
        }
        return new LumaFrame(
            lumaFrame.width, lumaFrame.height, lumaFrame.data, colorfulness, warmth,
            skinLikelihood
        );
    }

    static LumaFrame fromBitmap(Bitmap bitmap, int maxDimension) {
        float scale = Math.min(1f, maxDimension / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        int width = Math.max(2, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(2, Math.round(bitmap.getHeight() * scale));
        Bitmap scaled = bitmap;
        if (width != bitmap.getWidth() || height != bitmap.getHeight()) {
            scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }

        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] luma = new byte[pixels.length];
        byte[] colorfulness = new byte[pixels.length];
        byte[] warmth = new byte[pixels.length];
        byte[] skinLikelihood = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int red = (color >> 16) & 0xff;
            int green = (color >> 8) & 0xff;
            int blue = color & 0xff;
            luma[i] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
            colorfulness[i] = (byte) colorfulness(red, green, blue);
            warmth[i] = (byte) warmth(red, green, blue);
            skinLikelihood[i] = (byte) skinLikelihood(red, green, blue);
        }
        if (scaled != bitmap) {
            scaled.recycle();
        }
        return new LumaFrame(width, height, luma, colorfulness, warmth, skinLikelihood);
    }

    private static int yuvToRgb(int y, int u, int v) {
        int c = Math.max(0, y - 16);
        int d = u - 128;
        int e = v - 128;
        int red = clamp((298 * c + 409 * e + 128) >> 8);
        int green = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
        int blue = clamp((298 * c + 516 * d + 128) >> 8);
        return (red << 16) | (green << 8) | blue;
    }

    private static int colorfulness(int red, int green, int blue) {
        return Math.max(red, Math.max(green, blue))
            - Math.min(red, Math.min(green, blue));
    }

    private static int warmth(int red, int green, int blue) {
        // Red dominance is a useful lightweight signal for a finger crossing a
        // card edge. It is not used as a general skin classifier and cannot
        // reject a frame by itself unless it persists along the outer band.
        return clamp(red - ((green + blue) / 2));
    }

    private static int skinLikelihood(int red, int green, int blue) {
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        int cb = clamp(Math.round(128f - 0.169f * red - 0.331f * green + 0.5f * blue));
        int cr = clamp(Math.round(128f + 0.5f * red - 0.419f * green - 0.081f * blue));
        boolean plausibleTone = red > 45 && green > 25 && blue > 15
            && maximum - minimum >= 16
            && red >= green + 6
            && red >= blue + 10;
        boolean plausibleChroma = cb >= 72 && cb <= 132 && cr >= 132 && cr <= 184;
        if (!plausibleTone || !plausibleChroma) {
            return 0;
        }
        int cbScore = 100 - Math.min(100, Math.abs(cb - 102) * 3);
        int crScore = 100 - Math.min(100, Math.abs(cr - 156) * 3);
        return clamp(Math.min(cbScore, crScore));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int normalizeRotation(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        if (normalized < 45 || normalized >= 315) {
            return 0;
        }
        if (normalized < 135) {
            return 90;
        }
        if (normalized < 225) {
            return 180;
        }
        return 270;
    }
}
