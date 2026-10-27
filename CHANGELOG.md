# Changelog

## Unreleased

## 0.2.5 - 2026-10-27

- Added backward-compatible `minEffectiveDocumentLongEdge` (disabled by default) so undersized document captures are discarded and scanning resumes.
- Added `minStillSharpnessVariance` for the corrected still photo, preserving the previous Android/iOS defaults while allowing stricter values such as 155–160; enabled low-sharpness failures now force recapture.
- Added center-of-document AF/AE before the shutter with a bounded 700 ms fallback on Android and iOS.
- Expanded the landscape guide from 58% to 80% of viewport height and moved instruction/status messages inside the guide.
- Added effective resolution/sharpness thresholds and raw `quality.sharpnessVariance` to successful result metadata while retaining native dimensions, effective document resolution, final dimensions, warnings, JPEG 95, and no-upscale output.
- Added cross-platform option, retry, focus/metering, layout, sizing, and metadata tests without changing Cordova Android 13–15 or Cordova iOS 8 support.

## 0.2.4 - 2026-10-27

- Added `preferredCaptureLongEdge` on Android and iOS, defaulting to 1600 px with a closest-higher-then-lower native capture fallback.
- Added `rawWidth`, `rawHeight`, and `effectiveDocumentLongEdge` result metadata and prevented corrected-image upscaling.
- Raised the Android minimum to `cordova-android` 13 so all supported Android builds use `ResolutionSelector` directly with a compatible compile SDK.

## 0.2.3 - 2026-10-26

- Relicensed the plugin source under the MIT License. Releases through 0.2.2 remain available under Apache-2.0.

## 0.2.2 - 2026-10-26

- Added the optional `beepEnabled` confirmation sound, enabled by default on Android and iOS and suppressed when the device is in silent mode.

## 0.2.1 - 2026-10-19

- Added `profile: "genericCard"` with `side: "any"` for credit/debit cards, driver's licenses, and other ID-1-sized cards. It preserves geometry and capture-quality checks while skipping DNI layout recognition and color-based hand heuristics.
- Reduced the full geometry hold to 500 ms and the inertial-sensor quiet window to 300 ms while retaining three-frame confirmation and capture-time motion rejection.
- Prevented intermittent Android preview zoom after startup or auto-rotation by waiting for a stable `PreviewView` layout and explicitly restoring CameraX to `1x` before analysis resumes.
- Added mandatory short multi-frame geometry confirmation on Android independently of the optional motion-stability check.
- Added outward still-image edge refinement and fixed safety padding on Android to prefer extra background over clipped DNI content.
- Made four-side crop integrity mandatory on Android and replaced uniform clamping with independent per-edge expansion.
- Rechecked corrected-image exposure and clipping when the brightness control is enabled.
- Replaced the former requested-side check with `sideValidation: "off" | "advisory" | "strict"`; the high-recall `off` policy is now the default.
- Made layout, side, and DNI-generation similarity non-blocking unless strict side validation is explicitly requested. Variant estimation never blocks capture.
- Added `documentCandidate`, `usableForOcr`, `contentScore`, `qualityWarnings`, `sideMatchesRequested`, and effective side-validation metadata.
- Added a position-independent printed-content diagnostic so shifted, worn, dirty, or irregularly printed DNI cards are not rejected for missing prototype landmarks.

## 0.2.0 - 2026-10-15

- Added `orientation: "auto"` so preview, analysis, capture guide, and controls follow device rotation while scanning.
- Added effective `orientation` and requested `orientationMode` result metadata.
- Added a native iOS implementation for Cordova iOS 8 and iOS 13 or newer.
- Added AVFoundation preview, frame analysis, automatic photo capture, torch/flash control, cancellation, and timeout handling.
- Added offline Vision rectangle detection, Core Image perspective correction, quality metadata, and the existing coarse side/variant classifier.
- Added a lightweight iOS geometric fallback constrained to the visible guide for DNI edges that Vision does not return reliably.
- Added private-cache JPEG delivery through the Cordova iOS app scheme and an iOS privacy manifest.
- Added synchronized rotation for preview, analysis, capture, controls, and overlay in both landscape positions.
- Aligned iOS analysis with the visible aspect-fill preview and dotted guide.
- Matched the Android overlay layout and color-coded debug behavior on iOS.
- Added Core Motion stability gating before and during capture, visual-only sensor fallback, motion result metadata, and deterministic motion tests.
- Added Android gyroscope and acceleration stability gating before and during capture, with accelerometer and visual-only fallbacks.
- Added consistent `motion` result metadata and debug metrics on Android and iOS.

## 0.1.1

- Added the `maxOutputLongEdge` option, configurable from 640 to 2048 px.
- Changed the default corrected-image maximum long edge from 2048 to 1280 px.
- Added the effective output-size limit to the result metadata.
- Restricted geometric detection to the dotted guide with a small fixed outward tolerance.

## 0.1.0 - 2026-10-13

- Initial public implementation of the offline Android DNI scanner.
- Added automatic geometric detection, capture-quality validation, perspective correction, side and variant estimation, and local JPEG output.
- Added support for `cordova-android` 11 through 15 with toolchain-specific CameraX versions.
