'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
function read(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

var androidActivity = read('src/android/java/DniScannerActivity.java');
var androidConfig = read('src/android/java/ScannerConfig.java');
var androidModels = read('src/android/java/ScanModels.java');
var androidOverlay = read('src/android/java/ScannerOverlayView.java');
var iosController = read('src/ios/DniScannerViewController.swift');
var iosTypes = read('src/ios/DniScannerTypes.swift');
var iosOverlay = read('src/ios/DniScannerOverlayView.swift');
var readme = read('README.md');

assert(androidOverlay.includes('drawMessage(canvas, guide, guideColor)'));
assert(androidOverlay.includes('return 0xff4ade80'));
assert(androidOverlay.includes('return 0xfffacc15'));
assert(androidOverlay.includes('return 0xffef4444'));
assert(androidOverlay.includes('case FOCUSING:\n            default:')
    || androidOverlay.includes('case FOCUSING:\r\n            default:'));
assert(androidModels.includes('RETRYING'));
assert(androidModels.includes('SUCCESS'));
assert(androidOverlay.includes('PROGRESS_TRANSITION_MS = 260L'));
assert(androidOverlay.includes('PROGRESS_INTERMEDIATE = 0xffa2d54b'));
assert(androidOverlay.includes('PORTRAIT_ELEMENT_GAP_DP = 18f'));
assert(androidOverlay.includes('drawDebugPanel(canvas)'));
assert(!androidOverlay.includes('drawDebugPanel(Canvas canvas, RectF guide)'));
assert(androidOverlay.includes('private int progressCompleted()'));
assert(androidOverlay.includes('state == AnalysisResult.State.PROCESSING'));
assert(androidActivity.includes('AnalysisResult.State.SUCCESS'));
assert(androidActivity.includes('SUCCESS_VISUAL_FEEDBACK_MS = 260L'));
assert(androidActivity.includes('AnalysisResult visibleResult = copyAnalysisWithState('));
assert(androidActivity.includes('overlayView.update(\n                visibleResult,')
    || androidActivity.includes('overlayView.update(\r\n                visibleResult,'));
assert(androidActivity.includes('AnalysisResult.State.RETRYING'));
assert(androidConfig.includes('instructionStateConfirmationMs = 500L'));
assert(androidConfig.includes('instructionLossGraceMs = 300L'));
assert(androidConfig.includes('retryMessageDurationMs = 1000L'));

assert(iosTypes.includes('case retrying'));
assert(iosTypes.includes('case success'));
assert(iosController.includes('visibleAnalysis.state = instructionState'));
assert(iosController.includes('showCaptureStage(.retrying, message: message)'));
assert(androidActivity.includes('"\\u2713 Listo"'));
assert(!androidActivity.includes('Captura aprobada'));
assert(iosController.includes('showCaptureStage(.success, message: "✓ Listo")'));
assert(!iosController.includes('Captura aprobada'));
assert(iosController.includes('confirmationDuration: 0.5'));
assert(iosController.includes('lossGraceDuration: 0.3'));
assert(iosController.includes('deadline: .now() + 1.0'));
assert(iosController.includes('deadline: .now() + 0.26'));
assert(iosOverlay.includes('progressTransitionDuration: CFTimeInterval = 0.26'));
assert(iosOverlay.includes('portraitElementGap: CGFloat = 18'));
assert(iosOverlay.includes('drawDebugPanel()'));
assert(!iosOverlay.includes('drawDebugPanel(guide:'));
assert(iosOverlay.includes('let intermediate = UIColor(red: 0.64, green: 0.84, blue: 0.29'));
assert(iosOverlay.includes('analysis?.state == .processing'));
assert(iosOverlay.includes('red: 0.29, green: 0.87, blue: 0.50'));
assert(iosOverlay.includes('red: 0.98, green: 0.80, blue: 0.08'));
assert(iosOverlay.includes('red: 0.94, green: 0.27, blue: 0.27'));
assert(iosOverlay.includes('case .focusing:'));
assert(readme.includes('green (`#4ADE80`)'));
assert(readme.includes('frozen for 1000 ms'));

console.log('Status color and feedback timing checks passed.');
