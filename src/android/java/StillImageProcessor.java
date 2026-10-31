package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class StillImageProcessor {
    private static final String TRACE_TAG = "DniScannerTrace";

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
        final int rawWidth;
        final int rawHeight;
        final int effectiveDocumentLongEdge;
        final int width;
        final int height;
        final DocumentDetection detection;
        final QualityMetrics quality;
        final RecognitionResult recognition;

        Result(File file, int rawWidth, int rawHeight, int effectiveDocumentLongEdge,
               int width, int height, DocumentDetection detection,
               QualityMetrics quality, RecognitionResult recognition) {
            this.file = file;
            this.rawWidth = rawWidth;
            this.rawHeight = rawHeight;
            this.effectiveDocumentLongEdge = effectiveDocumentLongEdge;
            this.width = width;
            this.height = height;
            this.detection = detection;
            this.quality = quality;
            this.recognition = recognition;
        }
    }

    private final ScannerConfig config;
    private final ScanChecks checks;
    private final OpenCvDocumentEngine engine;
    private final PerspectiveTransformer transformer;
    private final SideVariantRecognizer recognizer;
    private final DocumentQuad previewQuad;
    private final int previewWidth;
    private final int previewHeight;
    private final boolean debugMode;

    StillImageProcessor(ScannerConfig config, ScanChecks checks,
                        DetectionRegion detectionRegion) {
        this(config, checks, detectionRegion, null, 0, 0, false);
    }

    StillImageProcessor(ScannerConfig config, ScanChecks checks,
                        DetectionRegion detectionRegion, DocumentQuad previewQuad,
                        int previewWidth, int previewHeight) {
        this(
            config, checks, detectionRegion, previewQuad,
            previewWidth, previewHeight, false
        );
    }

    StillImageProcessor(ScannerConfig config, ScanChecks checks,
                        DetectionRegion detectionRegion, DocumentQuad previewQuad,
                        int previewWidth, int previewHeight, boolean debugMode) {
        this.config = config;
        this.checks = checks;
        // A still has its own crop/orientation. The normalized preview track is
        // used as a ranking prior, not as a hard preview-guide boundary.
        engine = new OpenCvDocumentEngine(config, null);
        transformer = new PerspectiveTransformer(config);
        recognizer = new SideVariantRecognizer(config);
        this.previewQuad = previewQuad;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        this.debugMode = debugMode;
    }

    Result process(File rawFile, File outputFile)
        throws IOException, RetryableProcessingException {
        int[] rawDimensions = orientedRawDimensions(rawFile);
        trace("still:start raw=" + rawDimensions[0] + "x" + rawDimensions[1]
            + " bytes=" + rawFile.length()
            + " preview=" + previewWidth + "x" + previewHeight
            + " previewQuad=" + quadText(previewQuad));
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
            DocumentQuad prior = normalizedPreviewPrior(stillFrame.width, stillFrame.height);
            trace("still:decoded=" + oriented.getWidth() + "x" + oriented.getHeight()
                + " analysis=" + stillFrame.width + "x" + stillFrame.height
                + " prior=" + quadText(prior));
            DocumentDetection detection = engine.detect(stillFrame, prior);
            if (detection == null && prior != null) {
                trace("still:detection_with_prior_missed fallback=global_detection");
                detection = engine.detect(stillFrame, null);
            }
            if (detection == null) {
                if (prior == null) {
                    trace("still:reject reason=no_detection_or_preview_prior");
                    throw new RetryableProcessingException(
                        "No se pudieron confirmar los cuatro bordes en la fotografía."
                    );
                }
                trace("still:guard fallback=preview_prior reason=no_still_detection");
                detection = detectionForQuad(prior, 0.50f, stillFrame.width, stillFrame.height);
            }
            trace("still:detection score=" + decimal(detection.score)
                + " area=" + decimal(detection.areaFraction)
                + " aspect=" + decimal(detection.observedAspect)
                + " quad=" + quadText(detection.quad));
            TrackMatch trackMatch = prior == null ? null : comparePreviewTrack(
                prior, detection.quad, stillFrame.width, stillFrame.height
            );
            if (trackMatch != null) {
                trace("still:preview_match " + trackMatch);
            }
            if (trackMatch != null && trackMatch.isTight()) {
                trace("still:guard correction=tight_still_detection");
            } else if (trackMatch != null && trackMatch.isBounded()) {
                DocumentQuad bounded = OpenCvDocumentEngine.blend(
                    prior, detection.quad, 0.25f
                );
                trace("still:guard correction=bounded_blend weight=0.25 quad="
                    + quadText(bounded));
                detection = detectionForQuad(
                    bounded, detection.score, stillFrame.width, stillFrame.height
                );
            } else if (trackMatch != null) {
                trace("still:guard fallback=preview_prior reason=divergent_still_detection "
                    + trackMatch);
                detection = detectionForQuad(
                    prior, Math.min(detection.score, 0.55f),
                    stillFrame.width, stillFrame.height
                );
            }
            QualityMetrics preTransformQuality = engine.assess(stillFrame, detection.quad);
            trace("still:pre_quality " + qualityText(preTransformQuality));
            if (!transformer.hasSufficientSourceMargin(
                    detection.quad,
                    stillFrame.width,
                    stillFrame.height)) {
                trace("still:reject reason=crop_incomplete");
                throw new RetryableProcessingException(
                    "No se pudo conservar completo el contorno del DNI.",
                    AnalysisResult.State.CROP_INCOMPLETE,
                    detection,
                    preTransformQuality,
                    stillFrame.width,
                    stillFrame.height
                );
            }
            AnalysisResult.State qualityState = engine.classify(
                detection, preTransformQuality, checks
            );
            if (qualityState == AnalysisResult.State.WAITING_FOR_FOCUS
                || qualityState == AnalysisResult.State.IMPROVE_LIGHTING
                || qualityState == AnalysisResult.State.REDUCE_LIGHTING
                || qualityState == AnalysisResult.State.AVOID_GLARE
                || qualityState == AnalysisResult.State.UNEVEN_LIGHTING
                || qualityState == AnalysisResult.State.ADJUST_PERSPECTIVE) {
                trace("still:reject reason=pre_quality state=" + qualityState.name());
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
            DocumentQuad rawQuad = detection.quad.scaled(
                rawDimensions[0] / (float) stillFrame.width,
                rawDimensions[1] / (float) stillFrame.height
            );
            int effectiveDocumentLongEdge = transformer.effectiveDocumentLongEdge(rawQuad);
            trace("still:effective_long_edge=" + effectiveDocumentLongEdge
                + " minimum=" + config.minEffectiveDocumentLongEdge);
            if (effectiveDocumentLongEdge < config.minEffectiveDocumentLongEdge) {
                trace("still:reject reason=effective_resolution actual="
                    + effectiveDocumentLongEdge + " minimum="
                    + config.minEffectiveDocumentLongEdge);
                throw new RetryableProcessingException(
                    "La fotografía no alcanzó la resolución efectiva mínima.",
                    AnalysisResult.State.MOVE_CLOSER,
                    detection,
                    preTransformQuality,
                    stillFrame.width,
                    stillFrame.height
                );
            }
            corrected = transformer.cropWithoutWarp(oriented, sourceQuad);
            LumaFrame outputFrame = LumaExtractor.fromBitmap(corrected, config.stillDetectionMaxDimension);
            DocumentQuad outputQuad = new DocumentQuad(
                new android.graphics.PointF(0f, 0f),
                new android.graphics.PointF(outputFrame.width - 1f, 0f),
                new android.graphics.PointF(outputFrame.width - 1f, outputFrame.height - 1f),
                new android.graphics.PointF(0f, outputFrame.height - 1f)
            );
            QualityMetrics outputQuality = engine.assess(outputFrame, outputQuad);
            trace("still:output=" + corrected.getWidth() + "x" + corrected.getHeight()
                + " " + qualityText(outputQuality));
            boolean outputTooDark = checks.brightness
                && (outputQuality.brightness < 34f || outputQuality.darkFraction > 0.48f);
            boolean outputTooBright = checks.brightness
                && outputQuality.clippedFraction > 0.48f
                && outputQuality.glareBlobFraction > 0.12f;
            if ((checks.sharpness
                    && outputQuality.sharpness < config.minStillSharpnessVariance)
                || outputTooDark
                || outputTooBright
                || (checks.glare
                    && outputQuality.glareBlobFraction > 0.14f)
                || (checks.shadows && outputQuality.illuminationSpread > 0.52f
                    && outputQuality.shadowBoundary > 0.30f)) {
                AnalysisResult.State outputState;
                if (checks.shadows && outputQuality.illuminationSpread > 0.52f
                    && outputQuality.shadowBoundary > 0.30f) {
                    outputState = AnalysisResult.State.UNEVEN_LIGHTING;
                } else if (checks.sharpness
                    && outputQuality.sharpness < config.minStillSharpnessVariance) {
                    outputState = AnalysisResult.State.WAITING_FOR_FOCUS;
                } else if (outputTooDark) {
                    outputState = AnalysisResult.State.IMPROVE_LIGHTING;
                } else if (outputTooBright) {
                    outputState = AnalysisResult.State.REDUCE_LIGHTING;
                } else {
                    outputState = AnalysisResult.State.AVOID_GLARE;
                }
                trace("still:reject reason=output_quality state=" + outputState.name());
                throw new RetryableProcessingException(
                    "La imagen recortada no alcanzó la calidad mínima.",
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

            RecognitionResult recognition = config.profile == ScanProfile.DNI
                ? recognizer.recognize(corrected)
                : RecognitionResult.unknown();
            trace("still:accepted side=" + recognition.side
                + " variant=" + recognition.variant
                + " content=" + decimal(recognition.contentScore));

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("No se pudo crear el directorio temporal de salida.");
            }
            try (FileOutputStream stream = new FileOutputStream(outputFile)) {
                if (!corrected.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, stream)) {
                    throw new IOException("No se pudo codificar el JPEG recortado.");
                }
            }
            return new Result(
                outputFile,
                rawDimensions[0],
                rawDimensions[1],
                effectiveDocumentLongEdge,
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

    private DocumentQuad normalizedPreviewPrior(int width, int height) {
        if (previewQuad == null || previewWidth <= 0 || previewHeight <= 0) {
            return null;
        }
        return previewQuad.scaled(
            width / (float) previewWidth,
            height / (float) previewHeight
        );
    }

    private TrackMatch comparePreviewTrack(DocumentQuad prior, DocumentQuad detected,
                                           int width, int height) {
        float priorArea = prior.area();
        float areaRatio = detected.area() / Math.max(1f, priorArea);
        float priorHeight = (prior.leftHeight() + prior.rightHeight()) * 0.5f;
        float detectedHeight = (detected.leftHeight() + detected.rightHeight()) * 0.5f;
        float priorAspect = (prior.topWidth() + prior.bottomWidth()) * 0.5f
            / Math.max(1f, priorHeight);
        float detectedAspect = (detected.topWidth() + detected.bottomWidth()) * 0.5f
            / Math.max(1f, detectedHeight);
        float aspectChange = Math.abs(detectedAspect - priorAspect)
            / Math.max(0.01f, priorAspect);
        float rmsDistance = OpenCvDocumentEngine.quadDistance(
            prior, detected, width, height
        );
        float maximumCornerDistance = OpenCvDocumentEngine.quadMaxCornerDistance(
            prior, detected, width, height
        );
        boolean accepted = rmsDistance <= 0.030f
            && maximumCornerDistance <= 0.045f
            && areaRatio >= 0.84f
            && areaRatio <= 1.18f
            && aspectChange <= 0.16f;
        return new TrackMatch(
            accepted, rmsDistance, maximumCornerDistance, areaRatio, aspectChange
        );
    }

    private DocumentDetection detectionForQuad(DocumentQuad quad, float score,
                                               int width, int height) {
        float area = quad.area() / Math.max(1f, width * (float) height);
        android.graphics.PointF center = quad.center();
        float centerOffset = (float) Math.hypot(
            (center.x - width * 0.5f) / Math.max(1f, width * 0.5f),
            (center.y - height * 0.5f) / Math.max(1f, height * 0.5f)
        );
        float averageHeight = (quad.leftHeight() + quad.rightHeight()) * 0.5f;
        float aspect = (quad.topWidth() + quad.bottomWidth()) * 0.5f
            / Math.max(1f, averageHeight);
        return new DocumentDetection(quad, score, area, centerOffset, aspect);
    }

    private void trace(String message) {
        if (debugMode) {
            Log.i(TRACE_TAG, message);
        }
    }

    private String qualityText(QualityMetrics quality) {
        return "focus=" + decimal(quality.sharpness)
            + " light=" + decimal(quality.brightness)
            + " dark=" + decimal(quality.darkFraction)
            + " clipped=" + decimal(quality.clippedFraction)
            + " glare=" + decimal(quality.glareBlobFraction)
            + " perspective=" + decimal(quality.perspectiveScore)
            + " spread=" + decimal(quality.illuminationSpread)
            + " shadow=" + decimal(quality.shadowBoundary);
    }

    private static String quadText(DocumentQuad quad) {
        if (quad == null) {
            return "-";
        }
        return String.format(
            java.util.Locale.US,
            "[(%.1f,%.1f),(%.1f,%.1f),(%.1f,%.1f),(%.1f,%.1f)]",
            quad.topLeft.x, quad.topLeft.y,
            quad.topRight.x, quad.topRight.y,
            quad.bottomRight.x, quad.bottomRight.y,
            quad.bottomLeft.x, quad.bottomLeft.y
        );
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.US, "%.4f", value);
    }

    private static final class TrackMatch {
        final boolean accepted;
        final float rmsDistance;
        final float maximumCornerDistance;
        final float areaRatio;
        final float aspectChange;

        TrackMatch(boolean accepted, float rmsDistance, float maximumCornerDistance,
                   float areaRatio, float aspectChange) {
            this.accepted = accepted;
            this.rmsDistance = rmsDistance;
            this.maximumCornerDistance = maximumCornerDistance;
            this.areaRatio = areaRatio;
            this.aspectChange = aspectChange;
        }

        boolean isTight() {
            return rmsDistance <= 0.012f
                && maximumCornerDistance <= 0.020f
                && areaRatio >= 0.92f
                && areaRatio <= 1.08f
                && aspectChange <= 0.08f;
        }

        boolean isBounded() {
            return accepted;
        }

        @Override
        public String toString() {
            return "accepted=" + accepted
                + " rms=" + decimal(rmsDistance)
                + " maxCorner=" + decimal(maximumCornerDistance)
                + " areaRatio=" + decimal(areaRatio)
                + " aspectChange=" + decimal(aspectChange);
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
        int minimumDecodedLongEdge = Math.round(
            Math.max(config.preferredCaptureLongEdge, config.maxOutputLongEdge) * 0.90f
        );
        while (longest / (sampleSize * 2) >= minimumDecodedLongEdge) {
            sampleSize *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private int[] orientedRawDimensions(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("La fotografía capturada no tiene dimensiones válidas.");
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        int orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        );
        boolean swapsDimensions = orientation == ExifInterface.ORIENTATION_TRANSPOSE
            || orientation == ExifInterface.ORIENTATION_ROTATE_90
            || orientation == ExifInterface.ORIENTATION_TRANSVERSE
            || orientation == ExifInterface.ORIENTATION_ROTATE_270;
        return swapsDimensions
            ? new int[] { bounds.outHeight, bounds.outWidth }
            : new int[] { bounds.outWidth, bounds.outHeight };
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
