package com.crediser.plugins.dniscanner;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

final class FrameAnalyzer implements ImageAnalysis.Analyzer {
    private static final String TAG = "DniScanner";
    interface Listener {
        void onAnalysis(AnalysisResult result, int frameWidth, int frameHeight);
        void onAnalysisFailure(Throwable error);
    }

    private final ScannerConfig config;
    private final TemporalDocumentTracker tracker;
    private final Listener listener;
    private long lastAnalysisMs;
    private volatile boolean enabled = true;

    FrameAnalyzer(ScannerConfig config, ScanChecks checks, DetectionRegion detectionRegion,
                  Listener listener) {
        this.config = config;
        this.listener = listener;
        this.tracker = new TemporalDocumentTracker(config, checks, detectionRegion);
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        long now = SystemClock.elapsedRealtime();
        if (!enabled || now - lastAnalysisMs < config.analysisIntervalMs) {
            image.close();
            return;
        }
        lastAnalysisMs = now;
        try {
            LumaFrame frame = LumaExtractor.fromImageProxy(image, config.analysisMaxDimension);
            AnalysisResult result = tracker.update(frame, now);
            listener.onAnalysis(result, frame.width, frame.height);
        } catch (Throwable error) {
            tracker.reset();
            Log.e(TAG, "Frame analysis failed", error);
            listener.onAnalysisFailure(error);
        } finally {
            image.close();
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            tracker.reset();
        }
    }
}
