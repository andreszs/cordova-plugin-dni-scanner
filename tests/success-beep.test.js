'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var plugin = fs.readFileSync(path.join(root, 'plugin.xml'), 'utf8');
var androidPlugin = fs.readFileSync(
    path.join(root, 'src/android/java/DniScannerPlugin.java'), 'utf8'
);
var androidActivity = fs.readFileSync(
    path.join(root, 'src/android/java/DniScannerActivity.java'), 'utf8'
);
var androidBeep = fs.readFileSync(
    path.join(root, 'src/android/java/DniScannerSuccessBeep.java'), 'utf8'
);
var iosTypes = fs.readFileSync(path.join(root, 'src/ios/DniScannerTypes.swift'), 'utf8');
var iosController = fs.readFileSync(
    path.join(root, 'src/ios/DniScannerViewController.swift'), 'utf8'
);
var iosBeep = fs.readFileSync(
    path.join(root, 'src/ios/DniScannerSuccessBeep.swift'), 'utf8'
);

assert(plugin.includes('src/android/java/DniScannerSuccessBeep.java'));
assert(plugin.includes('src/ios/DniScannerSuccessBeep.swift'));
assert(androidPlugin.includes('isBooleanOption(options, "beepEnabled")'));
assert(androidActivity.includes('options.optBoolean("beepEnabled", true)'));
assert(androidActivity.includes('successBeep.play()'));
assert(androidBeep.includes('AudioAttributes.USAGE_ASSISTANCE_SONIFICATION'));
assert(androidBeep.includes('AudioManager.RINGER_MODE_NORMAL'));
assert(iosTypes.includes('strictBool("beepEnabled", fallback: true)'));
assert(iosController.includes('if options.beepEnabled'));
assert(iosController.includes('successBeep.play()'));
assert(iosBeep.includes('setCategory(.ambient'));
assert(iosBeep.includes('AVAudioPlayer(data: Self.data)'));

console.log('Success beep option checks passed.');
