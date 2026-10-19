package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class StillImageProcessor {
    static final class RetryableProcessingException extends Exception {
        final AnalysisResult.State state;
        final DocumentDetection detection;
        final QualityMetrics quality;
        final int frameWidth;
        final int frameHeight;

        RetryableProcessingException(String message) {
            this(message, null, null, null, 0, 0);
        }

        RetryableProcessingException(String message, AnalysisResult.State state) {
            this(message, state, null, null, 0, 0);
        }

        RetryableProcessingException(String message, AnalysisResult.State state,
                                     DocumentDetection detection, QualityMetrics quality,
                                     int frameWidth, int frameHeight) {
            super(message);
            this.state = state;
            this.detection = detection;
            this.quality = quality;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
        }
    }

    static final class Result {
        final File file;
        final int width;
        final int height;
        final DocumentDetection detection;
        final QualityMetrics quality;
        final RecognitionResult recognition;

        Result(File file, int width, int height, DocumentDetection detection,
               QualityMetrics quality, RecognitionResult recognition) {
            this.file = file;
            this.width = width;
            this.height = height;
            this.detection = detection;
            this.quality = quality;
            this.recognition = recognition;
        }
    }

    private final ScannerConfig config;
    private final ScanChecks checks;
    private final DocumentDetector detector;
    private final QualityEvaluator qualityEvaluator;
    private final PerspectiveTransformer transformer;
    private final SideVariantRecognizer recognizer;

    StillImageProcessor(ScannerConfig config, ScanChecks checks,
                        DetectionRegion detectionRegion) {
        this.config = config;
        this.checks = checks;
        detector = new DocumentDetector(config, detectionRegion);
        qualityEvaluator = new QualityEvaluator(config);
        transformer = new PerspectiveTransformer(config);
        recognizer = new SideVariantRecognizer(config);
    }

    Result process(File rawFile, File outputFile)
        throws IOException, RetryableProcessingException {
        Bitmap decoded = decodeForProcessing(rawFile);
        if (decoded == null) {
            throw new IOException("No se pudo decodificar la fotografía capturada.");
        }
        Bitmap oriented = orientFromExif(decoded, rawFile);
        if (oriented != decoded) {
            decoded.recycle();
        }

        Bitmap corrected = null;
        try {
            LumaFrame stillFrame = LumaExtractor.fromBitmap(oriented, config.stillDetectionMaxDimension);
            DocumentDetection detection = detector.detect(stillFrame);
            if (detection == null) {
                throw new RetryableProcessingException(
                    "No se pudieron confirmar los cuatro bordes en la fotografía."
                );
            }
            detection = detector.refineOutwardEdges(stillFrame, detection);
            QualityMetrics preTransformQuality = qualityEvaluator.evaluate(
                stillFrame,
                detection.quad
            );
            if (!transformer.hasSufficientSourceMargin(
                    detection.quad,
                    stillFrame.width,
                    stillFrame.height)) {
                throw new RetryableProcessingException(
                    "No se pudo conservar completo el contorno del DNI.",
                    AnalysisResult.State.CROP_INCOMPLETE,
                    detection,
                    preTransformQuality,
                    stillFrame.width,
                    stillFrame.height
                );
            }
            AnalysisResult.State qualityState = qualityEvaluator.classify(
                detection,
                preTransformQuality,
                checks
            );
            if (qualityState == AnalysisResult.State.EDGE_OCCLUDED
                && !qualityEvaluator.hasStillEdgeOcclusion(preTransformQuality)) {
                qualityState = AnalysisResult.State.READY;
            }
            if (qualityState == AnalysisResult.State.WAITING_FOR_FOCUS
                || qualityState == AnalysisResult.State.IMPROVE_LIGHTING
                || qualityState == AnalysisResult.State.REDUCE_LIGHTING
                || qualityState == AnalysisResult.State.AVOID_GLARE
                || qualityState == AnalysisResult.State.UNEVEN_LIGHTING
                || qualityState == AnalysisResult.State.EDGE_OCCLUDED
                || qualityState == AnalysisResult.State.ADJUST_PERSPECTIVE) {
                throw new RetryableProcessingException(
                    "La fotografía final no alcanzó la calidad mínima: " + qualityState.name(),
                    qualityState,
                    detection,
                    preTransformQuality,
                    stillFrame.width,
                    stillFrame.height
                );
            }

            float scaleX = oriented.getWidth() / (float) stillFrame.width;
            float scaleY = oriented.getHeight() / (float) stillFrame.height;
            DocumentQuad sourceQuad = detection.quad.scaled(scaleX, scaleY);
            corrected = transformer.transform(oriented, sourceQuad);
            LumaFrame outputFrame = LumaExtractor.fromBitmap(corrected, config.stillDetectionMaxDimension);
            DocumentQuad outputQuad = new DocumentQuad(
                new android.graphics.PointF(0f, 0f),
                new android.graphics.PointF(outputFrame.width - 1f, 0f),
                new android.graphics.PointF(outputFrame.width - 1f, outputFrame.height - 1f),
                new android.graphics.PointF(0f, outputFrame.height - 1f)
            );
            QualityMetrics outputQuality = qualityEvaluator.evaluate(outputFrame, outputQuad);
            boolean outputTooDark = checks.brightness
                && (outputQuality.brightness < config.minBrightness
                    || outputQuality.darkFraction > config.maxDarkFraction);
            boolean outputTooBright = checks.brightness
                && (outputQuality.brightness > config.maxBrightness
                    || outputQuality.clippedFraction > config.maxClippedFraction);
            if ((checks.sharpness
                    && outputQuality.sharpness < config.minSharpnessVariance)
                || outputTooDark
                || outputTooBright
                || (checks.glare
                    && (outputQuality.glareFraction > config.maxGlareFraction
                        || outputQuality.glareBlobFraction > config.maxGlareBlobFraction))
                || (checks.shadows && qualityEvaluator.hasUnevenLighting(outputQuality))
                || (checks.edgeOcclusion
                    && qualityEvaluator.hasStillEdgeOcclusion(outputQuality))) {
                AnalysisResult.State outputState;
                if (checks.edgeOcclusion
                    && qualityEvaluator.hasStillEdgeOcclusion(outputQuality)) {
                    outputState = AnalysisResult.State.EDGE_OCCLUDED;
                } else if (checks.shadows && qualityEvaluator.hasUnevenLighting(outputQuality)) {
                    outputState = AnalysisResult.State.UNEVEN_LIGHTING;
                } else if (checks.sharpness
                    && outputQuality.sharpness < config.minSharpnessVariance) {
                    outputState = AnalysisResult.State.WAITING_FOR_FOCUS;
                } else if (outputTooDark) {
                    outputState = AnalysisResult.State.IMPROVE_LIGHTING;
                } else if (outputTooBright) {
                    outputState = AnalysisResult.State.REDUCE_LIGHTING;
                } else {
                    outputState = AnalysisResult.State.AVOID_GLARE;
                }
                throw new RetryableProcessingException(
                    "La imagen corregida no alcanzó la calidad mínima.",
                    outputState,
                    new DocumentDetection(
                        outputQuad,
                        detection.score,
                        detection.areaFraction,
                        detection.centerOffset,
                        outputFrame.width / (float) outputFrame.height
                    ),
                    outputQuality,
                    outputFrame.width,
                    outputFrame.height
                );
            }

            RecognitionResult recognition = recognizer.recognize(corrected);

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("No se pudo crear el directorio temporal de salida.");
            }
            try (FileOutputStream stream = new FileOutputStream(outputFile)) {
                if (!corrected.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, stream)) {
                    throw new IOException("No se pudo codificar el JPEG corregido.");
                }
            }
            return new Result(
                outputFile,
                corrected.getWidth(),
                corrected.getHeight(),
                detection,
                outputQuality,
                recognition
            );
        } finally {
            oriented.recycle();
            if (corrected != null && !corrected.isRecycled()) {
                corrected.recycle();
            }
        }
    }

    private Bitmap decodeForProcessing(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sampleSize = 1;
        int minimumDecodedLongEdge = Math.round(config.maxOutputLongEdge * 0.90f);
        while (longest / (sampleSize * 2) >= minimumDecodedLongEdge) {
            sampleSize *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private Bitmap orientFromExif(Bitmap bitmap, File file) throws IOException {
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        int orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        );
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }
}
