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
assert(androidPlugin.includes('options.has("minEffectiveDocumentLongEdge")'));
assert(androidPlugin.includes('options.has("minStillSharpnessVariance")'));
assert(androidStill.includes('effectiveDocumentLongEdge < config.minEffectiveDocumentLongEdge'));
assert(androidStill.includes('outputQuality.sharpness < config.minStillSharpnessVariance'));
assert(androidActivity.includes('startFocusAndMetering(action)'));
assert(androidActivity.includes('config.focusMeteringTimeoutMs'));
assert(androidActivity.includes('qualityJson.put("sharpnessVariance"'));
assert(androidActivity.includes('json.put("minEffectiveDocumentLongEdge"'));
assert(androidActivity.includes('json.put("minStillSharpnessVariance"'));
assert(androidOverlay.includes('? 0.80f : 0.46f'));

assert(iosTypes.includes('defaultMinimumEffectiveDocumentLongEdge = 0'));
assert(iosTypes.includes('defaultMinimumStillSharpnessVariance = 70.0'));
assert(iosEngine.includes('effectiveDocumentLongEdge >= options.minEffectiveDocumentLongEdge'));
assert(iosEngine.includes('quality.sharpness < options.minStillSharpnessVariance'));
assert(iosController.includes('focusPointOfInterest = devicePoint'));
assert(iosController.includes('exposurePointOfInterest = devicePoint'));
assert(iosController.includes('focusMeteringTimeout: TimeInterval = 0.7'));
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
