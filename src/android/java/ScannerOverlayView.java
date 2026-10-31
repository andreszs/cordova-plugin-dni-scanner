package com.crediser.plugins.dniscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScannerOverlayView extends View {
    private static final int PROGRESS_SEGMENTS = 5;
    private static final long PROGRESS_TRANSITION_MS = 260L;
    private static final int PROGRESS_GREEN = 0xff4ade80;
    private static final int PROGRESS_INTERMEDIATE = 0xffa2d54b;
    private static final int PROGRESS_YELLOW = 0xfffacc15;
    private static final int PROGRESS_FUTURE = 0x66ffffff;
    private static final float PORTRAIT_ELEMENT_GAP_DP = 18f;
    private static final float PROGRESS_HEIGHT_DP = 8f;
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint documentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint instructionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messageBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ScannerConfig scannerConfig = ScannerConfig.DEFAULT;
    private AnalysisResult result;
    private int frameWidth;
    private int frameHeight;
    private String instruction = "";
    private String message = "Buscando documento";
    private boolean debugMode;
    private ScanChecks checks = ScanChecks.defaults();
    private int[] progressStartColors;
    private int[] progressTargetColors;
    private long[] progressTransitionStartedAt;

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
        debugPaint.setTextSize(sp(8.5f));
        debugBackgroundPaint.setColor(0x5c000000);
        debugBackgroundPaint.setStyle(Paint.Style.FILL);
        progressPaint.setStyle(Paint.Style.FILL);
    }

    void setInstructionText(String instruction) {
        this.instruction = instruction == null ? "" : instruction.trim();
        invalidate();
    }

    void setScannerConfiguration(ScannerConfig config) {
        scannerConfig = config == null ? ScannerConfig.DEFAULT : config;
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
        this.result = result;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.message = message;
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
        drawInstruction(canvas, guide, guideColor == 0xffef4444 ? guideColor : Color.WHITE);

        if (debugMode && result != null && result.detection != null
            && frameWidth > 0 && frameHeight > 0) {
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
        drawProgress(canvas, guide);
        drawMessage(canvas, guide, guideColor);
        drawDebugPanel(canvas);
    }

    private void drawDebugPanel(Canvas canvas) {
        if (!debugMode) {
            return;
        }
        // Diagnostics are a fixed overlay. They deliberately do not consume or
        // derive space from the guide, progress indicator, or status labels.
        List<DebugRow> rows = debugRows();
        boolean landscape = getWidth() > getHeight();
        int columns = 2;
        int rowsPerColumn = (rows.size() + columns - 1) / columns;
        float lineHeight = sp(10.5f);
        float padding = dp(6f);
        float panelWidth = Math.min(
            landscape ? dp(360f) : dp(310f),
            getWidth() - dp(16f)
        );
        float left = dp(8f);
        float panelHeight = rowsPerColumn * lineHeight + padding * 2f;
        float top = getHeight() - panelHeight - Math.max(dp(8f), getRootWindowInsets() == null
            ? 0f
            : getRootWindowInsets().getStableInsetBottom());
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
        rows.add(new DebugRow(
            frameWidth > 0 && frameHeight > 0
                ? String.format(Locale.US, "frame: %dx%d", frameWidth, frameHeight)
                : "frame: -",
            true,
            false
        ));
        rows.add(new DebugRow("profile: " + scannerConfig.profile.value, true, false));
        rows.add(new DebugRow("state: " + stateName(),
            result != null && result.isReadyCandidate(), true));

        if (result == null || result.detection == null || result.quality == null) {
            rows.add(new DebugRow("source: -", false, false));
            rows.add(new DebugRow("score/aspect: -", false, false));
            rows.add(new DebugRow("area/center: -", false, checks.documentSize));
            rows.add(new DebugRow("perspective: -", false, true));
            rows.add(new DebugRow("focus: -", false, checks.sharpness));
            rows.add(new DebugRow("light: -", false, checks.brightness));
            rows.add(new DebugRow("glare: -", false, checks.glare));
            rows.add(new DebugRow("shadow: -", false, checks.shadows));
            rows.add(new DebugRow("edge: -", false, checks.edgeOcclusion));
            rows.add(new DebugRow("skin: -", false,
                checks.edgeOcclusion && scannerConfig.profile == ScanProfile.DNI));
            rows.add(new DebugRow("hold: -", false, true));
            rows.add(new DebugRow("valid: -", false, true));
            rows.add(motionRow(result == null ? null : result.motion));
            return rows;
        }
        DocumentDetection detection = result.detection;
        QualityMetrics quality = result.quality;
        boolean still = result.source == AnalysisResult.Source.STILL;
        rows.add(new DebugRow("source: " + (still ? "still" : "preview"), true, false));
        rows.add(new DebugRow(
            String.format(Locale.US, "score/aspect: %.3f/%.3f", detection.score,
                detection.observedAspect),
            detection.score >= 0.48f
                && detection.observedAspect >= 1.08f,
            true
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "area/center: %.3f/%.3f", detection.areaFraction,
                detection.centerOffset),
            detection.areaFraction >= 0.14f
                && detection.areaFraction <= 0.88f
                && detection.centerOffset <= 0.34f,
            checks.documentSize || checks.centering
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "geometry: p%.2f r%.2f deg",
                detection.quad.maximumPerspectiveDeviationDegrees(),
                detection.quad.maximumInPlaneRotationDegrees()),
            detection.quad.hasAcceptablePerspective(
                ScannerConfig.MAX_RECTANGLE_DEVIATION_DEGREES,
                ScannerConfig.MAX_SYMMETRIC_PERSPECTIVE_DEVIATION_DEGREES,
                ScannerConfig.MAX_TRAPEZOID_SYMMETRY_ERROR_DEGREES
            ) && detection.quad.maximumInPlaneRotationDegrees()
                <= ScannerConfig.MAX_IN_PLANE_ROTATION_DEGREES,
            true
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "focus: %.0f", quality.sharpness),
            quality.sharpness >= 55.0,
            checks.sharpness
        ));
        boolean exposureOk = quality.brightness >= 36f
            && quality.darkFraction <= 0.45f
            && !(quality.clippedFraction > 0.42f
                && quality.glareBlobFraction > 0.10f);
        rows.add(new DebugRow(
            String.format(Locale.US, "light: %.0f d%.2f c%.2f", quality.brightness,
                quality.darkFraction, quality.clippedFraction),
            exposureOk,
            checks.brightness
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "glare: %.3f/%.3f", quality.glareFraction,
                quality.glareBlobFraction),
            quality.glareBlobFraction <= 0.13f,
            checks.glare
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "shadow: %.3f/%.3f", quality.illuminationSpread,
                quality.shadowBoundary),
            !(quality.illuminationSpread > 0.46f
                && quality.shadowBoundary > 0.28f),
            checks.shadows
        ));
        boolean genericCard = scannerConfig.profile == ScanProfile.GENERIC_CARD;
        rows.add(new DebugRow(
            String.format(Locale.US, "edge: %.2f warm %.2f", quality.edgeGapFraction,
                quality.warmEdgeRunFraction),
            true,
            checks.edgeOcclusion
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "skin: %.3f", quality.skinEdgeRunFraction),
            true,
            checks.edgeOcclusion && !genericCard
        ));
        int requiredFrames = checks.stability
            ? scannerConfig.stableFrames
            : scannerConfig.geometryConfirmationFrames;
        long requiredDuration = checks.stability
            ? scannerConfig.stableDurationMs
            : scannerConfig.geometryConfirmationDurationMs;
        boolean stable = result.state == AnalysisResult.State.READY
            || result.state == AnalysisResult.State.FOCUSING
            || result.state == AnalysisResult.State.CAPTURING
            || result.state == AnalysisResult.State.PROCESSING
            || (result.stableFrameCount >= requiredFrames
                && result.stableDurationMs >= requiredDuration);
        rows.add(new DebugRow(
            String.format(Locale.US, "hold: %df/%dms",
                result.stableFrameCount,
                result.stableDurationMs),
            stable,
            true
        ));
        rows.add(new DebugRow(
            String.format(Locale.US, "valid: g%s/q%s",
                result.geometryValid ? "1" : "0", result.qualityValid ? "1" : "0"),
            result.geometryValid && result.qualityValid,
            true
        ));
        rows.add(motionRow(result.motion));
        return rows;
    }

    private DebugRow motionRow(MotionSnapshot motion) {
        if (motion == null || !motion.available) {
            return new DebugRow("motion: -", false, checks.stability);
        } else if (!motion.sampling) {
            return new DebugRow("motion: -", false, checks.stability);
        }
        return new DebugRow(
            String.format(
                Locale.US,
                "motion: g%s a%s %dms",
                motionValue(motion.gyroscopeRadPerSecond),
                motionValue(motion.linearAcceleration),
                motion.quietDurationMs
            ),
            motion.stable,
            checks.stability
        );
    }

    private String stateName() {
        return result == null || result.state == null
            ? "searching"
            : result.state.name().toLowerCase(Locale.US);
    }

    private String motionValue(float value) {
        return Float.isNaN(value) ? "--" : String.format(Locale.US, "%.2f", value);
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
        boolean landscape = getWidth() > getHeight();
        float top = landscape
            ? guide.top + dp(12f)
            : Math.max(dp(12f),
                guide.top - boxHeight - dp(PORTRAIT_ELEMENT_GAP_DP));
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

    private void drawMessage(Canvas canvas, RectF guide, int color) {
        if (message == null || message.length() == 0) {
            return;
        }
        float maximumTextWidth = Math.max(dp(120f), guide.width() - dp(32f));
        float textSize = sp(18f);
        messagePaint.setColor(color);
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
        boolean landscape = getWidth() > getHeight();
        float top = landscape
            ? guide.bottom - boxHeight - dp(12f)
            : guide.bottom
                + dp(PORTRAIT_ELEMENT_GAP_DP)
                + dp(PROGRESS_HEIGHT_DP)
                + dp(PORTRAIT_ELEMENT_GAP_DP);
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

    private void drawProgress(Canvas canvas, RectF guide) {
        int completed = progressCompleted();
        int[] targets = new int[PROGRESS_SEGMENTS];
        for (int index = 0; index < PROGRESS_SEGMENTS; index++) {
            targets[index] = index < completed || completed == PROGRESS_SEGMENTS
                ? PROGRESS_GREEN
                : index == completed ? PROGRESS_YELLOW : PROGRESS_FUTURE;
        }
        AnalysisResult.State state = result == null
            ? AnalysisResult.State.SEARCHING
            : result.state;
        if (state == AnalysisResult.State.CAPTURING
            || state == AnalysisResult.State.PROCESSING) {
            targets[PROGRESS_SEGMENTS - 1] = PROGRESS_INTERMEDIATE;
        }

        long now = SystemClock.uptimeMillis();
        ensureProgressAnimation(targets, now);
        float totalWidth = Math.min(guide.width() * 0.72f, dp(320f));
        float gap = dp(6f);
        float segmentWidth = (totalWidth - gap * (PROGRESS_SEGMENTS - 1))
            / PROGRESS_SEGMENTS;
        float height = dp(PROGRESS_HEIGHT_DP);
        boolean landscape = getWidth() > getHeight();
        float centerY = landscape
            ? guide.bottom - dp(64f)
            : guide.bottom + dp(PORTRAIT_ELEMENT_GAP_DP) + height * 0.5f;
        float x = (getWidth() - totalWidth) * 0.5f;
        boolean animating = false;
        for (int index = 0; index < PROGRESS_SEGMENTS; index++) {
            progressPaint.setColor(animatedProgressColor(index, now));
            canvas.drawRoundRect(
                new RectF(x, centerY - height * 0.5f, x + segmentWidth,
                    centerY + height * 0.5f),
                height * 0.5f,
                height * 0.5f,
                progressPaint
            );
            animating = animating
                || now - progressTransitionStartedAt[index] < PROGRESS_TRANSITION_MS;
            x += segmentWidth + gap;
        }
        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    private int progressCompleted() {
        if (result == null) {
            return 0;
        }
        switch (result.state) {
            case SEARCHING:
            case RETRYING:
                return 0;
            case MOVE_CLOSER:
            case MOVE_AWAY:
            case CENTER_DOCUMENT:
            case ADJUST_PERSPECTIVE:
            case CROP_INCOMPLETE:
                return result.detection == null ? 0 : 1;
            case IMPROVE_LIGHTING:
            case REDUCE_LIGHTING:
            case AVOID_GLARE:
            case UNEVEN_LIGHTING:
            case EDGE_OCCLUDED:
            case WAITING_FOR_FOCUS:
                return result.detection == null ? 0 : 2;
            case HOLD_STILL:
                return 3;
            case SUCCESS:
                return PROGRESS_SEGMENTS;
            case READY:
            case FOCUSING:
            case CAPTURING:
            case PROCESSING:
                return 4;
            default:
                return 0;
        }
    }

    private void ensureProgressAnimation(int[] targets, long now) {
        if (progressTargetColors == null) {
            progressStartColors = targets.clone();
            progressTargetColors = targets.clone();
            progressTransitionStartedAt = new long[targets.length];
            for (int index = 0; index < targets.length; index++) {
                progressTransitionStartedAt[index] = now;
            }
            return;
        }
        for (int index = 0; index < targets.length; index++) {
            if (progressTargetColors[index] != targets[index]) {
                progressStartColors[index] = animatedProgressColor(index, now);
                progressTargetColors[index] = targets[index];
                progressTransitionStartedAt[index] = now;
            }
        }
    }

    private int animatedProgressColor(int index, long now) {
        float amount = Math.min(1f, Math.max(0f,
            (now - progressTransitionStartedAt[index]) / (float) PROGRESS_TRANSITION_MS));
        amount = amount * amount * (3f - 2f * amount);
        return blendColor(progressStartColors[index], progressTargetColors[index], amount);
    }

    private int blendColor(int from, int to, float amount) {
        int alpha = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount);
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.argb(alpha, red, green, blue);
    }

    private RectF guideRect() {
        float horizontalMargin = dp(20f);
        float maximumWidth = Math.max(1f, getWidth() - horizontalMargin * 2f);
        float width = maximumWidth;
        float height = width / ScannerConfig.DEFAULT.expectedDocumentAspect;
        float maximumHeight = getHeight() * (getWidth() > getHeight() ? 0.80f : 0.46f);
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
            case HOLD_STILL:
            case WAITING_FOR_FOCUS:
            case READY:
            case CAPTURING:
            case PROCESSING:
            case SUCCESS:
                return 0xff4ade80;
            case SEARCHING:
            case RETRYING:
                return 0xffef4444;
            case FOCUSING:
            default:
                return 0xfffacc15;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
