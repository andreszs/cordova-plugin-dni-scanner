package com.crediser.plugins.dniscanner;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DniScannerActivity extends Activity implements LifecycleOwner, FrameAnalyzer.Listener {
    private static final long SUCCESS_VISUAL_FEEDBACK_MS = 260L;
    private static final String TRACE_TAG = "DniScannerTrace";

    public static final String EXTRA_OPTIONS_JSON = "dniScanner.options";
    public static final String EXTRA_IMAGE_WEB_BASE_URL = "dniScanner.imageWebBaseUrl";
    public static final String EXTRA_RESULT_JSON = "dniScanner.result";
    public static final String EXTRA_ERROR_CODE = "dniScanner.errorCode";
    public static final String EXTRA_ERROR_MESSAGE = "dniScanner.errorMessage";
    public static final String EXTRA_ERROR_RECOVERABLE = "dniScanner.errorRecoverable";

    private ScannerConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();

    private LifecycleRegistry lifecycleRegistry;
    private PreviewView previewView;
    private ScannerOverlayView overlayView;
    private Button flashButton;
    private Button captureButton;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private FrameAnalyzer frameAnalyzer;
    private DetectionRegion detectionRegion;
    private DeviceMotionMonitor motionMonitor;
    private InstructionStateStabilizer instructionStateStabilizer;
    private ScanProfile scanProfile;
    private String requestedSide;
    private SideValidationMode sideValidation;
    private String imageWebBaseUrl;
    private String flashMode;
    private String scanOrientation;
    private String instructionText;
    private ScanChecks checks;
    private boolean showCloseButton;
    private boolean showFlashButton;
    private boolean debugMode;
    private boolean beepEnabled;
    private boolean autoCapture;
    private long timeoutMs;
    private boolean capturing;
    private boolean completed;
    private boolean rebindingForRotation;
    private int cameraBindGeneration;
    private int boundDisplayRotation = -1;
    private int pendingDisplayRotation = -1;
    private DisplayManager displayManager;
    private boolean displayListenerRegistered;
    private Runnable timeoutRunnable;
    private AnalysisResult latestAnalysis;
    private int latestFrameWidth;
    private int latestFrameHeight;
    private long captureMotionSequence;
    private MotionSnapshot capturedMotion;
    private String capturedOrientation;
    private DniScannerSuccessBeep successBeep;
    private int focusMeteringSequence;
    private boolean focusMeteringPending;
    private boolean manualCaptureRequested;
    private DocumentQuad captureReferenceQuad;
    private DocumentQuad capturedPreviewQuad;
    private int capturedPreviewWidth;
    private int capturedPreviewHeight;
    private long lastWarmFocusMs;
    private AnalysisResult.State lastTraceState;
    private long lastTraceAnalysisMs;
    private final DisplayManager.DisplayListener displayRotationListener =
        new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                // The scanner always uses the display already hosting its PreviewView.
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // Activity teardown handles removal of the active display.
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (previewView == null
                    || previewView.getDisplay() == null
                    || previewView.getDisplay().getDisplayId() != displayId) {
                    return;
                }
                synchronizeCameraRotation("display", false);
            }
        };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);

        try {
            JSONObject options = new JSONObject(getIntent().getStringExtra(EXTRA_OPTIONS_JSON));
            scanProfile = ScanProfile.fromValue(options.optString("profile", "dni"));
            if (scanProfile == null) {
                throw new IllegalArgumentException("Invalid scan profile");
            }
            requestedSide = options.getString("side");
            sideValidation = SideValidationMode.fromValue(
                options.optString("sideValidation", "off")
            );
            if (sideValidation == null) {
                throw new IllegalArgumentException("Invalid side validation mode");
            }
            config = ScannerConfig.forSession(
                options.optInt(
                    "preferredCaptureLongEdge",
                    ScannerConfig.DEFAULT_PREFERRED_CAPTURE_LONG_EDGE
                ),
                options.optInt("maxOutputLongEdge", ScannerConfig.DEFAULT_MAX_OUTPUT_LONG_EDGE),
                options.optInt(
                    "minEffectiveDocumentLongEdge",
                    ScannerConfig.DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE
                ),
                options.optDouble(
                    "minStillSharpnessVariance",
                    ScannerConfig.DEFAULT_MIN_STILL_SHARPNESS_VARIANCE
                ),
                scanProfile
            );
            instructionStateStabilizer = new InstructionStateStabilizer(
                config.instructionStateConfirmationMs,
                config.instructionLossGraceMs
            );
            flashMode = options.optString("flashMode", "off");
            scanOrientation = options.optString("orientation", "portrait");
            instructionText = options.isNull("instructionText")
                ? ""
                : options.optString("instructionText", "").trim();
            checks = parseChecks(options.optJSONObject("checks"));
            showCloseButton = options.optBoolean("showCloseButton", true);
            showFlashButton = options.optBoolean("showFlashButton", true);
            debugMode = options.optBoolean("debugMode", false);
            beepEnabled = options.optBoolean("beepEnabled", true);
            autoCapture = options.optBoolean("autoCapture", true);
            timeoutMs = options.optLong("timeoutMs", 0L);
            imageWebBaseUrl = getIntent().getStringExtra(EXTRA_IMAGE_WEB_BASE_URL);
            motionMonitor = new DeviceMotionMonitor(this, config);
            successBeep = new DniScannerSuccessBeep(this, mainHandler);
        } catch (Exception error) {
            finishWithError("INVALID_ARGUMENT", "Opciones de captura inválidas.", false);
            return;
        }

        if ("auto".equals(scanOrientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        } else {
            setRequestedOrientation("landscape".equals(scanOrientation)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }

        createUserInterface();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        scheduleTimeout();
        previewView.post(this::startCamera);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (completed || capturing) {
            return;
        }
        previewView.post(() -> synchronizeCameraRotation("configuration", true));
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        registerDisplayRotationListener();
        previewView.post(() -> synchronizeCameraRotation("start", false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        if (motionMonitor != null && checks != null && checks.stability) {
            motionMonitor.start();
        }
    }

    @Override
    protected void onPause() {
        if (motionMonitor != null) {
            motionMonitor.stop();
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterDisplayRotationListener();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        completed = true;
        cameraBindGeneration++;
        unregisterDisplayRotationListener();
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
        if (frameAnalyzer != null) {
            frameAnalyzer.setEnabled(false);
        }
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (motionMonitor != null) {
            motionMonitor.stop();
        }
        if (successBeep != null) {
            successBeep.close();
        }
        analysisExecutor.shutdownNow();
        captureExecutor.shutdownNow();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        cancelByUser();
    }

    private void createUserInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        overlayView = new ScannerOverlayView(this);
        overlayView.setInstructionText(instructionText);
        overlayView.setScannerConfiguration(config);
        overlayView.setDebugConfiguration(debugMode, checks);
        overlayView.update(null, 0, 0, "Buscando documento");
        root.addView(overlayView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        if (showCloseButton || showFlashButton) {
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(dp(12), dp(10), dp(12), dp(8));

            if (showCloseButton) {
                Button closeButton = controlButton("Cerrar");
                closeButton.setOnClickListener(view -> cancelByUser());
                topBar.addView(closeButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48)
                ));
            }

            if (showFlashButton) {
                View spacer = new View(this);
                topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
                flashButton = controlButton("");
                flashButton.setOnClickListener(view -> cycleFlashMode());
                topBar.addView(flashButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48)
                ));
                updateFlashButton();
            }

            FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            );
            root.addView(topBar, topParams);
        }

        captureButton = controlButton("Capturar");
        captureButton.setOnClickListener(view -> beginCapture(true));
        FrameLayout.LayoutParams captureParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48),
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        captureParams.bottomMargin = dp(18);
        root.addView(captureButton, captureParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        setContentView(root);
    }

    private Button controlButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15f);
        button.setBackgroundColor(0xaa202020);
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (completed || isFinishing()) {
                return;
            }
            try {
                cameraProvider = future.get();
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    finishWithError("CAMERA_UNAVAILABLE", "No hay una cámara trasera disponible.", false);
                    return;
                }
                scheduleCameraBindAfterLayout();
            } catch (Exception error) {
                finishWithError("CAMERA_UNAVAILABLE", "No se pudo abrir la cámara.", true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void scheduleCameraBindAfterLayout() {
        int generation = ++cameraBindGeneration;
        pendingDisplayRotation = currentDisplayRotation();
        waitForStablePreviewLayout(generation, 0, 0, -1, 0, 0, 0);
    }

    private void waitForStablePreviewLayout(
        int generation,
        int previousWidth,
        int previousHeight,
        int previousRotation,
        int stablePasses,
        int stableRotationPasses,
        int attempts
    ) {
        if (generation != cameraBindGeneration
            || completed
            || isFinishing()
            || capturing
            || cameraProvider == null) {
            return;
        }

        int width = previewView.getWidth();
        int height = previewView.getHeight();
        int displayRotation = currentDisplayRotation();
        boolean sameSize = width == previousWidth && height == previousHeight;
        boolean sameRotation = displayRotation == previousRotation;
        int nextStablePasses = sameSize ? stablePasses + 1 : 0;
        int nextStableRotationPasses = sameRotation ? stableRotationPasses + 1 : 0;
        if (displayRotation != pendingDisplayRotation) {
            trace("rotation:layout_update pending=" + rotationText(pendingDisplayRotation)
                + " display=" + rotationText(displayRotation));
            pendingDisplayRotation = displayRotation;
        }
        boolean landscape = getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE;
        boolean orientationReady = landscape ? width >= height : height >= width;
        boolean viewPortReady = previewView.getViewPort() != null;

        if (width > 0
            && height > 0
            && orientationReady
            && viewPortReady
            && nextStablePasses >= 1
            && nextStableRotationPasses >= 1) {
            bindCameraUseCases(generation, displayRotation);
            return;
        }

        if (attempts >= 30) {
            if (width > 0 && height > 0 && viewPortReady) {
                bindCameraUseCases(generation, displayRotation);
            } else {
                finishWithError("CAMERA_UNAVAILABLE", "No se pudo preparar la vista de cámara.", true);
            }
            return;
        }

        previewView.postOnAnimation(() -> waitForStablePreviewLayout(
            generation,
            width,
            height,
            displayRotation,
            nextStablePasses,
            nextStableRotationPasses,
            attempts + 1
        ));
    }

    private void bindCameraUseCases(int generation, int targetRotation) {
        if (generation != cameraBindGeneration
            || targetRotation != currentDisplayRotation()) {
            scheduleCameraBindAfterLayout();
            return;
        }
        if (frameAnalyzer != null) {
            frameAnalyzer.setEnabled(false);
        }
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        cameraProvider.unbindAll();
        Preview preview = new Preview.Builder()
            .setTargetRotation(targetRotation)
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(new android.util.Size(1280, 720))
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        detectionRegion = overlayView.detectionRegion(config.guideMarginFraction);
        frameAnalyzer = new FrameAnalyzer(config, checks, detectionRegion, this);
        frameAnalyzer.setEnabled(false);
        imageAnalysis.setAnalyzer(analysisExecutor, frameAnalyzer);

        // Legacy devices can spend several extra seconds on CameraX's quality
        // convergence path. The still is independently validated after capture,
        // so latency mode is the safer choice on Android 6 and older.
        int captureMode = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M
            ? ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            : ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY;
        ImageCapture.Builder imageCaptureBuilder = new ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setTargetRotation(targetRotation);
        CaptureResolutionConfigurator.apply(
            imageCaptureBuilder,
            config.preferredCaptureLongEdge,
            targetRotation
        );
        imageCapture = imageCaptureBuilder.build();

        try {
            UseCaseGroup.Builder groupBuilder = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .addUseCase(imageCapture);
            ViewPort viewPort = previewView.getViewPort();
            if (viewPort != null) {
                groupBuilder.setViewPort(viewPort);
            }
            camera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                groupBuilder.build()
            );
            boundDisplayRotation = targetRotation;
            pendingDisplayRotation = targetRotation;
            trace("rotation:bound display=" + rotationText(targetRotation)
                + " size=" + previewView.getWidth() + "x" + previewView.getHeight()
                + " mode=" + scanOrientation);
            boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
            if (flashButton != null) {
                flashButton.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
            }
            if (!hasFlash) {
                flashMode = "off";
            }
            applyFlashMode();
            normalizeZoomAndEnableAnalysis(camera, frameAnalyzer, 0);
        } catch (Exception error) {
            finishWithError("CAMERA_UNAVAILABLE", "No se pudo configurar la cámara.", true);
        }
    }

    private void normalizeZoomAndEnableAnalysis(
        @NonNull Camera boundCamera,
        @NonNull FrameAnalyzer boundAnalyzer,
        int attempt
    ) {
        ZoomState zoomState = boundCamera.getCameraInfo().getZoomState().getValue();
        float targetZoom = 1.0f;
        if (zoomState != null) {
            targetZoom = Math.max(
                zoomState.getMinZoomRatio(),
                Math.min(zoomState.getMaxZoomRatio(), targetZoom)
            );
        }

        final float requestedZoom = targetZoom;
        final ListenableFuture<Void> zoomFuture;
        try {
            zoomFuture = boundCamera.getCameraControl().setZoomRatio(requestedZoom);
        } catch (RuntimeException error) {
            enableAnalysisAfterZoomReset(boundCamera, boundAnalyzer);
            return;
        }

        zoomFuture.addListener(() -> {
            if (completed
                || camera != boundCamera
                || frameAnalyzer != boundAnalyzer) {
                return;
            }

            ZoomState appliedState = boundCamera.getCameraInfo().getZoomState().getValue();
            if (attempt == 0
                && appliedState != null
                && Math.abs(appliedState.getZoomRatio() - requestedZoom) > 0.01f) {
                normalizeZoomAndEnableAnalysis(boundCamera, boundAnalyzer, 1);
                return;
            }
            enableAnalysisAfterZoomReset(boundCamera, boundAnalyzer);
        }, ContextCompat.getMainExecutor(this));
    }

    private void enableAnalysisAfterZoomReset(
        @NonNull Camera boundCamera,
        @NonNull FrameAnalyzer boundAnalyzer
    ) {
        if (completed
            || camera != boundCamera
            || frameAnalyzer != boundAnalyzer) {
            return;
        }
        boundAnalyzer.setEnabled(true);
        rebindingForRotation = false;
    }

    private void synchronizeCameraRotation(String reason, boolean forceRebind) {
        if (completed || isFinishing() || capturing || previewView == null) {
            return;
        }
        int displayRotation = currentDisplayRotation();
        trace("rotation:event reason=" + reason
            + " display=" + rotationText(displayRotation)
            + " bound=" + rotationText(boundDisplayRotation)
            + " pending=" + rotationText(pendingDisplayRotation)
            + " config=" + currentOrientation()
            + " size=" + previewView.getWidth() + "x" + previewView.getHeight());
        if (cameraProvider == null) {
            return;
        }
        if (rebindingForRotation && displayRotation == pendingDisplayRotation) {
            return;
        }
        if (!forceRebind && displayRotation == boundDisplayRotation) {
            return;
        }
        rebindCameraAfterRotation(reason, displayRotation);
    }

    private void rebindCameraAfterRotation(String reason, int displayRotation) {
        trace("rotation:rebind reason=" + reason
            + " display=" + rotationText(displayRotation)
            + " previous=" + rotationText(boundDisplayRotation));
        rebindingForRotation = true;
        pendingDisplayRotation = displayRotation;
        latestAnalysis = null;
        latestFrameWidth = 0;
        latestFrameHeight = 0;
        if (frameAnalyzer != null) {
            frameAnalyzer.setEnabled(false);
        }
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        imageCapture = null;
        camera = null;
        overlayView.update(null, 0, 0, "Girando cámara…");
        scheduleCameraBindAfterLayout();
    }

    private void registerDisplayRotationListener() {
        if (displayManager == null || displayListenerRegistered) {
            return;
        }
        displayManager.registerDisplayListener(displayRotationListener, mainHandler);
        displayListenerRegistered = true;
    }

    private void unregisterDisplayRotationListener() {
        if (displayManager == null || !displayListenerRegistered) {
            return;
        }
        displayManager.unregisterDisplayListener(displayRotationListener);
        displayListenerRegistered = false;
    }

    private int currentDisplayRotation() {
        return previewView.getDisplay() == null
            ? Surface.ROTATION_0
            : previewView.getDisplay().getRotation();
    }

    private static String rotationText(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return "0";
            case Surface.ROTATION_90:
                return "90";
            case Surface.ROTATION_180:
                return "180";
            case Surface.ROTATION_270:
                return "270";
            default:
                return "-";
        }
    }

    private String currentOrientation() {
        return getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait";
    }

    private void lockAutoOrientationForCapture() {
        if ("auto".equals(scanOrientation)) {
            capturedOrientation = currentOrientation();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
    }

    private void unlockAutoOrientationAfterRetry() {
        if ("auto".equals(scanOrientation) && !completed && !isFinishing()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    @Override
    public void onAnalysis(AnalysisResult result, int frameWidth, int frameHeight) {
        if (rebindingForRotation) {
            return;
        }
        MotionSnapshot motion = motionSnapshot();
        AnalysisResult displayedResult = result;
        if (checks.stability
            && result.state == AnalysisResult.State.READY
            && !motion.stable) {
            displayedResult = copyAnalysisWithState(result, AnalysisResult.State.HOLD_STILL);
        }
        displayedResult.motion = motion;
        if (debugMode && (displayedResult.state != lastTraceState
                || displayedResult.timestampMs - lastTraceAnalysisMs >= 500L)) {
            trace("preview state=" + displayedResult.state.name()
                + " frame=" + frameWidth + "x" + frameHeight
                + " detection=" + detectionText(displayedResult.detection)
                + " quality=" + qualityText(displayedResult.quality)
                + " hold=" + displayedResult.stableFrameCount + "f/"
                + displayedResult.stableDurationMs + "ms"
                + " motion=" + motionText(motion));
            lastTraceState = displayedResult.state;
            lastTraceAnalysisMs = displayedResult.timestampMs;
        }
        latestAnalysis = displayedResult;
        latestFrameWidth = frameWidth;
        latestFrameHeight = frameHeight;
        AnalysisResult finalDisplayedResult = displayedResult;
        mainHandler.post(() -> {
            if (completed || isFinishing()) {
                return;
            }
            if (capturing) {
                return;
            }
            AnalysisResult.State instructionState = instructionStateStabilizer.update(
                finalDisplayedResult.state,
                finalDisplayedResult.timestampMs
            );
            AnalysisResult visibleResult = copyAnalysisWithState(
                finalDisplayedResult,
                instructionState
            );
            overlayView.update(
                visibleResult,
                frameWidth,
                frameHeight,
                statusText(instructionState)
            );
            if (finalDisplayedResult.state == AnalysisResult.State.HOLD_STILL
                && finalDisplayedResult.stableDurationMs >= 180L) {
                warmFocusAtDocument(finalDisplayedResult);
            }
            if (autoCapture && finalDisplayedResult.state == AnalysisResult.State.READY) {
                beginAutomaticCapture();
            }
        });
    }

    @Override
    public void onAnalysisFailure(Throwable error) {
        mainHandler.post(() -> {
            if (!completed && !capturing) {
                overlayView.update(
                    null,
                    0,
                    0,
                    "No se pudo analizar la cámara"
                );
            }
        });
    }

    private void beginAutomaticCapture() {
        beginCapture(false);
    }

    private void beginCapture(boolean manual) {
        if (capturing || imageCapture == null || completed) {
            return;
        }
        MotionSnapshot motion = motionSnapshot();
        if (!manual && checks.stability && !motion.stable) {
            return;
        }
        lockAutoOrientationForCapture();
        capturing = true;
        manualCaptureRequested = manual;
        captureReferenceQuad = latestAnalysis == null || latestAnalysis.detection == null
            ? null : latestAnalysis.detection.quad;
        trace("capture:begin manual=" + manual
            + " frame=" + latestFrameWidth + "x" + latestFrameHeight
            + " detection=" + detectionText(
                latestAnalysis == null ? null : latestAnalysis.detection
            ) + " motion=" + motionText(motion));
        capturedMotion = null;
        captureMotionSequence = motion.motionSequence;
        if (captureButton != null) {
            captureButton.setEnabled(false);
        }
        showCaptureStage(AnalysisResult.State.FOCUSING, "Enfocando…", motion);
        mainHandler.postDelayed(this::focusAndCaptureStillImage, config.captureDelayMs);
    }

    private void focusAndCaptureStillImage() {
        if (completed || !capturing || imageCapture == null) {
            return;
        }
        final int sequence = ++focusMeteringSequence;
        trace("capture:focus_start sequence=" + sequence
            + " freshGeometry=" + hasFreshCaptureGeometry());
        focusMeteringPending = true;
        mainHandler.postDelayed(
            () -> completeFocusMetering(sequence),
            config.focusMeteringTimeoutMs
        );

        if (!manualCaptureRequested && !hasFreshCaptureGeometry()) {
            retryAfterInvalidStill(statusText(AnalysisResult.State.HOLD_STILL));
            return;
        }
        if (camera == null || latestAnalysis == null || latestAnalysis.detection == null
            || latestFrameWidth <= 0 || latestFrameHeight <= 0
            || previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
            return;
        }
        MeteringPoint point = meteringPoint(latestAnalysis, latestFrameWidth, latestFrameHeight);
        FocusMeteringAction action = new FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(2, TimeUnit.SECONDS).build();
        try {
            ListenableFuture<FocusMeteringResult> focus = camera.getCameraControl()
                .startFocusAndMetering(action);
            focus.addListener(() -> {
                try {
                    if (focus.get().isFocusSuccessful()) {
                        trace("capture:focus_success sequence=" + sequence);
                        completeFocusMetering(sequence);
                    }
                } catch (Exception ignored) {
                    // The bounded timeout below is the fallback for unsupported or failed AF/AE.
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception ignored) {
            // The bounded timeout below is the fallback for unsupported or failed AF/AE.
        }
    }

    private void completeFocusMetering(int sequence) {
        if (!focusMeteringPending || sequence != focusMeteringSequence
            || completed || !capturing) {
            return;
        }
        focusMeteringPending = false;
        trace("capture:focus_complete sequence=" + sequence
            + " freshGeometry=" + hasFreshCaptureGeometry());
        if (!manualCaptureRequested && !hasFreshCaptureGeometry()) {
            retryAfterInvalidStill(statusText(AnalysisResult.State.HOLD_STILL));
            return;
        }
        captureStillImage();
    }

    private void captureStillImage() {
        if (completed || imageCapture == null) {
            return;
        }
        MotionSnapshot motion = motionSnapshot();
        trace("capture:shutter_gate motion=" + motionText(motion)
            + " detection=" + detectionText(
                latestAnalysis == null ? null : latestAnalysis.detection
            ));
        if (checks.stability && !motion.stable) {
            retryAfterDeviceMotion();
            return;
        }
        showCaptureStage(
            AnalysisResult.State.CAPTURING,
            "Tomando foto",
            motion
        );
        capturedPreviewQuad = latestAnalysis == null || latestAnalysis.detection == null
            ? captureReferenceQuad : latestAnalysis.detection.quad;
        capturedPreviewWidth = latestFrameWidth;
        capturedPreviewHeight = latestFrameHeight;
        captureMotionSequence = motion.motionSequence;
        File directory = new File(getCacheDir(), "dni-scanner");
        if (!directory.exists() && !directory.mkdirs()) {
            finishWithError("PROCESSING_FAILED", "No se pudo preparar el almacenamiento temporal.", false);
            return;
        }
        File rawFile = new File(directory, "raw-" + UUID.randomUUID() + ".jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(rawFile).build();
        imageCapture.takePicture(outputOptions, captureExecutor,
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    trace("capture:image_saved bytes=" + rawFile.length());
                    // Keep the preview/session alive while post-processing, but
                    // stop redundant frame analysis once the shutter result exists.
                    if (frameAnalyzer != null) {
                        frameAnalyzer.setEnabled(false);
                    }
                    MotionSnapshot motion = motionSnapshot();
                    // Motion is gated immediately before the shutter. Once a JPEG
                    // exists, its measured sharpness is a more reliable verdict
                    // than a transient sensor spike during camera processing.
                    capturedMotion = motion;
                    mainHandler.post(() -> {
                        if (completed || isFinishing()) {
                            return;
                        }
                        showCaptureStage(
                            AnalysisResult.State.PROCESSING,
                            "Procesando imagen…",
                            motion
                        );
                    });
                    processCapturedImage(rawFile, directory);
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TRACE_TAG, "capture:image_error", exception);
                    rawFile.delete();
                    finishWithErrorOnMain("CAPTURE_FAILED", "No se pudo tomar la fotografía.", true);
                }
            }
        );
    }

    private void processCapturedImage(File rawFile, File directory) {
        File finalFile = new File(directory, "dni-" + UUID.randomUUID() + ".jpg");
        try {
            StillImageProcessor processor = new StillImageProcessor(
                config,
                checks,
                detectionRegion,
                capturedPreviewQuad,
                capturedPreviewWidth,
                capturedPreviewHeight,
                debugMode
            );
            StillImageProcessor.Result result = processor.process(rawFile, finalFile);
            RecognitionResult recognition = result.recognition;
            if (scanProfile == ScanProfile.DNI
                && sideValidation == SideValidationMode.STRICT
                && !recognition.matchesRequestedSide(requestedSide)) {
                finalFile.delete();
                boolean frontRequested = "front".equals(requestedSide);
                String requestedLabel = frontRequested ? "frente" : "dorso";
                if ("unknown".equals(recognition.side)) {
                    retryAfterInvalidStill("No se pudo confirmar el " + requestedLabel);
                } else {
                    retryAfterInvalidStill("\u26A0  Mostrá el " + requestedLabel + " del DNI");
                }
                return;
            }
            JSONObject json = buildResult(result);
            trace("capture:success output=" + result.width + "x" + result.height
                + " effective=" + result.effectiveDocumentLongEdge
                + " detection=" + detectionText(result.detection)
                + " quality=" + qualityText(result.quality));
            mainHandler.post(() -> finishWithSuccess(json));
        } catch (StillImageProcessor.RetryableProcessingException retryable) {
            trace("capture:retry reason=" + retryable.getMessage()
                + " state=" + (retryable.state == null ? "-" : retryable.state.name())
                + " frame=" + retryable.frameWidth + "x" + retryable.frameHeight
                + " detection=" + detectionText(retryable.detection)
                + " quality=" + qualityText(retryable.quality));
            finalFile.delete();
            if (retryable.state == AnalysisResult.State.EDGE_OCCLUDED
                || retryable.state == AnalysisResult.State.CROP_INCOMPLETE) {
                retryAfterInvalidStill(
                    statusText(retryable.state),
                    diagnosticResult(retryable),
                    retryable.frameWidth,
                    retryable.frameHeight
                );
                return;
            }
            retryAfterInvalidStill(
                retryable.state == null ? "Reintentando captura" : statusText(retryable.state),
                diagnosticResult(retryable),
                retryable.frameWidth,
                retryable.frameHeight
            );
        } catch (Exception error) {
            Log.e(TRACE_TAG, "capture:processing_error", error);
            finalFile.delete();
            finishWithErrorOnMain("PROCESSING_FAILED", "No se pudo procesar la fotografía.", true);
        } finally {
            rawFile.delete();
        }
    }

    private void retryAfterDeviceMotion() {
        MotionSnapshot motion = motionSnapshot();
        AnalysisResult diagnostic = latestAnalysis == null
            ? null
            : copyAnalysisWithState(latestAnalysis, AnalysisResult.State.HOLD_STILL);
        if (diagnostic != null) {
            diagnostic.motion = motion;
        }
        retryAfterInvalidStill(
            statusText(AnalysisResult.State.HOLD_STILL),
            diagnostic,
            latestFrameWidth,
            latestFrameHeight
        );
    }

    private MotionSnapshot motionSnapshot() {
        if (motionMonitor == null || !checks.stability) {
            return new MotionSnapshot(
                false,
                false,
                false,
                false,
                true,
                Float.NaN,
                Float.NaN,
                0L,
                0L
            );
        }
        return motionMonitor.snapshot();
    }

    private void showCaptureStage(AnalysisResult.State state, String message,
                                  MotionSnapshot motion) {
        instructionStateStabilizer.force(state);
        if (latestAnalysis == null) {
            overlayView.update(null, 0, 0, message);
            return;
        }
        AnalysisResult stage = copyAnalysisWithState(latestAnalysis, state);
        stage.motion = motion;
        overlayView.update(
            stage,
            latestFrameWidth,
            latestFrameHeight,
            message
        );
    }

    private AnalysisResult copyAnalysisWithState(AnalysisResult source,
                                                  AnalysisResult.State state) {
        AnalysisResult copy = new AnalysisResult(
            state,
            source.detection,
            source.quality,
            source.geometryValid,
            source.qualityValid,
            source.timestampMs,
            source.source
        );
        copy.stableFrameCount = source.stableFrameCount;
        copy.stableDurationMs = source.stableDurationMs;
        copy.motion = source.motion;
        return copy;
    }

    private void retryAfterInvalidStill(String message) {
        retryAfterInvalidStill(message, null, 0, 0);
    }

    private void retryAfterInvalidStill(String message, AnalysisResult diagnostic, int frameWidth,
                                        int frameHeight) {
        trace("capture:retry_ui message=" + message
            + " diagnostic=" + (diagnostic == null ? "-" : diagnostic.state.name())
            + " frame=" + frameWidth + "x" + frameHeight);
        mainHandler.post(() -> {
            if (completed || isFinishing()) {
                return;
            }
            capturing = false;
            manualCaptureRequested = false;
            captureReferenceQuad = null;
            if (captureButton != null) {
                captureButton.setEnabled(true);
            }
            if (instructionStateStabilizer != null) {
                instructionStateStabilizer.force(AnalysisResult.State.RETRYING);
            }
            focusMeteringPending = false;
            focusMeteringSequence++;
            unlockAutoOrientationAfterRetry();
            previewView.post(() -> synchronizeCameraRotation("capture_retry", false));
            AnalysisResult retryResult = diagnostic != null
                ? copyAnalysisWithState(diagnostic, AnalysisResult.State.RETRYING)
                : latestAnalysis == null
                    ? null
                    : copyAnalysisWithState(latestAnalysis, AnalysisResult.State.RETRYING);
            overlayView.update(
                retryResult,
                diagnostic != null ? frameWidth : latestFrameWidth,
                diagnostic != null ? frameHeight : latestFrameHeight,
                message
            );
            mainHandler.postDelayed(() -> {
                if (!completed && !capturing && frameAnalyzer != null) {
                    instructionStateStabilizer.force(AnalysisResult.State.SEARCHING);
                    overlayView.update(null, 0, 0, "Buscando documento");
                    frameAnalyzer.setEnabled(true);
                }
            }, config.retryMessageDurationMs);
        });
    }

    private void trace(String message) {
        if (debugMode) {
            Log.i(TRACE_TAG, message);
        }
    }

    private String detectionText(DocumentDetection detection) {
        if (detection == null) {
            return "-";
        }
        DocumentQuad quad = detection.quad;
        return String.format(
            Locale.US,
            "score=%.4f area=%.4f center=%.4f aspect=%.4f quad=[(%.1f,%.1f),(%.1f,%.1f),(%.1f,%.1f),(%.1f,%.1f)]",
            detection.score, detection.areaFraction, detection.centerOffset,
            detection.observedAspect,
            quad.topLeft.x, quad.topLeft.y,
            quad.topRight.x, quad.topRight.y,
            quad.bottomRight.x, quad.bottomRight.y,
            quad.bottomLeft.x, quad.bottomLeft.y
        );
    }

    private String qualityText(QualityMetrics quality) {
        if (quality == null) {
            return "-";
        }
        return String.format(
            Locale.US,
            "focus=%.2f light=%.2f dark=%.4f clipped=%.4f glare=%.4f perspective=%.4f spread=%.4f shadow=%.4f",
            quality.sharpness, quality.brightness, quality.darkFraction,
            quality.clippedFraction, quality.glareBlobFraction,
            quality.perspectiveScore, quality.illuminationSpread,
            quality.shadowBoundary
        );
    }

    private String motionText(MotionSnapshot motion) {
        if (motion == null) {
            return "-";
        }
        return String.format(
            Locale.US,
            "stable=%s gyro=%.4f accel=%.4f quiet=%dms seq=%d",
            motion.stable, motion.gyroscopeRadPerSecond,
            motion.linearAcceleration, motion.quietDurationMs,
            motion.motionSequence
        );
    }

    private boolean hasFreshCaptureGeometry() {
        AnalysisResult current = latestAnalysis;
        if (current == null || current.detection == null || captureReferenceQuad == null
            || latestFrameWidth <= 0 || latestFrameHeight <= 0
            || android.os.SystemClock.elapsedRealtime() - current.timestampMs > 550L) {
            return false;
        }
        if (current.state != AnalysisResult.State.READY
            && current.state != AnalysisResult.State.HOLD_STILL) {
            return false;
        }
        return OpenCvDocumentEngine.quadDistance(
            captureReferenceQuad,
            current.detection.quad,
            latestFrameWidth,
            latestFrameHeight
        ) <= 0.045f;
    }

    private MeteringPoint meteringPoint(AnalysisResult analysis, int frameWidth, int frameHeight) {
        PointF center = analysis.detection.quad.center();
        float scale = Math.max(
            previewView.getWidth() / (float) frameWidth,
            previewView.getHeight() / (float) frameHeight
        );
        float offsetX = (previewView.getWidth() - frameWidth * scale) * 0.5f;
        float offsetY = (previewView.getHeight() - frameHeight * scale) * 0.5f;
        return previewView.getMeteringPointFactory().createPoint(
            offsetX + center.x * scale,
            offsetY + center.y * scale,
            0.15f
        );
    }

    private void warmFocusAtDocument(AnalysisResult analysis) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (camera == null || analysis.detection == null || now - lastWarmFocusMs < 900L
            || latestFrameWidth <= 0 || latestFrameHeight <= 0) {
            return;
        }
        lastWarmFocusMs = now;
        try {
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                meteringPoint(analysis, latestFrameWidth, latestFrameHeight),
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(2, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception ignored) {
            // Some camera providers do not expose metering; capture remains available.
        }
    }

    private AnalysisResult diagnosticResult(
        StillImageProcessor.RetryableProcessingException retryable
    ) {
        if (!debugMode || retryable.detection == null || retryable.quality == null) {
            return null;
        }
        return new AnalysisResult(
            retryable.state == null ? AnalysisResult.State.SEARCHING : retryable.state,
            retryable.detection,
            retryable.quality,
            false,
            false,
            android.os.SystemClock.elapsedRealtime(),
            AnalysisResult.Source.STILL
        );
    }

    private JSONObject buildResult(StillImageProcessor.Result result) throws JSONException {
        RecognitionResult recognition = result.recognition;
        QualityMetrics quality = result.quality;
        boolean exposeSide = scanProfile == ScanProfile.DNI
            && sideValidation != SideValidationMode.OFF;
        String detectedSide = exposeSide ? recognition.side : "unknown";
        Integer detectedVariant = exposeSide ? recognition.variant : null;
        float sideConfidence = exposeSide ? recognition.sideConfidence : 0f;
        float variantConfidence = exposeSide ? recognition.variantConfidence : 0f;
        boolean usableForOcr = usableForOcr(quality);
        JSONObject confidence = new JSONObject();
        confidence.put("document", round(result.detection.score));
        confidence.put("content", round(recognition.contentScore));
        confidence.put("side", round(sideConfidence));
        confidence.put("variant", round(variantConfidence));
        confidence.put("overall", round(result.detection.score));

        JSONObject qualityJson = new JSONObject();
        qualityJson.put("sharpness", round(sharpnessScore(quality.sharpness)));
        qualityJson.put("sharpnessVariance", round(quality.sharpness));
        qualityJson.put("brightness", round(quality.brightness / 255f));
        qualityJson.put("glare", round(Math.max(quality.glareFraction, quality.glareBlobFraction)));
        qualityJson.put("perspective", round(quality.perspectiveScore));
        qualityJson.put("darkClipping", round(quality.darkFraction));
        qualityJson.put("highlightClipping", round(quality.clippedFraction));
        qualityJson.put("edgeGap", round(quality.edgeGapFraction));
        qualityJson.put("edgeObstruction", round(quality.warmEdgeRunFraction));
        qualityJson.put("illuminationSpread", round(quality.illuminationSpread));
        qualityJson.put("shadowBoundary", round(quality.shadowBoundary));
        qualityJson.put("skinEdgeObstruction", round(quality.skinEdgeRunFraction));

        MotionSnapshot motion = capturedMotion == null ? motionSnapshot() : capturedMotion;
        JSONObject motionJson = new JSONObject();
        motionJson.put("sensorAvailable", motion.available);
        motionJson.put("gyroscopeAvailable", motion.gyroscopeAvailable);
        motionJson.put("accelerationAvailable", motion.accelerationAvailable);
        motionJson.put("sampling", motion.sampling);
        motionJson.put("stable", motion.stable);
        motionJson.put(
            "gyroscopeRadPerSecond",
            Float.isNaN(motion.gyroscopeRadPerSecond)
                ? JSONObject.NULL : round(motion.gyroscopeRadPerSecond)
        );
        motionJson.put(
            "linearAcceleration",
            Float.isNaN(motion.linearAcceleration)
                ? JSONObject.NULL : round(motion.linearAcceleration)
        );
        motionJson.put("quietDurationMs", motion.quietDurationMs);

        JSONArray signals = new JSONArray();
        for (String signal : recognition.signals) {
            if (exposeSide || "printed_content".equals(signal)) {
                signals.put(signal);
            }
        }
        JSONArray warnings = qualityWarnings(quality);

        JSONObject json = new JSONObject();
        json.put("status", "success");
        String imageUri = imageWebBaseUrl == null
            ? Uri.fromFile(result.file).toString()
            : imageWebBaseUrl + result.file.getName();
        json.put("imageUri", imageUri);
        json.put("imagePath", result.file.getAbsolutePath());
        json.put("profile", scanProfile.value);
        json.put("requestedSide", requestedSide);
        json.put("sideValidation", sideValidation.value);
        json.put("detectedSide", detectedSide);
        json.put("sideMatchesRequested", "unknown".equals(detectedSide)
            ? JSONObject.NULL : requestedSide.equals(detectedSide));
        json.put("variant", detectedVariant == null ? JSONObject.NULL : detectedVariant);
        json.put("documentCandidate", true);
        json.put("usableForOcr", usableForOcr);
        json.put("contentScore", round(recognition.contentScore));
        json.put("qualityWarnings", warnings);
        json.put("confidence", confidence);
        json.put("width", result.width);
        json.put("height", result.height);
        json.put("rawWidth", result.rawWidth);
        json.put("rawHeight", result.rawHeight);
        json.put("effectiveDocumentLongEdge", result.effectiveDocumentLongEdge);
        json.put("preferredCaptureLongEdge", config.preferredCaptureLongEdge);
        json.put("maxOutputLongEdge", config.maxOutputLongEdge);
        json.put("minEffectiveDocumentLongEdge", config.minEffectiveDocumentLongEdge);
        json.put("minStillSharpnessVariance", round(config.minStillSharpnessVariance));
        json.put("mimeType", "image/jpeg");
        json.put("bytes", result.file.length());
        json.put("quality", qualityJson);
        json.put("motion", motionJson);
        json.put("appliedChecks", checksJson());
        json.put("recognitionSignals", signals);
        json.put("perspectiveCorrected", false);
        json.put("flashMode", flashMode);
        json.put("orientation", capturedOrientation == null
            ? currentOrientation() : capturedOrientation);
        json.put("orientationMode", scanOrientation);
        json.put("autoCapture", autoCapture);
        json.put("debugMode", debugMode);
        if ("auto".equals(flashMode)) {
            json.put("flashFired", JSONObject.NULL);
        } else {
            json.put("flashFired", "on".equals(flashMode));
        }
        json.put("capturedAt", isoTimestamp());
        return json;
    }

    private ScanChecks parseChecks(JSONObject value) {
        JSONObject source = value == null ? new JSONObject() : value;
        return new ScanChecks(
            source.optBoolean("documentSize", true),
            source.optBoolean("centering", true),
            true,
            source.optBoolean("stability", true),
            source.optBoolean("sharpness", true),
            source.optBoolean("brightness", true),
            source.optBoolean("glare", true),
            source.optBoolean("shadows", true),
            source.optBoolean("edgeOcclusion", true)
        );
    }

    private JSONObject checksJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("documentSize", checks.documentSize);
        json.put("centering", checks.centering);
        json.put("perspective", checks.perspective);
        json.put("stability", checks.stability);
        json.put("sharpness", checks.sharpness);
        json.put("brightness", checks.brightness);
        json.put("glare", checks.glare);
        json.put("shadows", checks.shadows);
        json.put("edgeOcclusion", checks.edgeOcclusion);
        return json;
    }

    private boolean usableForOcr(QualityMetrics quality) {
        return quality.sharpness >= 55.0
            && quality.brightness >= 35f
            && quality.brightness <= 245f
            && quality.darkFraction <= 0.50f
            && quality.clippedFraction <= 0.60f
            && quality.glareBlobFraction <= 0.18f;
    }

    private JSONArray qualityWarnings(QualityMetrics quality) {
        JSONArray warnings = new JSONArray();
        if (!checks.sharpness
            && quality.sharpness < config.minStillSharpnessVariance) {
            warnings.put("low_sharpness");
        }
        if (quality.brightness < 54f || quality.darkFraction > 0.34f) {
            warnings.put("low_light");
        }
        if (quality.clippedFraction > 0.36f && quality.glareBlobFraction > 0.08f) {
            warnings.put("high_light");
        }
        if (quality.glareBlobFraction > 0.09f) {
            warnings.put("glare");
        }
        if (quality.illuminationSpread > 0.39f
            && quality.shadowBoundary > 0.23f) {
            warnings.put("uneven_lighting");
        }
        return warnings;
    }

    private void cycleFlashMode() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            return;
        }
        if ("off".equals(flashMode)) {
            flashMode = "auto";
        } else if ("auto".equals(flashMode)) {
            flashMode = "on";
        } else {
            flashMode = "off";
        }
        applyFlashMode();
        updateFlashButton();
    }

    private void applyFlashMode() {
        if (camera == null || imageCapture == null) {
            return;
        }
        if ("on".equals(flashMode)) {
            camera.getCameraControl().enableTorch(true);
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
        } else {
            camera.getCameraControl().enableTorch(false);
            imageCapture.setFlashMode(
                "auto".equals(flashMode) ? ImageCapture.FLASH_MODE_AUTO : ImageCapture.FLASH_MODE_OFF
            );
        }
    }

    private void updateFlashButton() {
        if (flashButton == null) {
            return;
        }
        String label;
        if ("on".equals(flashMode)) {
            label = "Flash: sí";
        } else if ("auto".equals(flashMode)) {
            label = "Flash: auto";
        } else {
            label = "Flash: no";
        }
        flashButton.setText(label);
    }

    private String statusText(AnalysisResult.State state) {
        switch (state) {
            case MOVE_CLOSER:
                return "Acercá la cámara";
            case MOVE_AWAY:
                return "Alejá la cámara";
            case CENTER_DOCUMENT:
                return "Centrá el documento";
            case ADJUST_PERSPECTIVE:
                return "Alineá el documento con el marco";
            case IMPROVE_LIGHTING:
                return "Luz insuficiente";
            case REDUCE_LIGHTING:
                return "Demasiada luz";
            case AVOID_GLARE:
                return "Reflejos detectados";
            case UNEVEN_LIGHTING:
                return "Evitá sombras sobre el DNI";
            case EDGE_OCCLUDED:
                return "\u26A0 Apoyá el DNI y no tapes los bordes";
            case CROP_INCOMPLETE:
                return "\u26A0 Dejá visible todo el borde del DNI";
            case HOLD_STILL:
                return "Mantené esta posición";
            case WAITING_FOR_FOCUS:
                return "Enfocá el DNI";
            case READY:
                return "No muevas el celular";
            case FOCUSING:
                return "Enfocando…";
            case CAPTURING:
                return "Tomando foto";
            case PROCESSING:
                return "Procesando imagen…";
            case RETRYING:
                return "Reintentando captura";
            case SUCCESS:
                return "\u2713 Listo";
            default:
                return "Buscando documento";
        }
    }

    private void scheduleTimeout() {
        if (timeoutMs <= 0L) {
            return;
        }
        timeoutRunnable = () -> finishWithError(
            "DETECTION_TIMEOUT",
            "No se detectó un documento válido dentro del tiempo configurado.",
            true
        );
        mainHandler.postDelayed(timeoutRunnable, timeoutMs);
    }

    private void cancelByUser() {
        if (completed) {
            return;
        }
        finishWithError("USER_CANCELLED", "Captura cancelada por el usuario.", true);
    }

    private void finishWithSuccess(JSONObject result) {
        if (completed || isFinishing()) {
            return;
        }
        showCaptureStage(
            AnalysisResult.State.SUCCESS,
            "\u2713 Listo",
            capturedMotion == null ? motionSnapshot() : capturedMotion
        );
        completed = true;
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_JSON, result.toString());
        if (beepEnabled && successBeep != null) {
            successBeep.play();
        }
        mainHandler.postDelayed(() -> {
            setResult(RESULT_OK, data);
            finish();
        }, SUCCESS_VISUAL_FEEDBACK_MS);
    }

    private void finishWithErrorOnMain(String code, String message, boolean recoverable) {
        mainHandler.post(() -> finishWithError(code, message, recoverable));
    }

    private void finishWithError(String code, String message, boolean recoverable) {
        if (completed || isFinishing()) {
            return;
        }
        completed = true;
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR_CODE, code);
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        data.putExtra(EXTRA_ERROR_RECOVERABLE, recoverable);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    private float sharpnessScore(double variance) {
        return (float) Math.max(0.0, Math.min(1.0,
            variance / (config.minSharpnessVariance * 4.0)
        ));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String isoTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
