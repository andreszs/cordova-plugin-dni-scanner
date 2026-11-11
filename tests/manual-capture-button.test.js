'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
function read(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

var androidActivity = read('src/android/java/DniScannerActivity.java');
var iosController = read('src/ios/DniScannerViewController.swift');
var iosTypes = read('src/ios/DniScannerTypes.swift');

// Both platforms keep automatic capture enabled by default.
assert(androidActivity.includes('autoCapture = options.optBoolean("autoCapture", true);'));
assert(iosTypes.includes('strictBool("autoCapture", fallback: true)'));

// autoCapture=true must skip native manual-button creation on both platforms.
assert(/if \(!autoCapture\) \{\r?\n\s+captureButton = controlButton\("Capturar"\);/.test(androidActivity));
assert(/if !options\.autoCapture \{\r?\n\s+let capture = makeButton\(title: "Capturar"\)/.test(iosController));

// autoCapture=false keeps the existing manual capture handlers wired to the button.
assert(androidActivity.includes('captureButton.setOnClickListener(view -> beginCapture(true));'));
assert(iosController.includes('capture.addTarget(self, action: #selector(captureManually), for: .touchUpInside)'));
assert(iosController.includes('beginCapture(analysis, manual: true)'));

// No independent button-visibility option may be introduced.
assert(!androidActivity.includes('showCaptureButton'));
assert(!iosController.includes('showCaptureButton'));
assert(!iosTypes.includes('showCaptureButton'));

console.log('Manual capture button policy checks passed.');
