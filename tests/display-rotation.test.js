'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var activity = fs.readFileSync(
    path.join(root, 'src/android/java/DniScannerActivity.java'),
    'utf8'
);
var readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
var changelog = fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8');

assert(activity.includes('DisplayManager.DisplayListener'));
assert(activity.includes('displayManager.registerDisplayListener(displayRotationListener, mainHandler)'));
assert(activity.includes('displayManager.unregisterDisplayListener(displayRotationListener)'));
assert(activity.includes('synchronizeCameraRotation("display", false)'));
assert(activity.includes('synchronizeCameraRotation("configuration", true)'));
assert(activity.includes('nextStableRotationPasses >= 1'));
assert(activity.includes('targetRotation != currentDisplayRotation()'));
assert(activity.includes('synchronizeCameraRotation("capture_retry", false)'));
assert(!activity.includes('!"auto".equals(scanOrientation) || completed || capturing'));
assert(readme.includes('`auto` follows all four display rotations'));
assert(changelog.includes('180-degree transitions'));

console.log('Android display rotation synchronization checks passed.');
