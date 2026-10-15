package com.crediser.plugins.dniscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScannerOverlayView extends View {
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint documentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint instructionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messageBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final QualityEvaluator qualityEvaluator = new QualityEvaluator(ScannerConfig.DEFAULT);
    private AnalysisResult result;
    private int frameWidth;
    private int frameHeight;
    private String instruction = "";
    private String message = "Buscando documento";
    private int messageColor = Color.WHITE;
    private boolean debugMode = true;
    private ScanChecks checks = ScanChecks.defaults();

    ScannerOverlayView(Context context) {
        this(context, null);
    }

    ScannerOverlayView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        shadePaint.setColor(0x66000000);
        shadePaint.setStyle(Paint.Style.FILL);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(3f));
        guidePaint.setPathEffect(new android.graphics.DashPathEffect(
            new float[] { dp(10f), dp(7f) }, 0f
        ));
        documentPaint.setStyle(Paint.Style.STROKE);
        documentPaint.setColor(0x99d4d7dc);
        documentPaint.setStrokeWidth(dp(1.5f));
        documentPaint.setStrokeJoin(Paint.Join.ROUND);
        instructionPaint.setTextAlign(Paint.Align.CENTER);
        instructionPaint.setTypeface(android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        ));
        messagePaint.setColor(Color.WHITE);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        messagePaint.setTypeface(android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        ));
        messageBackgroundPaint.setColor(0xcc000000);
        messageBackgroundPaint.setStyle(Paint.Style.FILL);
        debugPaint.setTypeface(android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.NORMAL
        ));
        debugPaint.setTextSize(sp(9f));
        debugBackgroundPaint.setColor(0xb8000000);
        debugBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    void setInstructionText(String instruction) {
        this.instruction = instruction == null ? "" : instruction.trim();
        invalidate();
    }

    void setDebugConfiguration(boolean debugMode, ScanChecks checks) {
        this.debugMode = debugMode;
        this.checks = checks == null ? ScanChecks.defaults() : checks;
        invalidate();
    }

    DetectionRegion detectionRegion(float marginFraction) {
        RectF guide = guideRect();
        return new DetectionRegion(
            getWidth(),
            getHeight(),
            guide.left,
            guide.top,
            guide.right,
            guide.bottom,
            marginFraction
        );
    }

    void update(AnalysisResult result, int frameWidth, int frameHeight, String message) {
        update(result, frameWidth, frameHeight, message, Color.WHITE);
    }

    void update(AnalysisResult result, int frameWidth, int frameHeight, String message,
                int messageColor) {
        this.result = result;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.message = message;
        this.messageColor = messageColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF guide = guideRect();
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
        path.addRoundRect(guide, dp(14f), dp(14f), Path.Direction.CCW);
        canvas.drawPath(path, shadePaint);
        int guideColor = colorFor(
            result == null ? AnalysisResult.State.SEARCHING : result.state
        );
        guidePaint.setColor(guideColor);
        canvas.drawRoundRect(guide, dp(14f), dp(14f), guidePaint);
        drawInstruction(canvas, guide, guideColor == 0xffff5c5c ? guideColor : Color.WHITE);

        if (result != null && result.detection != null && frameWidth > 0 && frameHeight > 0) {
            float scale = Math.max(getWidth() / (float) frameWidth, getHeight() / (float) frameHeight);
            float offsetX = (getWidth() - frameWidth * scale) * 0.5f;
            float offsetY = (getHeight() - frameHeight * scale) * 0.5f;
            DocumentQuad quad = result.detection.quad;
            path.reset();
            path.moveTo(offsetX + quad.topLeft.x * scale, offsetY + quad.topLeft.y * scale);
            path.lineTo(offsetX + quad.topRight.x * scale, offsetY + quad.topRight.y * scale);
            path.lineTo(offsetX + quad.bottomRight.x * scale, offsetY + quad.bottomRight.y * scale);
            path.lineTo(offsetX + quad.bottomLeft.x * scale, offsetY + quad.bottomLeft.y * scale);
            path.close();
            canvas.drawPath(path, documentPaint);
        }
        drawMessage(canvas, guide);
        drawDebugPanel(canvas, guide);
    }

    private void drawDebugPanel(Canvas canvas, RectF guide) {
        if (!debugMode) {
            return;
        }
        List<DebugRow> rows = debugRows();
        boolean landscape = getWidth() > getHeight();
        int columns = landscape ? 1 : 2;
        int rowsPerColumn = (rows.size() + columns - 1) / columns;
        float lineHeight = sp(11.5f);
        float padding = dp(7f);
        float panelWidth;
        float left;
        float top;
        if (landscape) {
            float availableLeft = Math.max(dp(120f), guide.left - dp(22f));
            panelWidth = Math.min(dp(210f), availableLeft - dp(10f));
            left = Math.max(dp(8f), guide.left - panelWidth - dp(12f));
            top = Math.max(dp(70f), guide.top);
        } else {
            panelWidth = Math.min(guide.width(), getWidth() - dp(24f));
            left = (getWidth() - panelWidth) * 0.5f;
            top = Math.min(
                getHeight() - rowsPerColumn * lineHeight - padding * 2f - dp(8f),
                guide.bottom + dp(92f)
            );
        }
        float panelHeight = rowsPerColumn * lineHeight + padding * 2f;
        RectF panel = new RectF(left, top, left + panelWidth, top + panelHeight);
        canvas.drawRoundRect(panel, dp(7f), dp(7f), debugBackgroundPaint);

        float columnWidth = (panelWidth - padding * 2f) / columns;
        Paint.FontMetrics metrics = debugPaint.getFontMetrics();
        for (int index = 0; index < rows.size(); index++) {
            int column = index / rowsPerColumn;
            int row = index % rowsPerColumn;
            DebugRow debugRow = rows.get(index);
            debugPaint.setColor(debugRow.enabled
                ? (debugRow.acceptable ? 0xff55e68a : 0xffff6666)
                : 0xffaab0b6);
            float x = left + padding + column * columnWidth;
            float baseline = top + padding + row * lineHeight - metrics.ascent;
            canvas.drawText(fitDebugText(debugRow.text, columnWidth - dp(4f)), x, baseline,
                debugPaint);
        }
    }

    private List<DebugRow> debugRows() {
        List<DebugRow> rows = new ArrayList<>();
        if (result == null || result.detection == null || result.quality == null) {
            rows.add(new DebugRow("documento --", false, true));
            rows.add(new DebugRow("estado buscando", false, true));
            return rows;
        }
        DocumentDetection detection = result.detection;
        QualityMetrics quality = result.quality;
        boolean still = result.source == AnalysisResult.Source.STILL;
        rows.add(new DebugRow(still ? "fuente foto" : "fuente preview", true, false));
        rows.add(new DebugRow(
            String.format(Locale.US, "area %.3f", detection.areaFraction),
            detection.areaFraction >= ScannerConfig.DEFAULT.minDocumentArea
                && detection.areaFraction <= ScannerConfig.DEFAULT.maxDocumentArea,
            checks.documentSize
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "centro %.3f", detection.centerOffset),
            detection.centerOffset <= ScannerConfig.DEFAULT.maxCenterOffset,
            checks.centering
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "persp %.3f", quality.perspectiveScore),
            quality.perspectiveScore >= ScannerConfig.DEFAULT.minPerspectiveScore,
            checks.perspective
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "foco %.0f", quality.sharpness),
            quality.sharpness >= ScannerConfig.DEFAULT.minSharpnessVariance,
            checks.sharpness
        ));
        boolean exposureOk = quality.brightness >= ScannerConfig.DEFAULT.minBrightness
            && quality.brightness <= ScannerConfig.DEFAULT.maxBrightness
            && quality.darkFraction <= ScannerConfig.DEFAULT.maxDarkFraction
            && quality.clippedFraction <= ScannerConfig.DEFAULT.maxClippedFraction;
        rows.add(new DebugRow(
            String.format(Locale.US, "luz %.0f d%.2f c%.2f", quality.brightness,
                quality.darkFraction, quality.clippedFraction),
            exposureOk,
            checks.brightness
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "glare %.3f/%.3f", quality.glareFraction,
                quality.glareBlobFraction),
            quality.glareFraction <= ScannerConfig.DEFAULT.maxGlareFraction
                && quality.glareBlobFraction <= ScannerConfig.DEFAULT.maxGlareBlobFraction,
            checks.glare
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "sombra %.3f/%.3f", quality.illuminationSpread,
                quality.shadowBoundary),
            !hasUnevenLighting(quality),
            checks.shadows
        ));
        boolean borderSignalsAcceptable = quality.edgeGapFraction
                <= ScannerConfig.DEFAULT.maxEdgeGapFraction
            && quality.warmEdgeRunFraction
                <= ScannerConfig.DEFAULT.maxWarmEdgeRunFraction;
        boolean skinSignalAcceptable = !hasSkinEdgeOcclusion(quality, still);
        rows.add(new DebugRow(
            String.format(Locale.US, "borde gap %.2f warm %.2f", quality.edgeGapFraction,
                quality.warmEdgeRunFraction),
            borderSignalsAcceptable,
            checks.edgeOcclusion
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "piel cruza %.3f", quality.skinEdgeRunFraction),
            skinSignalAcceptable,
            checks.edgeOcclusion
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "moire %.3f cov %.2f", quality.moireScore,
                quality.moireCoverage),
            !qualityEvaluator.hasMoire(quality),
            checks.moire
        ));
        boolean stable = result.state == AnalysisResult.State.READY
            || result.state == AnalysisResult.State.CAPTURING
            || (result.stableFrameCount >= ScannerConfig.DEFAULT.stableFrames
                && result.stableDurationMs >= ScannerConfig.DEFAULT.stableDurationMs);
        rows.add(new DebugRow(
            String.format(Locale.US, "estable %df %dms", result.stableFrameCount,
                result.stableDurationMs),
            stable,
            checks.stability
        ));
        MotionSnapshot motion = result.motion;
        if (motion == null || !motion.available) {
            rows.add(new DebugRow("sensor movimiento no disponible", true, false));
        } else if (!motion.sampling) {
            rows.add(new DebugRow("sensor movimiento sin datos", true, false));
        } else {
            rows.add(new DebugRow(
                String.format(
                    Locale.US,
                    "mov g%s a%s %dms",
                    motionValue(motion.gyroscopeRadPerSecond),
                    motionValue(motion.linearAcceleration),
                    motion.quietDurationMs
                ),
                motion.stable,
                checks.stability
            ));
        }
        return rows;
    }

    private String motionValue(float value) {
        return Float.isNaN(value) ? "--" : String.format(Locale.US, "%.2f", value);
    }

    private boolean hasUnevenLighting(QualityMetrics quality) {
        return quality.shadowBoundary > ScannerConfig.DEFAULT.maxSevereShadowBoundary
            || (quality.illuminationSpread > ScannerConfig.DEFAULT.maxIlluminationSpread
                && quality.shadowBoundary > ScannerConfig.DEFAULT.maxShadowBoundary);
    }

    private boolean hasSkinEdgeOcclusion(QualityMetrics quality, boolean still) {
        return still
            ? qualityEvaluator.hasStillSkinEdgeOcclusion(quality)
            : qualityEvaluator.hasPreviewSkinEdgeOcclusion(quality);
    }

    private String fitDebugText(String value, float maximumWidth) {
        if (debugPaint.measureText(value) <= maximumWidth) {
            return value;
        }
        int count = debugPaint.breakText(value, true,
            Math.max(0f, maximumWidth - debugPaint.measureText("…")), null);
        return value.substring(0, Math.max(0, count)).trim() + "…";
    }

    private static final class DebugRow {
        final String text;
        final boolean acceptable;
        final boolean enabled;

        DebugRow(String text, boolean acceptable, boolean enabled) {
            this.text = text;
            this.acceptable = acceptable;
            this.enabled = enabled;
        }
    }

    private void drawInstruction(Canvas canvas, RectF guide, int color) {
        if (instruction.length() == 0) {
            return;
        }
        float maximumTextWidth = Math.max(dp(120f), guide.width() - dp(24f));
        float textSize = sp(17f);
        instructionPaint.setColor(color);
        instructionPaint.setTextSize(textSize);
        while (instructionPaint.measureText(instruction) > maximumTextWidth
            && textSize > sp(13f)) {
            textSize -= sp(0.5f);
            instructionPaint.setTextSize(textSize);
        }

        String displayedInstruction = instruction;
        if (instructionPaint.measureText(displayedInstruction) > maximumTextWidth) {
            String suffix = "…";
            float availableWidth = Math.max(
                0f,
                maximumTextWidth - instructionPaint.measureText(suffix)
            );
            int characterCount = instructionPaint.breakText(
                instruction,
                true,
                availableWidth,
                null
            );
            displayedInstruction = instruction.substring(0, characterCount).trim() + suffix;
        }

        Paint.FontMetrics metrics = instructionPaint.getFontMetrics();
        float textWidth = Math.min(
            maximumTextWidth,
            instructionPaint.measureText(displayedInstruction)
        );
        float boxWidth = textWidth + dp(28f);
        float boxHeight = metrics.descent - metrics.ascent + dp(16f);
        float centerX = getWidth() * 0.5f;
        float top = Math.max(dp(12f), guide.top - boxHeight - dp(14f));
        RectF box = new RectF(
            centerX - boxWidth * 0.5f,
            top,
            centerX + boxWidth * 0.5f,
            top + boxHeight
        );
        canvas.drawRoundRect(box, dp(10f), dp(10f), messageBackgroundPaint);
        float baseline = box.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(displayedInstruction, centerX, baseline, instructionPaint);
    }

    private void drawMessage(Canvas canvas, RectF guide) {
        if (message == null || message.length() == 0) {
            return;
        }
        float maximumTextWidth = Math.max(dp(120f), guide.width() - dp(32f));
        float textSize = sp(18f);
        messagePaint.setColor(messageColor);
        messagePaint.setTextSize(textSize);
        while (messagePaint.measureText(message) > maximumTextWidth && textSize > sp(14f)) {
            textSize -= sp(0.5f);
            messagePaint.setTextSize(textSize);
        }

        Paint.FontMetrics metrics = messagePaint.getFontMetrics();
        float textWidth = Math.min(maximumTextWidth, messagePaint.measureText(message));
        float boxWidth = textWidth + dp(32f);
        float boxHeight = metrics.descent - metrics.ascent + dp(20f);
        float centerX = getWidth() * 0.5f;
        float top = guide.bottom + dp(18f);
        RectF box = new RectF(
            centerX - boxWidth * 0.5f,
            top,
            centerX + boxWidth * 0.5f,
            top + boxHeight
        );
        canvas.drawRoundRect(box, dp(12f), dp(12f), messageBackgroundPaint);
        float baseline = box.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(message, centerX, baseline, messagePaint);
    }

    private RectF guideRect() {
        float horizontalMargin = dp(20f);
        float maximumWidth = Math.max(1f, getWidth() - horizontalMargin * 2f);
        float width = maximumWidth;
        float height = width / ScannerConfig.DEFAULT.expectedDocumentAspect;
        float maximumHeight = getHeight() * (getWidth() > getHeight() ? 0.58f : 0.46f);
        if (height > maximumHeight) {
            height = maximumHeight;
            width = height * ScannerConfig.DEFAULT.expectedDocumentAspect;
        }
        float left = (getWidth() - width) * 0.5f;
        float top = (getHeight() - height) * 0.5f;
        return new RectF(left, top, left + width, top + height);
    }

    private int colorFor(AnalysisResult.State state) {
        switch (state) {
            case READY:
            case CAPTURING:
                return 0xff39d98a;
            case HOLD_STILL:
            case WAITING_FOR_FOCUS:
                return 0xffffc857;
            default:
                return 0xffff5c5c;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
