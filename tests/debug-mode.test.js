'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
function read(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

var androidActivity = read('src/android/java/DniScannerActivity.java');
var androidOverlay = read('src/android/java/ScannerOverlayView.java');
var iosTypes = read('src/ios/DniScannerTypes.swift');
var iosOverlay = read('src/ios/DniScannerOverlayView.swift');
var readme = read('README.md');

assert(androidActivity.includes('options.optBoolean("debugMode", false)'));
assert(androidActivity.includes('DniScannerTrace'));
assert(androidActivity.includes('capture:retry reason='));
assert(androidOverlay.includes('if (debugMode && result != null && result.detection != null'));
assert(androidOverlay.includes('float left = dp(8f)'));
assert(androidOverlay.includes('getHeight() - panelHeight'));
assert(androidOverlay.includes('"score/aspect: -"'));
assert(androidOverlay.includes('debugBackgroundPaint.setColor(0x5c000000)'));
assert(iosTypes.includes('strictBool("debugMode", fallback: false)'));
assert(iosOverlay.includes('if debugMode, let current = analysis'));
assert(iosOverlay.includes('let left: CGFloat = 8'));
assert(iosOverlay.includes('"score/aspect: -"'));
assert(iosOverlay.includes('withAlphaComponent(0.36)'));
assert(readme.includes('| `debugMode` | `boolean` | `false` |'));

console.log('Debug mode checks passed.');
