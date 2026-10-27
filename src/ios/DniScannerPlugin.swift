import AVFoundation
import Foundation
import WebKit

#if canImport(Cordova)
import Cordova
#endif

@objc(DniScanner)
final class DniScanner: CDVPlugin {
    private static let imagePathPrefix = "/_dni_scanner_image_/"
    private var pendingCallbackId: String?
    private var pendingOptions: DNIOptions?
    private weak var scannerController: DNIViewController?

    @objc(scan:)
    func scan(_ command: CDVInvokedUrlCommand) {
        guard pendingCallbackId == nil else {
            sendError(
                callbackId: command.callbackId,
                code: "BUSY",
                message: "Ya existe una captura de DNI activa.",
                recoverable: true
            )
            return
        }

        do {
            let dictionary = command.argument(at: 0) as? [String: Any] ?? [:]
            let options = try DNIOptions(dictionary: dictionary)
            pendingCallbackId = command.callbackId
            pendingOptions = options
            requestCameraAndPresent(options: options)
        } catch let error as DNIError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
        } catch {
            sendError(
                callbackId: command.callbackId,
                code: "INVALID_ARGUMENT",
                message: error.localizedDescription,
                recoverable: false
            )
        }
    }

    override func onReset() {
        scannerController?.abortForWebViewReset()
        scannerController = nil
        pendingCallbackId = nil
        pendingOptions = nil
    }

    private func requestCameraAndPresent(options: DNIOptions) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            presentScanner(options: options)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if granted {
                        self.presentScanner(options: options)
                    } else {
                        self.finishWithError(
                            code: "CAMERA_PERMISSION_DENIED",
                            message: "Permiso de cámara denegado.",
                            recoverable: true
                        )
                    }
                }
            }
        case .denied, .restricted:
            finishWithError(
                code: "CAMERA_PERMISSION_DENIED",
                message: "Permiso de cámara denegado.",
                recoverable: true
            )
        @unknown default:
            finishWithError(
                code: "CAMERA_PERMISSION_DENIED",
                message: "No se pudo determinar el permiso de cámara.",
                recoverable: true
            )
        }
    }

    private func presentScanner(options: DNIOptions) {
        guard pendingCallbackId != nil else { return }
        guard let host = viewController else {
            finishWithError(
                code: "CAMERA_UNAVAILABLE",
                message: "No se encontró el controlador de la aplicación.",
                recoverable: false
            )
            return
        }
        let scanner = DNIViewController(options: options)
        scanner.delegate = self
        scannerController = scanner
        host.present(scanner, animated: true)
    }

    private func resultDictionary(
        scan: DNIProcessedScan,
        options: DNIOptions,
        flashMode: String,
        flashFired: Bool?,
        motion: DNIMotionSnapshot,
        effectiveOrientation: String
    ) -> [String: Any] {
        let recognition = scan.recognition
        let documentConfidence = Double(scan.detection.score)
        let exposeSide = options.profile == .dni && options.sideValidation != .off
        let detectedSide = exposeSide ? recognition.side : "unknown"
        let sideConfidence = exposeSide ? recognition.sideConfidence : 0
        let variantConfidence = exposeSide ? recognition.variantConfidence : 0
        let detectedVariant: Any = exposeSide
            ? (recognition.variant.map { $0 as Any } ?? NSNull())
            : NSNull()
        let sideMatchesRequested: Any = detectedSide == "unknown"
            ? NSNull()
            : detectedSide == options.side
        let usable = usableForOcr(scan.quality)
        let signals = recognition.signals.filter {
            exposeSide || $0 == "printed_content"
        }
        let attributes = try? FileManager.default.attributesOfItem(atPath: scan.fileURL.path)
        let bytes = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        return [
            "status": "success",
            "imageUri": imageWebURL(fileName: scan.fileURL.lastPathComponent),
            "imagePath": scan.fileURL.path,
            "profile": options.profile.rawValue,
            "requestedSide": options.side,
            "sideValidation": options.sideValidation.rawValue,
            "detectedSide": detectedSide,
            "sideMatchesRequested": sideMatchesRequested,
            "variant": detectedVariant,
            "documentCandidate": true,
            "usableForOcr": usable,
            "contentScore": round(recognition.contentScore),
            "qualityWarnings": qualityWarnings(scan.quality, profile: options.profile),
            "confidence": [
                "document": round(documentConfidence),
                "content": round(recognition.contentScore),
                "side": round(sideConfidence),
                "variant": round(variantConfidence),
                "overall": round(documentConfidence)
            ],
            "width": scan.width,
            "height": scan.height,
            "rawWidth": scan.rawWidth,
            "rawHeight": scan.rawHeight,
            "effectiveDocumentLongEdge": scan.effectiveDocumentLongEdge,
            "preferredCaptureLongEdge": options.preferredCaptureLongEdge,
            "maxOutputLongEdge": options.maxOutputLongEdge,
            "mimeType": "image/jpeg",
            "bytes": bytes,
            "quality": scan.quality.dictionary,
            "motion": motion.dictionary,
            "appliedChecks": options.checks.dictionary,
            "recognitionSignals": signals,
            "perspectiveCorrected": true,
            "flashMode": flashMode,
            "orientation": options.orientation == "auto" ? effectiveOrientation : options.orientation,
            "orientationMode": options.orientation,
            "debugMode": options.debugMode,
            "flashFired": flashFired ?? NSNull(),
            "capturedAt": formatter.string(from: Date())
        ]
    }

    private func usableForOcr(_ quality: DNIQuality) -> Bool {
        return quality.sharpness >= DNIEngine.minimumOCRSharpness
            && quality.brightness >= DNIEngine.minimumOCRBrightness
            && quality.brightness <= DNIEngine.maximumOCRBrightness
            && quality.darkClipping <= DNIEngine.maximumOCRDarkClipping
            && quality.highlightClipping <= DNIEngine.maximumOCRHighlightClipping
            && quality.glare <= DNIEngine.maximumOCRGlare
    }

    private func qualityWarnings(_ quality: DNIQuality, profile: DNIScanProfile) -> [String] {
        var warnings: [String] = []
        if quality.sharpness < DNIEngine.minimumSharpness * 1.35 {
            warnings.append("low_sharpness")
        }
        if quality.brightness < DNIEngine.minimumBrightness + 18
            || quality.darkClipping > DNIEngine.maximumDarkClipping * 0.75 {
            warnings.append("low_light")
        }
        if quality.brightness > DNIEngine.maximumBrightness - 12
            || quality.highlightClipping > DNIEngine.maximumHighlightClipping * 0.75 {
            warnings.append("high_light")
        }
        if quality.glare > DNIEngine.maximumGlare(for: profile) * 0.65 {
            warnings.append("glare")
        }
        if quality.illuminationSpread
                > DNIEngine.maximumIlluminationSpread(for: profile) * 0.75
            && quality.shadowBoundary
                > DNIEngine.maximumShadowBoundary(for: profile) * 0.65 {
            warnings.append("uneven_lighting")
        }
        let edgeObstruction = profile == .genericCard
            ? quality.edgeGap
            : max(quality.edgeGap, max(quality.edgeObstruction, quality.skinEdgeObstruction))
        if edgeObstruction > DNIEngine.maximumEdgeObstruction * 0.75 {
            warnings.append("edge_obstruction")
        }
        return warnings
    }

    private func imageWebURL(fileName: String) -> String {
        let scheme = viewController?.appScheme ?? "app"
        let hostname = (commandDelegate.settings["hostname"] as? String) ?? "localhost"
        return "\(scheme)://\(hostname)\(Self.imagePathPrefix)\(fileName)"
    }

    private func round(_ value: Double) -> Double {
        return (value * 10_000).rounded() / 10_000
    }

    private func finishWithError(code: String, message: String, recoverable: Bool) {
        guard let callbackId = pendingCallbackId else { return }
        clearPending()
        sendError(callbackId: callbackId, code: code, message: message, recoverable: recoverable)
    }

    private func sendError(callbackId: String, code: String, message: String, recoverable: Bool) {
        sendErrorPayload(callbackId: callbackId, payload: [
            "code": code,
            "message": message,
            "recoverable": recoverable
        ])
    }

    private func sendErrorPayload(callbackId: String, payload: [String: Any]) {
        let result = CDVPluginResult(status: CDVCommandStatus.error, messageAs: payload)
        commandDelegate.send(result, callbackId: callbackId)
    }

    private func clearPending() {
        pendingCallbackId = nil
        pendingOptions = nil
        scannerController = nil
    }

    @objc(overrideSchemeTask:)
    func overrideSchemeTask(_ task: WKURLSchemeTask) -> Bool {
        let path = task.request.url?.path ?? ""
        guard path.hasPrefix(Self.imagePathPrefix) else { return false }
        let fileName = String(path.dropFirst(Self.imagePathPrefix.count))
        let expression = try? NSRegularExpression(pattern: "^dni-[0-9a-fA-F-]+\\.jpg$")
        let range = NSRange(fileName.startIndex..<fileName.endIndex, in: fileName)
        guard expression?.firstMatch(in: fileName, range: range) != nil else {
            failSchemeTask(task)
            return true
        }
        do {
            let cache = try scannerCacheDirectory().standardizedFileURL
            let file = cache.appendingPathComponent(fileName).standardizedFileURL
            guard file.deletingLastPathComponent() == cache else {
                failSchemeTask(task)
                return true
            }
            let data = try Data(contentsOf: file, options: .mappedIfSafe)
            guard let url = task.request.url,
                  let response = HTTPURLResponse(
                    url: url,
                    statusCode: 200,
                    httpVersion: "HTTP/1.1",
                    headerFields: [
                        "Content-Type": "image/jpeg",
                        "Content-Length": String(data.count),
                        "Cache-Control": "no-store"
                    ]
                  ) else {
                failSchemeTask(task)
                return true
            }
            task.didReceive(response)
            task.didReceive(data)
            task.didFinish()
        } catch {
            failSchemeTask(task)
        }
        return true
    }

    @objc(stopSchemeTask:)
    func stopSchemeTask(_ task: WKURLSchemeTask) {
        // Files are read synchronously and are small; there is no pending transfer to cancel.
    }

    private func failSchemeTask(_ task: WKURLSchemeTask) {
        let error = NSError(
            domain: "cordova-plugin-dni-scanner",
            code: 404,
            userInfo: [NSLocalizedDescriptionKey: "Imagen temporal no disponible."]
        )
        task.didFailWithError(error)
    }

    private func scannerCacheDirectory() throws -> URL {
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return cache.appendingPathComponent("dni-scanner", isDirectory: true)
    }
}

extension DniScanner: DNIViewControllerDelegate {
    func dniScanner(
        _ controller: DNIViewController,
        didFinish scan: DNIProcessedScan,
        flashFired: Bool?,
        motion: DNIMotionSnapshot
    ) {
        guard let callbackId = pendingCallbackId, let options = pendingOptions else { return }
        let payload = resultDictionary(
            scan: scan,
            options: options,
            flashMode: controller.effectiveFlashMode,
            flashFired: flashFired,
            motion: motion,
            effectiveOrientation: controller.effectiveCaptureOrientation
        )
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            let result = CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload)
            self.commandDelegate.send(result, callbackId: callbackId)
        }
    }

    func dniScannerDidCancel(_ controller: DNIViewController) {
        guard let callbackId = pendingCallbackId else { return }
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendError(
                callbackId: callbackId,
                code: "USER_CANCELLED",
                message: "Captura cancelada por el usuario.",
                recoverable: true
            )
        }
    }

    func dniScanner(_ controller: DNIViewController, didFail error: DNIError) {
        guard let callbackId = pendingCallbackId else { return }
        let payload = error.payload
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendErrorPayload(callbackId: callbackId, payload: payload)
        }
    }
}
