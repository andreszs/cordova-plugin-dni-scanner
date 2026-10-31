'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
function read(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

var androidConfig = read('src/android/java/ScannerConfig.java');
var androidPlugin = read('src/android/java/DniScannerPlugin.java');
var androidActivity = read('src/android/java/DniScannerActivity.java');
var androidStill = read('src/android/java/StillImageProcessor.java');
var androidOverlay = read('src/android/java/ScannerOverlayView.java');
var androidCapture = read('src/android/java/CaptureResolutionConfigurator.java');
var androidTransformer = read('src/android/java/PerspectiveTransformer.java');
var iosTypes = read('src/ios/DniScannerTypes.swift');
var iosController = read('src/ios/DniScannerViewController.swift');
var iosEngine = read('src/ios/DniScannerEngine.swift');
var iosOverlay = read('src/ios/DniScannerOverlayView.swift');
var iosPlugin = read('src/ios/DniScannerPlugin.swift');
var readme = read('README.md');
var changelog = read('CHANGELOG.md');

assert(androidConfig.includes('DEFAULT_MIN_EFFECTIVE_DOCUMENT_LONG_EDGE = 0'));
assert(androidConfig.includes('DEFAULT_MIN_STILL_SHARPNESS_VARIANCE = 115.0'));
assert(androidConfig.includes('GUIDE_MARGIN_PERCENT = 6f'));
assert(androidPlugin.includes('options.has("minEffectiveDocumentLongEdge")'));
assert(androidPlugin.includes('options.has("minStillSharpnessVariance")'));
assert(androidStill.includes('effectiveDocumentLongEdge < config.minEffectiveDocumentLongEdge'));
assert(androidStill.includes('outputQuality.sharpness < config.minStillSharpnessVariance'));
assert(androidStill.includes('outputQuality.clippedFraction > 0.48f'));
assert(androidStill.includes('OpenCvDocumentEngine'));
assert(androidActivity.includes('startFocusAndMetering(action)'));
assert(androidActivity.includes('config.focusMeteringTimeoutMs'));
assert(!androidActivity.includes('motionMonitor.hasMovedSince(captureMotionSequence)'));
assert(androidActivity.includes('instructionStateStabilizer.update'));
assert(androidActivity.includes('AnalysisResult.State.FOCUSING'));
assert(androidActivity.includes('AnalysisResult.State.PROCESSING'));
assert(androidActivity.includes('"Tomando foto"'));
assert(!androidActivity.includes('Tomando foto, no muevas el celular'));
assert(!androidActivity.includes('previewView.setVisibility(View.INVISIBLE)'));
assert(androidActivity.includes('qualityJson.put("sharpnessVariance"'));
assert(androidActivity.includes('json.put("minEffectiveDocumentLongEdge"'));
assert(androidActivity.includes('json.put("minStillSharpnessVariance"'));
assert(androidOverlay.includes('? 0.80f : 0.46f'));

assert(iosTypes.includes('defaultMinimumEffectiveDocumentLongEdge = 0'));
assert(iosTypes.includes('defaultMinimumStillSharpnessVariance = 70.0'));
assert(iosEngine.includes('effectiveDocumentLongEdge >= options.minEffectiveDocumentLongEdge'));
assert(iosEngine.includes('quality.sharpness < options.minStillSharpnessVariance'));
assert(iosEngine.includes('VNDetectDocumentSegmentationRequest'));
assert(iosEngine.includes('maximumBrightness = 245.0'));
assert(androidConfig.includes('MAX_RECTANGLE_DEVIATION_DEGREES = 1.5f'));
assert(androidConfig.includes('MAX_SYMMETRIC_PERSPECTIVE_DEVIATION_DEGREES = 8f'));
assert(androidConfig.includes('MAX_TRAPEZOID_SYMMETRY_ERROR_DEGREES = 2.5f'));
assert(androidConfig.includes('MAX_IN_PLANE_ROTATION_DEGREES = 2f'));
assert(iosTypes.includes('maximumPerspectiveDeviationDegrees'));
assert(iosTypes.includes('hasAcceptablePerspective'));
assert(iosEngine.includes('maximumRectangleDeviationDegrees: CGFloat = 1.5'));
assert(iosEngine.includes('maximumSymmetricPerspectiveDeviationDegrees: CGFloat = 8'));
assert(iosEngine.includes('maximumInPlaneRotationDegrees: CGFloat = 2'));
assert(iosEngine.includes('0.25 * angleScore'));
assert(!fs.existsSync(path.join(root, 'src/ios/DniGeometricDocumentDetector.swift')));
assert(iosEngine.includes('guideMarginPercent: CGFloat = 6'));
assert(iosController.includes('focusPointOfInterest = devicePoint'));
assert(iosController.includes('exposurePointOfInterest = devicePoint'));
assert(iosController.includes('focusMeteringTimeout: TimeInterval = 0.7'));
assert(!iosController.includes('if !motionOutcome.accepted'));
assert(iosController.includes('instructionStateStabilizer.update'));
assert(iosTypes.includes('case focusing'));
assert(iosTypes.includes('case processing'));
assert(iosController.includes('message: "Tomando foto"'));
assert(!iosController.includes('Tomando foto, no muevas el celular'));
assert(!iosController.includes('previewLayer.isHidden = true'));
assert(iosPlugin.includes('"minEffectiveDocumentLongEdge": options.minEffectiveDocumentLongEdge'));
assert(iosPlugin.includes('"minStillSharpnessVariance": round(options.minStillSharpnessVariance)'));
assert(iosOverlay.includes('usesLandscapeLayout ? 0.80 : 0.46'));

assert(androidCapture.includes('FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER'));
assert(!androidTransformer.includes('Math.max(640, targetWidth)'));
assert(androidConfig.includes('final int jpegQuality = 95'));
assert(iosEngine.includes('jpegData(compressionQuality: 0.95)'));
assert(readme.includes('minEffectiveDocumentLongEdge: 1000'));
assert(readme.includes('minStillSharpnessVariance: 158'));
assert(changelog.includes('AF/AE'));

console.log('Still-image quality option checks passed.');
