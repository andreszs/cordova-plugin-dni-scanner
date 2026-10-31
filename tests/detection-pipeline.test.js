'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
function read(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

var plugin = read('plugin.xml');
var gradle = read('src/android/dni-scanner.gradle');
var androidEngine = read('src/android/java/OpenCvDocumentEngine.java');
var androidTracker = read('src/android/java/TemporalDocumentTracker.java');
var androidStill = read('src/android/java/StillImageProcessor.java');
var androidActivity = read('src/android/java/DniScannerActivity.java');
var androidPlugin = read('src/android/java/DniScannerPlugin.java');
var iosEngine = read('src/ios/DniScannerEngine.swift');
var iosTracker = read('src/ios/DniDocumentTracker.swift');
var iosController = read('src/ios/DniScannerViewController.swift');
var iosTypes = read('src/ios/DniScannerTypes.swift');
var notices = read('THIRD_PARTY_NOTICES.md');

assert(gradle.includes("org.opencv:opencv:4.12.0"));
assert(plugin.includes('OpenCvDocumentEngine.java'));
assert(plugin.includes('TemporalDocumentTracker.java'));
assert(!plugin.includes('DocumentDetector.java'));
assert(androidEngine.includes('Strategy.DOG'));
assert(androidEngine.includes('Strategy.CLAHE'));
assert(androidEngine.includes('MIN_EDGE_SUPPORT'));
assert(androidTracker.includes('calcOpticalFlowPyrLK'));
assert(androidTracker.includes('MAX_FLOW_ONLY_FRAMES'));
assert(androidTracker.includes('MAX_RECONCILE_CORNER_DISTANCE'));
assert(androidTracker.includes('isConsistentTrackUpdate'));
assert(androidTracker.includes('isOuterContourRecovery'));
assert(androidTracker.includes('nextSmoothed'));
assert(androidTracker.includes('synchronized AnalysisResult update'));
assert(androidTracker.includes('previousRaw == null || stableSinceMs <= 0L'));
assert(androidTracker.includes('synchronized void reset()'));
assert(androidEngine.includes('quadMaxCornerDistance'));
assert(androidEngine.includes('detection.quad.hasAcceptablePerspective('));
assert(!androidEngine.includes('checks.perspective && quality.perspectiveScore'));
assert(androidTracker.includes('MIN_OUTER_RECOVERY_AREA_GAIN'));
assert(androidStill.includes('correction=bounded_blend'));
assert(androidStill.includes('reason=divergent_still_detection'));
assert(androidStill.includes('cropWithoutWarp'));
assert(!androidStill.includes('transformer.transform'));
assert(androidStill.includes('fallback=global_detection'));
assert(androidActivity.includes('hasFreshCaptureGeometry()'));
assert(androidActivity.includes('beginCapture(true)'));
assert(androidPlugin.includes('autoCapture debe ser booleano'));

assert(plugin.includes('DniDocumentTracker.swift'));
assert(!plugin.includes('DniGeometricDocumentDetector.swift'));
assert(iosEngine.includes('VNDetectDocumentSegmentationRequest'));
assert(iosEngine.includes('VNDetectRectanglesRequest'));
assert(iosEngine.includes('source: "preview_guard"'));
assert(iosEngine.includes('currentWeight: 0.25'));
assert(iosEngine.includes('detection.quad.hasAcceptablePerspective('));
assert(iosEngine.includes('factory.croppedImage('));
assert(!iosEngine.includes('CIPerspectiveCorrection'));
assert(!iosEngine.includes('checks.perspective && quality.perspective'));
assert(iosTracker.includes('VNTrackRectangleRequest'));
assert(iosController.includes('hasFreshCaptureGeometry()'));
assert(iosController.includes('captureManually'));
assert(iosTypes.includes('strictBool("autoCapture", fallback: true)'));
assert(notices.includes('DocShot'));
assert(notices.includes('Open Android Doc Scanner'));

console.log('Detection and autocapture pipeline checks passed.');
