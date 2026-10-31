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
        // Kept as an accepted input for API compatibility, but geometric
        // perspective integrity is now a mandatory capture requirement.
        perspective = true
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
    static let minimumPreferredCaptureLongEdge = 1280
    static let defaultPreferredCaptureLongEdge = 1600
    static let maximumPreferredCaptureLongEdge = 2048
    static let minimumOutputLongEdge = 640
    static let maximumOutputLongEdge = 2048
    static let minimumEffectiveDocumentLongEdge = 0
    static let defaultMinimumEffectiveDocumentLongEdge = 0
    static let maximumEffectiveDocumentLongEdge = 4096
    static let minimumStillSharpnessVariance = 1.0
    static let defaultMinimumStillSharpnessVariance = 70.0
    static let maximumStillSharpnessVariance = 10_000.0

    let profile: DNIScanProfile
    let side: String
    let sideValidation: DNISideValidationMode
    let flashMode: String
    let orientation: String
    let showCloseButton: Bool
    let showFlashButton: Bool
    let debugMode: Bool
    let beepEnabled: Bool
    let autoCapture: Bool
    let preferredCaptureLongEdge: Int
    let maxOutputLongEdge: Int
    let minEffectiveDocumentLongEdge: Int
    let minStillSharpnessVariance: Double
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

        let preferredNumber: NSNumber
        if let rawPreferred = dictionary["preferredCaptureLongEdge"] {
            guard !(rawPreferred is Bool), let number = rawPreferred as? NSNumber else {
                throw DNIError.invalidArgument(
                    "La opción preferredCaptureLongEdge debe ser numérica."
                )
            }
            preferredNumber = number
        } else {
            preferredNumber = NSNumber(value: Self.defaultPreferredCaptureLongEdge)
        }
        let preferred = preferredNumber.intValue
        guard preferredNumber.doubleValue == Double(preferred),
              preferred >= Self.minimumPreferredCaptureLongEdge,
              preferred <= Self.maximumPreferredCaptureLongEdge else {
            throw DNIError.invalidArgument(
                "La opción preferredCaptureLongEdge debe ser un entero entre 1280 y 2048."
            )
        }

        let outputNumber = dictionary["maxOutputLongEdge"] as? NSNumber ?? 1280
        let output = outputNumber.intValue
        guard outputNumber.doubleValue == Double(output),
              output >= Self.minimumOutputLongEdge,
              output <= Self.maximumOutputLongEdge else {
            throw DNIError.invalidArgument("La opción maxOutputLongEdge debe ser un entero entre 640 y 2048.")
        }

        let effectiveNumber: NSNumber
        if let rawMinimum = dictionary["minEffectiveDocumentLongEdge"] {
            guard !(rawMinimum is Bool), let number = rawMinimum as? NSNumber else {
                throw DNIError.invalidArgument(
                    "La opción minEffectiveDocumentLongEdge debe ser numérica."
                )
            }
            effectiveNumber = number
        } else {
            effectiveNumber = NSNumber(value: Self.defaultMinimumEffectiveDocumentLongEdge)
        }
        let minimumEffective = effectiveNumber.intValue
        guard effectiveNumber.doubleValue == Double(minimumEffective),
              minimumEffective >= Self.minimumEffectiveDocumentLongEdge,
              minimumEffective <= Self.maximumEffectiveDocumentLongEdge else {
            throw DNIError.invalidArgument(
                "La opción minEffectiveDocumentLongEdge debe ser un entero entre 0 y 4096."
            )
        }

        let sharpnessNumber: NSNumber
        if let rawMinimum = dictionary["minStillSharpnessVariance"] {
            guard !(rawMinimum is Bool), let number = rawMinimum as? NSNumber else {
                throw DNIError.invalidArgument(
                    "La opción minStillSharpnessVariance debe ser numérica."
                )
            }
            sharpnessNumber = number
        } else {
            sharpnessNumber = NSNumber(value: Self.defaultMinimumStillSharpnessVariance)
        }
        let minimumStillSharpness = sharpnessNumber.doubleValue
        guard minimumStillSharpness.isFinite,
              minimumStillSharpness >= Self.minimumStillSharpnessVariance,
              minimumStillSharpness <= Self.maximumStillSharpnessVariance else {
            throw DNIError.invalidArgument(
                "La opción minStillSharpnessVariance debe estar entre 1 y 10000."
            )
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
        self.debugMode = try strictBool("debugMode", fallback: false)
        self.beepEnabled = try strictBool("beepEnabled", fallback: true)
        self.autoCapture = try strictBool("autoCapture", fallback: true)
        self.preferredCaptureLongEdge = preferred
        self.maxOutputLongEdge = output
        self.minEffectiveDocumentLongEdge = minimumEffective
        self.minStillSharpnessVariance = minimumStillSharpness
        self.instructionText = instruction
        self.timeoutMs = timeout
        self.checks = try DNIChecks(dictionary: checksDictionary)
    }
}

struct DNICaptureDimensions: Equatable {
    let width: Int32
    let height: Int32

    var longEdge: Int { max(Int(width), Int(height)) }
}

enum DNICaptureResolutionSelector {
    static func select(
        _ candidates: [DNICaptureDimensions],
        preferredLongEdge: Int
    ) -> DNICaptureDimensions? {
        let valid = candidates.filter { $0.width > 0 && $0.height > 0 }
        let higher = valid
            .filter { $0.longEdge >= preferredLongEdge }
            .min { first, second in
                if first.longEdge != second.longEdge {
                    return first.longEdge < second.longEdge
                }
                return area(first) < area(second)
            }
        if let higher = higher {
            return higher
        }
        return valid.max { first, second in
            if first.longEdge != second.longEdge {
                return first.longEdge < second.longEdge
            }
            return area(first) < area(second)
        }
    }

    private static func area(_ dimensions: DNICaptureDimensions) -> Int64 {
        return Int64(dimensions.width) * Int64(dimensions.height)
    }
}

enum DNIOutputSizing {
    static func longEdge(effectiveDocumentLongEdge: Int, maximumLongEdge: Int) -> Int {
        return max(1, min(effectiveDocumentLongEdge, maximumLongEdge))
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

    var maximumPerspectiveDeviationDegrees: CGFloat {
        guard let deviations = cornerAngleDeviationsDegrees else { return 90 }
        return deviations.reduce(0) { max($0, abs($1)) }
    }

    var maximumInPlaneRotationDegrees: CGFloat {
        return max(
            abs(Self.lineRotationDegrees(from: topLeft, to: topRight)),
            abs(Self.lineRotationDegrees(from: bottomLeft, to: bottomRight))
        )
    }

    func hasAcceptablePerspective(
        freeLimit: CGFloat,
        symmetricLimit: CGFloat,
        symmetryTolerance: CGFloat
    ) -> Bool {
        guard let deviations = cornerAngleDeviationsDegrees else { return false }
        let maximum = deviations.reduce(0) { max($0, abs($1)) }
        if maximum <= freeLimit { return true }
        if maximum > symmetricLimit { return false }

        func maximum(_ values: CGFloat...) -> CGFloat {
            values.max() ?? .greatestFiniteMagnitude
        }
        let horizontalAxisError = maximum(
            abs(deviations[0] - deviations[1]),
            abs(deviations[2] - deviations[3]),
            abs(deviations[0] + deviations[3]),
            abs(deviations[1] + deviations[2])
        )
        let verticalAxisError = maximum(
            abs(deviations[1] - deviations[2]),
            abs(deviations[3] - deviations[0]),
            abs(deviations[1] + deviations[0]),
            abs(deviations[2] + deviations[3])
        )
        return min(horizontalAxisError, verticalAxisError) <= symmetryTolerance
    }

    private var cornerAngleDeviationsDegrees: [CGFloat]? {
        var deviations: [CGFloat] = []
        let corners = points
        for index in corners.indices {
            let previous = corners[(index + 3) % corners.count]
            let current = corners[index]
            let next = corners[(index + 1) % corners.count]
            let first = CGPoint(x: previous.x - current.x, y: previous.y - current.y)
            let second = CGPoint(x: next.x - current.x, y: next.y - current.y)
            let denominator = hypot(first.x, first.y) * hypot(second.x, second.y)
            guard denominator >= 0.0001 else { return nil }
            let cosine = max(-1, min(1,
                (first.x * second.x + first.y * second.y) / denominator
            ))
            let angle = acos(cosine) * 180 / .pi
            deviations.append(angle - 90)
        }
        return deviations
    }

    private static func lineRotationDegrees(from start: CGPoint, to end: CGPoint) -> CGFloat {
        var angle = atan2(end.y - start.y, end.x - start.x) * 180 / .pi
        while angle > 90 { angle -= 180 }
        while angle < -90 { angle += 180 }
        return angle
    }

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
            "sharpnessVariance": Self.round(sharpness),
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
    case focusing
    case capturing
    case processing
    case retrying
    case success
}

/// Stabilizes only the visible instruction. Analysis and automatic capture keep
/// consuming the current, unfiltered state.
final class DNIInstructionStateStabilizer {
    private let confirmationDuration: TimeInterval
    private let lossGraceDuration: TimeInterval
    private var visibleState: DNIAnalysisState = .searching
    private var pendingState: DNIAnalysisState?
    private var pendingSince: TimeInterval = 0

    init(confirmationDuration: TimeInterval, lossGraceDuration: TimeInterval? = nil) {
        self.confirmationDuration = max(0, confirmationDuration)
        self.lossGraceDuration = max(0, lossGraceDuration ?? confirmationDuration)
    }

    func update(candidate: DNIAnalysisState, at timestamp: TimeInterval) -> DNIAnalysisState {
        if candidate == visibleState {
            pendingState = nil
            pendingSince = 0
            return visibleState
        }
        if candidate != pendingState {
            pendingState = candidate
            pendingSince = timestamp
            return visibleState
        }
        let requiredDuration = candidate == .searching
            ? lossGraceDuration
            : confirmationDuration
        if timestamp - pendingSince >= requiredDuration {
            visibleState = candidate
            pendingState = nil
            pendingSince = 0
        }
        return visibleState
    }

    func force(_ state: DNIAnalysisState) {
        visibleState = state
        pendingState = nil
        pendingSince = 0
    }
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
    let rawWidth: Int
    let rawHeight: Int
    let effectiveDocumentLongEdge: Int
    let width: Int
    let height: Int
    let detection: DNIDetection
    let quality: DNIQuality
    let recognition: DNIRecognition
}
