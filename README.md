![npm version](https://img.shields.io/npm/v/cordova-plugin-dni-scanner) ![GitHub package.json version](https://img.shields.io/github/package-json/v/andreszs/cordova-plugin-dni-scanner?color=FF6D00&label=master&logo=github)

# cordova-plugin-dni-scanner

Android and iOS Cordova plugin for automatic capture of the front or back of an Argentine DNI. It detects the card geometrically, evaluates capture quality, corrects perspective, and returns a cropped color JPEG. Processing is fully local and works offline; the plugin does not use Google services, ML Kit, Firebase, OpenCV, ZXing, analytics, or remote APIs.

> Status: functional implementation under active calibration. Android has been exercised on Samsung J7, Samsung S7, and Xiaomi devices. The iOS camera flow, dynamic landscape rotation, guide-aligned analysis, and debug overlay have been exercised on a physical iPhone 11 with Cordova iOS 8.1. Recognition and quality thresholds still require validation with a representative DNI and device matrix before production use on either platform.

## Table of contents

- [Screenshots](#screenshots)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Demo app](#demo-app)
- [Usage](#usage)
  - [Options](#options)
  - [Optional checks](#optional-checks)
- [Result](#result)
  - [Metadata](#metadata)
- [Cancellation, retries, and errors](#cancellation-retries-and-errors)
- [Architecture and behavior](#architecture-and-behavior)
- [Dependencies and licenses](#dependencies-and-licenses)
- [Privacy](#privacy)
- [Known limitations](#known-limitations)
- [Tests](#tests)
- [Changelog](#changelog)

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/demo-quality-controls.png" width="320" alt="Demo quality controls"><br><sub>Configurable quality controls</sub></td>
    <td align="center"><img src="docs/screenshots/native-auto-capture.png" width="320" alt="Native automatic capture"><br><sub>Native automatic capture</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/demo-success-result.png" width="320" alt="Successful scan result"><br><sub>Corrected image and recognition result</sub></td>
    <td align="center"><img src="docs/screenshots/demo-result-metadata.png" width="320" alt="Result metadata"><br><sub>Result metadata</sub></td>
  </tr>
</table>

## Compatibility

The plugin supports `cordova-android` 11 through 15 from one codebase. It selects an exact CameraX version from the installed `cordova-android` major version, not from an overridable `compileSdk` value.

| cordova-android | CameraX | Reference toolchain |
| --- | --- | --- |
| 11.x | 1.1.0 | compileSdk 32 / AGP 7.2.1 / JDK 11 |
| 12.x | 1.2.3 | compileSdk 33 / AGP 7.4.2 / JDK 11 |
| 13.x | 1.4.2 | compileSdk 34 / AGP 8.3.0 / JDK 17 |
| 14.x | 1.5.2 | compileSdk 35 / AGP 8.7.3 / JDK 17 |
| 15.x | 1.6.0 | compileSdk 36 / AGP 8.10.1 / JDK 17 |

Unknown Android toolchain combinations fail at build time rather than silently selecting an unsafe dependency version.

The iOS implementation supports `cordova-ios` 8.x, iOS 13 or newer, and Swift 5 language mode. The reference build uses `cordova-ios` 8.1 and has been deployed to an iPhone 11. It uses only Apple system frameworks, including AVFoundation, Core Motion, Core Image, Vision, UIKit, and WebKit.

## Installation

Install from npm:

```bash
cordova plugin add cordova-plugin-dni-scanner
```

Or install directly from GitHub:

```bash
cordova plugin add https://github.com/andreszs/cordova-plugin-dni-scanner.git
```

The plugin requests only `CAMERA`. Images are written to the application's private cache, so no storage permission is required.

On iOS, the host app must provide a camera-purpose string in `config.xml`:

```xml
<platform name="ios">
    <edit-config file="*-Info.plist" mode="merge" target="NSCameraUsageDescription">
        <string>Usamos la cámara para escanear el DNI.</string>
    </edit-config>
</platform>
```

No Photo Library permission is required. Corrected JPEGs remain in the app's private cache and are exposed to the Cordova WebView through its own local scheme.

To test an unpublished checkout, install it by local path so Cordova uses the working tree instead of the npm release:

```bash
cordova plugin add /absolute/path/to/cordova-plugin-dni-scanner
```

## Demo app

The companion demo is maintained in [`com.andreszs.dniscanner.demo`](https://github.com/andreszs/cordova-plugin-demos/tree/main/com.andreszs.dniscanner.demo). It exposes every scan option and quality check, displays the corrected JPEG and complete result payload, and can enable the native diagnostic overlay.

For iOS device validation, install this plugin from a local checkout, add `ios@8.1.0`, select a Development team, and run the demo on a physical iPhone. The simulator build validates integration and compilation but cannot validate camera capture:

```bash
cordova platform add ios@8.1.0
cordova build ios --debug --emulator
cordova run ios --device --debug
```

## Usage

`DniScanner.scan(options)` returns a Promise:

```javascript
const result = await DniScanner.scan({
    side: "front",
    orientation: "portrait",
    flashMode: "off",
    showCloseButton: true,
    showFlashButton: false,
    debugMode: false,
    maxOutputLongEdge: 1280,
    guideMarginPercent: 3,
    instructionText: "Ubicá el frente del DNI en el recuadro",
    checks: {
        glare: true,
        shadows: true,
        edgeOcclusion: true,
        moire: false,
        requestedSide: true
    },
    timeoutMs: 45000
});

if (result.status === "cancelled") {
    return;
}

document.querySelector("img").src = result.imageUri;
```

Use `side: "back"` to capture the back.

### Options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `side` | `"front" \| "back"` | required | Requested DNI side. |
| `autoCapture` | `boolean` | `true` | Only `true` is currently supported. |
| `orientation` | `"portrait" \| "landscape"` | `"portrait"` | Scanner orientation for this session. Landscape follows both physical landscape positions on iOS. |
| `flashMode` | `"off" \| "auto" \| "on"` | `"off"` | Initial flash mode. `on` uses the torch; `off` is recommended to reduce reflections. |
| `showCloseButton` | `boolean` | `true` | Shows the native close button. Android Back still cancels when hidden. |
| `showFlashButton` | `boolean` | `true` | Shows the flash selector when the device has a flash unit. |
| `debugMode` | `boolean` | `true` | Shows live metrics over the camera. Enabled checks are green inside their threshold and red outside it; disabled checks are gray. It does not change validation. |
| `maxOutputLongEdge` | integer `640–2048` | `1280` | Maximum long edge of the corrected JPEG. The actual image may be smaller when the capture contains fewer effective pixels. |
| `guideMarginPercent` | number `0–20` | `3` | Permitted outward margin around the dotted guide, measured as a percentage of the guide's shorter edge. Candidates extending farther are ignored. |
| `instructionText` | `string \| null` | `""` | Optional text above the guide. Missing, empty, or `null` hides it. |
| `checks` | `object \| null` | standard checks enabled | Enables or disables individual acceptance checks. Missing properties stay enabled except `moire`, which defaults to `false`. |
| `timeoutMs` | integer `>= 0` | `0` | Maximum session duration. Zero disables the timeout. |

Only one scan may run at a time.

### Optional checks

`checks` accepts these boolean properties:

| Check | Purpose |
| --- | --- |
| `documentSize` | Rejects a card that is too close or too far. |
| `centering` | Requires the card near the guide. |
| `perspective` | Limits perspective distortion. |
| `stability` | Requires stable corners and scale across frames. On iOS it also rejects device rotation and linear acceleration reported by Core Motion. |
| `sharpness` | Rejects blur using Laplacian variance. |
| `brightness` | Rejects under/overexposure and severe clipping. |
| `glare` | Rejects large localized reflections. |
| `shadows` | Rejects strong uneven illumination. |
| `edgeOcclusion` | Rejects interrupted edges and likely fingers/hands. |
| `moire` | Rejects broad periodic interference consistent with photographing a display. Defaults to `false`; its diagnostic metrics are still returned. |
| `requestedSide` | Requires `detectedSide` to match `side`. |

Four-corner document detection is always required for cropping and perspective correction. Disabling a check reduces capture guarantees.

## Result

A successful scan resolves with metadata and a temporary JPEG:

```json
{
  "status": "success",
  "imageUri": "https://localhost/_dni_scanner_image_/dni-....jpg",
  "imagePath": "/data/user/0/.../cache/dni-scanner/dni-....jpg",
  "requestedSide": "front",
  "detectedSide": "front",
  "variant": 2,
  "confidence": {
    "document": 0.91,
    "side": 0.78,
    "variant": 0.72,
    "overall": 0.78
  },
  "width": 1280,
  "height": 807,
  "maxOutputLongEdge": 1280,
  "guideMarginPercent": 3.0,
  "mimeType": "image/jpeg",
  "bytes": 613245,
  "quality": {
    "sharpness": 0.84,
    "brightness": 0.56,
    "glare": 0.01,
    "perspective": 0.93,
    "darkClipping": 0.002,
    "highlightClipping": 0.03,
    "edgeGap": 0.02,
    "edgeObstruction": 0.0,
    "skinEdgeObstruction": 0.0,
    "illuminationSpread": 0.08,
    "shadowBoundary": 0.04,
    "moire": 0.05,
    "moireCoverage": 0.0
  },
  "motion": {
    "sensorAvailable": true,
    "gyroscopeAvailable": true,
    "accelerationAvailable": true,
    "sampling": true,
    "stable": true,
    "gyroscopeRadPerSecond": 0.018,
    "linearAcceleration": 0.074,
    "quietDurationMs": 824
  },
  "appliedChecks": {
    "documentSize": true,
    "centering": true,
    "perspective": true,
    "stability": true,
    "sharpness": true,
    "brightness": true,
    "glare": true,
    "shadows": true,
    "edgeOcclusion": true,
    "moire": false,
    "requestedSide": true
  },
  "recognitionSignals": ["layout_signature"],
  "perspectiveCorrected": true,
  "flashMode": "off",
  "orientation": "portrait",
  "debugMode": false,
  "flashFired": false,
  "capturedAt": "2026-10-07T15:30:00.000Z"
}
```

### Metadata

| Field | Description |
| --- | --- |
| `status` | `"success"` for an accepted capture. Cancellation uses the separate result documented below. |
| `imageUri` | WebView-safe local URL. Use it directly as an `<img>` source. |
| `imagePath` | Native absolute path to the same private-cache JPEG. |
| `requestedSide` / `detectedSide` | Requested side and recognized `"front"`, `"back"`, or `"unknown"`. With `requestedSide` checking enabled, a success always matches. |
| `variant` | Estimated generation (`1`, `2`, `3`, or `null` when confidence is insufficient). |
| `confidence` | `document`, `side`, `variant`, and conservative `overall` heuristic scores. |
| `width` / `height` / `mimeType` / `bytes` | Encoded image properties. |
| `maxOutputLongEdge` | Effective output-size limit requested for this scan. The actual long edge can be smaller. |
| `guideMarginPercent` | Effective outward detection margin around the dotted guide. |
| `quality` | Normalized final-image measurements listed below. |
| `motion` | iOS motion-sensor availability, sampling state, filtered rotation/acceleration, and quiet duration at capture. Measurements are `null` when unavailable. |
| `appliedChecks` | Effective checks used for the scan. These are configuration flags, not scores. |
| `recognitionSignals` | Supporting layout features; no code or personal text is decoded. |
| `perspectiveCorrected` | Always `true` in the current successful-result format. |
| `flashMode` / `orientation` / `debugMode` | Effective session configuration. |
| `flashFired` | `true`/`false`, or `null` in automatic mode where CameraX cannot report it reliably. |
| `capturedAt` | ISO 8601 UTC completion timestamp. |

All confidence and quality numbers are rounded to four decimals and are diagnostic heuristics, not identity, authenticity, or probability scores.

| Quality field | Meaning |
| --- | --- |
| `sharpness` | Normalized focus score; higher is better. |
| `brightness` | Mean normalized luminance; it must remain within an acceptable range. |
| `glare` | Localized reflection coverage; lower is better. |
| `perspective` | Geometric regularity; higher is better. |
| `darkClipping` / `highlightClipping` | Fractions of very dark or very bright pixels. |
| `edgeGap` | Longest perimeter run without sufficient edge contrast. |
| `edgeObstruction` | Longest warm-colored peripheral run. |
| `skinEdgeObstruction` | Longest skin-compatible region crossing an edge. |
| `illuminationSpread` / `shadowBoundary` | Uneven-lighting measurements; lower is better. |
| `moire` | Normalized strength and extent of periodic high-frequency interference; lower is better. It is always reported. |
| `moireCoverage` | Fraction of evaluated document regions with a strong periodic pattern; lower is better. It is always reported. |

Possible `recognitionSignals` are `layout_signature`, `machine_readable_rows`, `square_dense_region`, `wide_dense_region`, and `localized_lower_right_dense_region`. They are supporting geometric/layout observations, not decoded QR, PDF417, MRZ, or personal data.

Both image locations are temporary. Copy the file to application-managed persistent storage if it must be retained.

## Cancellation, retries, and errors

User cancellation resolves rather than rejects:

```json
{ "status": "cancelled", "reason": "user" }
```

Other failures reject with `{ code, message, recoverable }`:

| Code | Meaning |
| --- | --- |
| `INVALID_ARGUMENT` | Invalid or unsupported options. |
| `BUSY` | Another scan is active. |
| `CAMERA_PERMISSION_DENIED` | Camera permission was denied. |
| `CAMERA_UNAVAILABLE` | The rear camera is missing or could not be opened/configured. |
| `DETECTION_TIMEOUT` | No acceptable image was obtained before `timeoutMs`. |
| `CAPTURE_FAILED` | CameraX could not save the photograph. |
| `PROCESSING_FAILED` | Non-retryable local failure while decoding, transforming, encoding, writing, or returning the result. |
| `UNKNOWN` | Unstructured native error normalized by the JavaScript wrapper. |

Quality rejection, unknown-side recognition, or a side mismatch do not produce `PROCESSING_FAILED`. The image is discarded and capture resumes without a retry limit. The session ends on success, cancellation, a non-retryable failure, or `DETECTION_TIMEOUT`. With `timeoutMs: 0`, retryable rejection can continue indefinitely.

## Architecture and behavior

- Android uses CameraX `Preview`, `ImageAnalysis`, and `ImageCapture`. iOS uses `AVCaptureVideoPreviewLayer`, `AVCaptureVideoDataOutput`, and `AVCapturePhotoOutput`. Both analyze reduced preview frames of at most 512 px away from the UI thread.
- Android uses local Sobel/Hough processing to find and score a DNI-shaped quadrilateral. iOS uses Apple's on-device Vision rectangle detector. Neither platform includes a third-party computer-vision library, model download, or network service.
- On iOS, the analysis frame is center-cropped to the same aspect-fill viewport shown by the camera preview. The accepted region is derived from the visible dotted guide, so size, centering, and boundary checks refer to what the user actually sees.
- Framing, perspective, stability, focus, exposure, glare, shadows, edge obstruction, and display moiré are checked before capture and rechecked on the photograph.
- When `checks.stability` is enabled on iOS, Core Motion samples rotation and user acceleration at approximately 50 Hz off the main thread. Movement blocks analysis and the shutter; movement detected after the shutter discards that photo and resumes scanning. Missing, late, or stale sensors fall back to visual stability instead of blocking capture.
- Stable valid frames trigger native photo capture; the result is perspective-corrected and saved as JPEG 95 with a configurable maximum long edge (1280 px by default, capped at 2048 px). Colors are preserved without paper-scanner filters.
- A compact 4×3 edge/saturation layout classifier estimates side and generation. It does not decode QR or PDF417 and does not rotate or recognize an upside-down DNI.

The native guide changes from red to yellow to green. A subtle gray outline shows the detected quadrilateral, and one message reports the highest-priority problem. Built-in scanner messages are currently in Spanish. `debugMode` adds diagnostic categories for image quality, visual stability, and iOS device movement. Green means accepted, red means rejected, and gray means disabled, unavailable, or not yet sampling.

An iOS landscape session supports both `landscapeLeft` and `landscapeRight`. The preview, analyzed frames, captured photo, overlay, and controls update together when the device is rotated 180 degrees.

With the default `checks.requestedSide: true`, an opposite or unknown side is discarded. An opposite side displays a red warning for three seconds before analysis resumes.

## Dependencies and licenses

| Dependency | Version | License | Purpose |
| --- | --- | --- | --- |
| AndroidX CameraX (`core`, `camera2`, `lifecycle`, `view`) | compatibility table | Apache-2.0 | Preview, analysis, and capture. |
| AndroidX ExifInterface | 1.3.2 | Apache-2.0 | JPEG orientation metadata. |
| Guava `listenablefuture` | 1.0 | Apache-2.0 | Minimal local type required by CameraX; no Google service or network dependency. |
| AndroidX WebKit | supplied by Cordova | Apache-2.0 | Serves the private JPEG to the WebView; declared `compileOnly`. |
| Apple AVFoundation / Core Motion / Core Image / Vision / WebKit | iOS system | Apple SDK | Camera, motion stability, rectangle detection, perspective correction, and private JPEG delivery. |

The unused CameraX video stack is excluded. The plugin contains no ML models, native computer-vision libraries, or remote-service SDKs. Plugin source is licensed under Apache-2.0; see [LICENSE](LICENSE).

## Privacy

- Fully local and offline processing.
- No Internet permission, uploads, analytics, telemetry, tracking, or crash reporting.
- No Google Play Services or runtime model downloads.
- No shared-storage permission; output remains in the application's private cache.
- The iOS package includes a privacy manifest and uses only the camera-purpose string supplied by the host application. Core Motion does not add a permission prompt for this use.

## Known limitations

- Side and generation recognition are heuristic and based on a limited reference/calibration set; they are not document-authenticity checks.
- Glare, shadow, and hand/edge detection are heuristic and may require further device calibration.
- iOS has been exercised on an iPhone 11, but camera and recognition thresholds still need calibration across additional iPhone generations and representative DNI samples.
- QR and PDF417 are not decoded. Only automatic capture is supported; there is no manual shutter or Base64 result.
- The requested portrait/landscape family is fixed for each scan. Either physical landscape position is supported, but an upside-down document inside the guide is not recognized or automatically rotated.
- Output is temporary cache data and has no plugin-managed retention policy.

## Tests

`tests/host` contains deterministic Android tests for geometry, stability, image quality, perspective correction, edge obstruction, and layout classification. `tests/ios` exercises motion fallback, startup, rotation, acceleration, quiet recovery, capture-time movement, and stale samples without Core Motion hardware. The iOS sources are compiled in both simulator and device configurations through the Cordova demo. Reference DNI images are intentionally excluded from the repository and npm package. A physical-device scan remains required before a production release.

## Changelog

### 0.2.0

- Added a native iOS implementation for Cordova iOS 8 and iOS 13 or newer.
- Added AVFoundation preview, frame analysis, automatic photo capture, torch/flash control, cancellation, and timeout handling.
- Added offline Vision rectangle detection, Core Image perspective correction, quality metadata, and the existing coarse side/variant classifier.
- Added private-cache JPEG delivery through the Cordova iOS app scheme and an iOS privacy manifest.
- Added synchronized rotation for preview, analysis, capture, controls, and overlay in both landscape positions.
- Aligned iOS analysis with the visible aspect-fill preview and dotted guide.
- Matched the Android overlay layout and color-coded debug behavior on iOS.
- Added Core Motion stability gating before and during capture, visual-only sensor fallback, motion result metadata, and deterministic motion tests.

### 0.1.1

- Added the `maxOutputLongEdge` option, configurable from 640 to 2048 px.
- Changed the default corrected-image maximum long edge from 2048 to 1280 px.
- Added the effective output-size limit to the result metadata.
- Restricted geometric detection to the dotted guide and added the configurable `guideMarginPercent` outward margin.
- Added always-reported `quality.moire` and `quality.moireCoverage` diagnostics plus the configurable `checks.moire` rejection control.

### 0.1.0

- Initial public implementation of the offline Android DNI scanner.
- Added automatic geometric detection, capture-quality validation, perspective correction, side and variant estimation, and local JPEG output.
- Added support for `cordova-android` 11 through 15 with toolchain-specific CameraX versions.
