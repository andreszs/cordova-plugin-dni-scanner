import CoreGraphics
import Foundation

enum DNIError: Error {
    case invalidArgument(String)
    case cameraUnavailable(String, Bool)
    case captureFailed(String, Bool)
    case processingFailed(String, Bool)
    case retry(String, DNIAnalysisState)

    var payload: [String: Any] {
        switch self {
        case .invalidArgument(let message):
            return ["code": "INVALID_ARGUMENT", "message": message, "recoverable": false]
        case .cameraUnavailable(let message, let recoverable):
            return ["code": "CAMERA_UNAVAILABLE", "message": message, "recoverable": recoverable]
        case .captureFailed(let message, let recoverable):
            return ["code": "CAPTURE_FAILED", "message": message, "recoverable": recoverable]
        case .processingFailed(let message, let recoverable):
            return ["code": "PROCESSING_FAILED", "message": message, "recoverable": recoverable]
        case .retry(let message, _):
            return ["code": "PROCESSING_FAILED", "message": message, "recoverable": true]
        }
    }
}

struct DNIChecks {
    let documentSize: Bool
    let centering: Bool
    let perspective: Bool
    let stability: Bool
    let sharpness: Bool
    let brightness: Bool
    let glare: Bool
    let shadows: Bool
    let edgeOcclusion: Bool

    static let defaults = DNIChecks(
        documentSize: true,
        centering: true,
        perspective: true,
        stability: true,
        sharpness: true,
        brightness: true,
        glare: true,
        shadows: true,
        edgeOcclusion: true
    )

    init(dictionary: [String: Any]?) throws {
        let source = dictionary ?? [:]
        let known = Set([
            "documentSize", "centering", "perspective", "stability", "sharpness",
            "brightness", "glare", "shadows", "edgeOcclusion"
        ])
        for (name, value) in source {
            guard known.contains(name) else {
                throw DNIError.invalidArgument("Control desconocido en checks: \(name).")
            }
            guard value is Bool else {
                throw DNIError.invalidArgument("El control checks.\(name) debe ser booleano.")
            }
        }

        func bool(_ name: String, _ fallback: Bool) -> Bool {
            return source[name] as? Bool ?? fallback
        }
        documentSize = bool("documentSize", true)
        centering = bool("centering", true)
        perspective = bool("perspective", true)
        stability = bool("stability", true)
        sharpness = bool("sharpness", true)
        brightness = bool("brightness", true)
        glare = bool("glare", true)
        shadows = bool("shadows", true)
        edgeOcclusion = bool("edgeOcclusion", true)
    }

    private init(
        documentSize: Bool,
        centering: Bool,
        perspective: Bool,
        stability: Bool,
        sharpness: Bool,
        brightness: Bool,
        glare: Bool,
        shadows: Bool,
        edgeOcclusion: Bool
    ) {
        self.documentSize = documentSize
        self.centering = centering
        self.perspective = perspective
        self.stability = stability
        self.sharpness = sharpness
        self.brightness = brightness
        self.glare = glare
        self.shadows = shadows
        self.edgeOcclusion = edgeOcclusion
    }

    var dictionary: [String: Any] {
        return [
            "documentSize": documentSize,
            "centering": centering,
            "perspective": perspective,
            "stability": stability,
            "sharpness": sharpness,
            "brightness": brightness,
            "glare": glare,
            "shadows": shadows,
            "edgeOcclusion": edgeOcclusion
        ]
    }
}

enum DNISideValidationMode: String {
    case off
    case advisory
    case strict
}

enum DNIScanProfile: String {
    case dni
    case genericCard
}

struct DNIOptions {
    static let minimumOutputLongEdge = 640
    static let maximumOutputLongEdge = 2048

    let profile: DNIScanProfile
    let side: String
    let sideValidation: DNISideValidationMode
    let flashMode: String
    let orientation: String
    let showCloseButton: Bool
    let showFlashButton: Bool
    let debugMode: Bool
    let maxOutputLongEdge: Int
    let guideMarginPercent: CGFloat
    let instructionText: String
    let timeoutMs: Int
    let checks: DNIChecks

    init(dictionary: [String: Any]) throws {
        let rawProfile = dictionary["profile"] as? String ?? "dni"
        guard let profile = DNIScanProfile(rawValue: rawProfile) else {
            throw DNIError.invalidArgument("La opción profile debe ser 'dni' o 'genericCard'.")
        }
        guard let side = dictionary["side"] as? String else {
            throw DNIError.invalidArgument("La opción side es obligatoria.")
        }
        if profile == .dni && side != "front" && side != "back" {
            throw DNIError.invalidArgument("Para profile='dni', side debe ser 'front' o 'back'.")
        }
        if profile == .genericCard && side != "any" {
            throw DNIError.invalidArgument("Para profile='genericCard', side debe ser 'any'.")
        }
        let rawSideValidation = dictionary["sideValidation"] as? String ?? "off"
        guard let sideValidation = DNISideValidationMode(rawValue: rawSideValidation) else {
            throw DNIError.invalidArgument(
                "La opción sideValidation debe ser 'off', 'advisory' o 'strict'."
            )
        }
        if profile == .genericCard && sideValidation != .off {
            throw DNIError.invalidArgument("Para profile='genericCard', sideValidation debe ser 'off'.")
        }
        if let autoCapture = dictionary["autoCapture"] {
            guard let enabled = autoCapture as? Bool, enabled else {
                throw DNIError.invalidArgument("La versión actual sólo admite autoCapture=true.")
            }
        }

        let flashMode = dictionary["flashMode"] as? String ?? "off"
        guard ["off", "auto", "on"].contains(flashMode) else {
            throw DNIError.invalidArgument("La opción flashMode debe ser 'off', 'auto' u 'on'.")
        }
        let orientation = dictionary["orientation"] as? String ?? "portrait"
        guard ["portrait", "landscape", "auto"].contains(orientation) else {
            throw DNIError.invalidArgument(
                "La opción orientation debe ser 'portrait', 'landscape' o 'auto'."
            )
        }

        func strictBool(_ name: String, fallback: Bool) throws -> Bool {
            guard let value = dictionary[name] else { return fallback }
            guard let boolean = value as? Bool else {
                throw DNIError.invalidArgument("La opción \(name) debe ser booleana.")
            }
            return boolean
        }

        let outputNumber = dictionary["maxOutputLongEdge"] as? NSNumber ?? 1280
        let output = outputNumber.intValue
        guard outputNumber.doubleValue == Double(output),
              output >= Self.minimumOutputLongEdge,
              output <= Self.maximumOutputLongEdge else {
            throw DNIError.invalidArgument("La opción maxOutputLongEdge debe ser un entero entre 640 y 2048.")
        }

        let marginNumber = dictionary["guideMarginPercent"] as? NSNumber ?? 3
        let margin = CGFloat(marginNumber.doubleValue)
        guard margin.isFinite, margin >= 0, margin <= 20 else {
            throw DNIError.invalidArgument("La opción guideMarginPercent debe estar entre 0 y 20.")
        }

        let instruction: String
        if let raw = dictionary["instructionText"], !(raw is NSNull) {
            guard let text = raw as? String else {
                throw DNIError.invalidArgument("La opción instructionText debe ser texto, null o estar ausente.")
            }
            instruction = text
        } else {
            instruction = ""
        }

        let timeoutNumber = dictionary["timeoutMs"] as? NSNumber ?? 0
        let timeout = timeoutNumber.intValue
        guard timeoutNumber.doubleValue == Double(timeout), timeout >= 0 else {
            throw DNIError.invalidArgument("La opción timeoutMs debe ser un entero no negativo.")
        }

        let checksDictionary: [String: Any]?
        if let rawChecks = dictionary["checks"], !(rawChecks is NSNull) {
            guard let checks = rawChecks as? [String: Any] else {
                throw DNIError.invalidArgument("La opción checks debe ser un objeto.")
            }
            checksDictionary = checks
        } else {
            checksDictionary = nil
        }

        self.profile = profile
        self.side = side
        self.sideValidation = sideValidation
        self.flashMode = flashMode
        self.orientation = orientation
        self.showCloseButton = try strictBool("showCloseButton", fallback: true)
        self.showFlashButton = try strictBool("showFlashButton", fallback: true)
        self.debugMode = try strictBool("debugMode", fallback: true)
        self.maxOutputLongEdge = output
        self.guideMarginPercent = margin
        self.instructionText = instruction
        self.timeoutMs = timeout
        self.checks = try DNIChecks(dictionary: checksDictionary)
    }
}

enum DNIEffectiveOrientation: String {
    case portrait
    case landscapeLeft
    case landscapeRight

    var isLandscape: Bool {
        return self == .landscapeLeft || self == .landscapeRight
    }
}

/// Keeps the orientation used at the start of automatic capture immutable until
/// AVCapturePhotoOutput responds. The accepted orientation remains available for
/// result metadata even after autorotation is re-enabled.
struct DNICaptureOrientationState {
    private(set) var locked: DNIEffectiveOrientation?
    private(set) var captured: DNIEffectiveOrientation?

    mutating func beginCapture(at orientation: DNIEffectiveOrientation) {
        guard locked == nil else { return }
        locked = orientation
        captured = orientation
    }

    mutating func receivePhoto() {
        locked = nil
    }

    mutating func discardPhoto() {
        locked = nil
        captured = nil
    }
}

struct DNIQuad {
    let topLeft: CGPoint
    let topRight: CGPoint
    let bottomRight: CGPoint
    let bottomLeft: CGPoint

    var points: [CGPoint] { [topLeft, topRight, bottomRight, bottomLeft] }

    var area: CGFloat {
        let values = points
        var sum: CGFloat = 0
        for index in values.indices {
            let first = values[index]
            let second = values[(index + 1) % values.count]
            sum += first.x * second.y - second.x * first.y
        }
        return abs(sum) * 0.5
    }

    var center: CGPoint {
        return CGPoint(
            x: (topLeft.x + topRight.x + bottomRight.x + bottomLeft.x) * 0.25,
            y: (topLeft.y + topRight.y + bottomRight.y + bottomLeft.y) * 0.25
        )
    }

    var topWidth: CGFloat { topLeft.distance(to: topRight) }
    var bottomWidth: CGFloat { bottomLeft.distance(to: bottomRight) }
    var leftHeight: CGFloat { topLeft.distance(to: bottomLeft) }
    var rightHeight: CGFloat { topRight.distance(to: bottomRight) }

    func scaled(x: CGFloat, y: CGFloat) -> DNIQuad {
        func scale(_ point: CGPoint) -> CGPoint { CGPoint(x: point.x * x, y: point.y * y) }
        return DNIQuad(
            topLeft: scale(topLeft),
            topRight: scale(topRight),
            bottomRight: scale(bottomRight),
            bottomLeft: scale(bottomLeft)
        )
    }
}

extension CGPoint {
    func distance(to other: CGPoint) -> CGFloat {
        return hypot(x - other.x, y - other.y)
    }
}

struct DNIDetection {
    let quad: DNIQuad
    let score: CGFloat
    let areaFraction: CGFloat
    let centerOffset: CGFloat
    let observedAspect: CGFloat
    let source: String

    init(
        quad: DNIQuad,
        score: CGFloat,
        areaFraction: CGFloat,
        centerOffset: CGFloat,
        observedAspect: CGFloat,
        source: String = "vision"
    ) {
        self.quad = quad
        self.score = score
        self.areaFraction = areaFraction
        self.centerOffset = centerOffset
        self.observedAspect = observedAspect
        self.source = source
    }
}

struct DNIQuality {
    let sharpness: Double
    let brightness: Double
    let darkClipping: Double
    let highlightClipping: Double
    let glare: Double
    let perspective: Double
    let edgeGap: Double
    let edgeObstruction: Double
    let skinEdgeObstruction: Double
    let illuminationSpread: Double
    let shadowBoundary: Double

    var dictionary: [String: Any] {
        return [
            "sharpness": Self.round(normalizedSharpness),
            "brightness": Self.round(brightness / 255.0),
            "glare": Self.round(glare),
            "perspective": Self.round(perspective),
            "darkClipping": Self.round(darkClipping),
            "highlightClipping": Self.round(highlightClipping),
            "edgeGap": Self.round(edgeGap),
            "edgeObstruction": Self.round(edgeObstruction),
            "skinEdgeObstruction": Self.round(skinEdgeObstruction),
            "illuminationSpread": Self.round(illuminationSpread),
            "shadowBoundary": Self.round(shadowBoundary)
        ]
    }

    var normalizedSharpness: Double {
        return max(0, min(1, (sharpness - 45.0) / 420.0))
    }

    static func round(_ value: Double) -> Double {
        return (value * 10_000).rounded() / 10_000
    }
}

struct DNIRecognition {
    let side: String
    let variant: Int?
    let sideConfidence: Double
    let variantConfidence: Double
    let signals: [String]
    let contentScore: Double

    static let unknown = DNIRecognition(
        side: "unknown",
        variant: nil,
        sideConfidence: 0,
        variantConfidence: 0,
        signals: [],
        contentScore: 0
    )
}

enum DNIAnalysisState: String {
    case searching
    case moveCloser
    case moveAway
    case centerDocument
    case adjustPerspective
    case improveLighting
    case reduceLighting
    case avoidGlare
    case unevenLighting
    case edgeOccluded
    case holdStill
    case waitingForFocus
    case ready
    case capturing
}

struct DNIAnalysis {
    var state: DNIAnalysisState
    let detection: DNIDetection?
    let quality: DNIQuality?
    let imageWidth: Int
    let imageHeight: Int
    var stableFrameCount: Int = 0
    var stableDurationMs: Int = 0
    var motion: DNIMotionSnapshot? = nil
}

struct DNIProcessedScan {
    let fileURL: URL
    let width: Int
    let height: Int
    let detection: DNIDetection
    let quality: DNIQuality
    let recognition: DNIRecognition
}
