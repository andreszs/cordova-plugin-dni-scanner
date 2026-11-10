'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
function read(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

var androidConfig = read('src/android/java/ScannerConfig.java');
var androidModels = read('src/android/java/ScanModels.java');
var androidEngine = read('src/android/java/OpenCvDocumentEngine.java');
var androidTracker = read('src/android/java/TemporalDocumentTracker.java');
var androidActivity = read('src/android/java/DniScannerActivity.java');
var androidCrop = read('src/android/java/PerspectiveTransformer.java');
var androidStill = read('src/android/java/StillImageProcessor.java');
var iosTypes = read('src/ios/DniScannerTypes.swift');
var iosEngine = read('src/ios/DniScannerEngine.swift');
var iosTracker = read('src/ios/DniDocumentTracker.swift');
var iosController = read('src/ios/DniScannerViewController.swift');
var iosPlugin = read('src/ios/DniScannerPlugin.swift');

// Tracking hysteresis cannot authorize a visibly rolled capture.
assert(androidConfig.includes('MAX_IN_PLANE_ROTATION_DEGREES = 4f'));
assert(androidConfig.includes('EXIT_IN_PLANE_ROTATION_DEGREES = 5.5f'));
assert(androidTracker.includes('geometryAccepted'));
assert(androidTracker.includes('state == AnalysisResult.State.READY && !geometryValid'));
assert(iosEngine.includes('maximumInPlaneRotationDegrees: CGFloat = 4'));
assert(iosEngine.includes('exitInPlaneRotationDegrees: CGFloat = 5.5'));
assert(iosTracker.includes('useExitGeometryLimits: geometryAccepted'));
assert(iosTracker.includes('state == .ready && !geometryIsAccepted'));
assert(iosTracker.includes('useExit: false'));

// perspective=false is honored while convex four-corner detection remains mandatory.
assert(androidActivity.includes('source.optBoolean("perspective", true)'));
assert(androidEngine.includes('if (checks.perspective)'));
assert(iosTypes.includes('perspective = bool("perspective", true)'));
assert(iosEngine.includes('if checks.perspective'));

// Visual stability is short; inertial sensors never veto a started or manual capture.
assert(androidConfig.includes('final int stableFrames = 2'));
assert(androidConfig.includes('final long stableDurationMs = 220L'));
assert(!androidActivity.includes('if (!manual && checks.stability && !motion.stable)'));
assert(!androidActivity.includes('if (checks.stability && !motion.stable)'));
assert(androidActivity.includes('beginCapture(true)'));
assert(iosEngine.includes('requiredStableFrames = 2'));
assert(!iosController.includes('motionMonitor.captureMayProceed()'));
assert(iosController.includes('beginCapture(analysis, manual: true)'));

// Detection and crop policies tolerate preview misses while preserving a hard border margin.
assert(androidModels.includes('final class DocumentShapeGate'));
assert(androidModels.includes('MAXIMUM_CORNER_DEVIATION_DEGREES = 18f'));
assert(androidEngine.includes('DocumentShapeGate.accepts(quad, width, height)'));
assert(androidTracker.includes('forceGlobalDetection'));
assert(androidTracker.includes('nowMs - adjustPerspectiveSinceMs >= 650L'));
assert(androidTracker.includes('MAX_FLOW_ONLY_FRAMES = 5'));
assert(iosTracker.includes('missedFrames <= 5'));
assert(androidEngine.includes('MIN_EDGE_SUPPORT = 0.41f'));
assert(androidEngine.includes('MIN_SIDE_EDGE_SUPPORT = 0.22f'));
assert(androidEngine.includes('solidity < 0.55f'));
assert(iosEngine.includes('guard score >= 0.40'));
assert(iosTypes.includes('enum DNIDocumentShapeGate'));
assert(iosEngine.includes('DNIDocumentShapeGate.accepts'));
assert(iosTracker.includes('forceGlobalDetection'));
assert(iosTracker.includes('timestamp - adjustPerspectiveSince >= 0.65'));
assert(androidConfig.includes('MINIMUM_CROP_PADDING_PERCENT = 1.75f'));
assert(androidCrop.includes('minimumCropIntegrityClearanceFraction'));
assert(androidCrop.includes('reducedPadding'));
assert(!androidStill.includes('fallback=preview_prior reason=no_still_detection'));
assert(!androidStill.includes('fallback=preview_prior reason=divergent_still_detection'));
assert(androidStill.includes('physical_border_unconfirmed'));
assert(androidCrop.includes('hasCompletePhysicalBorder'));
assert(iosEngine.includes('minimumAdaptivePaddingFraction: CGFloat = 0.0175'));
assert(!iosEngine.includes('source: "preview_fallback"'));
assert(!iosEngine.includes('source: "preview_guard"'));
assert(iosEngine.includes('hasCompletePhysicalBorder'));
assert(iosPlugin.includes('warnings.append("reduced_padding")'));

console.log('OCR-friendly capture policy checks passed.');
