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
var androidConfig = read('src/android/java/ScannerConfig.java');
var androidPlugin = read('src/android/java/DniScannerPlugin.java');
var androidCapture = read('src/android/java/CaptureResolutionConfigurator.java');
var androidActivity = read('src/android/java/DniScannerActivity.java');
var androidTransformer = read('src/android/java/PerspectiveTransformer.java');
var iosTypes = read('src/ios/DniScannerTypes.swift');
var iosController = read('src/ios/DniScannerViewController.swift');
var iosEngine = read('src/ios/DniScannerEngine.swift');
var iosPlugin = read('src/ios/DniScannerPlugin.swift');
var readme = read('README.md');

assert(plugin.includes('cordova-android" version=">=13.0.0 &lt;16.0.0"'));
assert(!gradle.includes("11: '"));
assert(!gradle.includes("12: '"));
assert(gradle.includes("13: '1.4.2'"));
assert(androidConfig.includes('DEFAULT_PREFERRED_CAPTURE_LONG_EDGE = 1600'));
assert(androidPlugin.includes('options.has("preferredCaptureLongEdge")'));
assert(androidCapture.includes('new ResolutionSelector.Builder()'));
assert(androidCapture.includes('FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER'));
assert(androidActivity.includes('json.put("rawWidth", result.rawWidth)'));
assert(androidActivity.includes('json.put("effectiveDocumentLongEdge"'));
assert(!androidTransformer.includes('Math.max(640, targetWidth)'));

assert(iosTypes.includes('defaultPreferredCaptureLongEdge = 1600'));
assert(iosTypes.includes('DNICaptureResolutionSelector'));
assert(iosController.includes('supportedMaxPhotoDimensions'));
assert(iosController.includes('settings.maxPhotoDimensions = dimensions'));
assert(iosEngine.includes('max(options.preferredCaptureLongEdge, options.maxOutputLongEdge)'));
assert(iosEngine.includes('DNIOutputSizing.longEdge'));
assert(iosPlugin.includes('"rawWidth": scan.rawWidth'));
assert(iosPlugin.includes('"effectiveDocumentLongEdge"'));

assert(readme.includes('`preferredCaptureLongEdge` | integer `1280–2048` | `1600`'));
assert(readme.includes('`maxOutputLongEdge` independently caps the corrected JPEG'));

console.log('Capture resolution option checks passed.');
