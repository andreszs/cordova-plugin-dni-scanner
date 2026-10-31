import Foundation

struct DNIMotionParameters {
    static let `default` = DNIMotionParameters(
        smoothingFactor: 0.72,
        maxGyroscopeRadPerSecond: 0.18,
        maxGyroscopeSpikeRadPerSecond: 0.45,
        maxLinearAcceleration: 0.34,
        maxAccelerationSpike: 0.90,
        stableDuration: 0.300,
        sensorStartupTimeout: 1.000,
        sensorStaleTimeout: 0.750
    )

    let smoothingFactor: Double
    let maxGyroscopeRadPerSecond: Double
    let maxGyroscopeSpikeRadPerSecond: Double
    let maxLinearAcceleration: Double
    let maxAccelerationSpike: Double
    let stableDuration: TimeInterval
    let sensorStartupTimeout: TimeInterval
    let sensorStaleTimeout: TimeInterval
}

struct DNIMotionCapabilities {
    let gyroscope: Bool
    let acceleration: Bool

    var sensorAvailable: Bool { gyroscope || acceleration }
}

struct DNIMotionSample {
    let timestamp: TimeInterval
    let gyroscopeRadPerSecond: Double?
    let linearAcceleration: Double?
}

struct DNIMotionSnapshot {
    let sensorAvailable: Bool
    let gyroscopeAvailable: Bool
    let accelerationAvailable: Bool
    let sampling: Bool
    let stable: Bool
    let gyroscopeRadPerSecond: Double?
    let linearAcceleration: Double?
    let quietDurationMs: Int

    var dictionary: [String: Any] {
        return [
            "sensorAvailable": sensorAvailable,
            "gyroscopeAvailable": gyroscopeAvailable,
            "accelerationAvailable": accelerationAvailable,
            "sampling": sampling,
            "stable": stable,
            "gyroscopeRadPerSecond": Self.roundedOrNull(gyroscopeRadPerSecond),
            "linearAcceleration": Self.roundedOrNull(linearAcceleration),
            "quietDurationMs": quietDurationMs
        ]
    }

    private static func roundedOrNull(_ value: Double?) -> Any {
        guard let value = value else { return NSNull() }
        return (value * 1_000).rounded() / 1_000
    }
}

struct DNIMotionCaptureOutcome {
    let accepted: Bool
    let movementDetected: Bool
    let snapshot: DNIMotionSnapshot
}

/// Pure motion filtering and stability state. CoreMotion is deliberately kept out
/// of this class so its behavior can be tested with deterministic timestamps.
final class DNIMotionStabilityMachine {
    private let parameters: DNIMotionParameters
    private var capabilities = DNIMotionCapabilities(gyroscope: false, acceleration: false)
    private var started = false
    private var startedAt: TimeInterval = 0
    private var lastGyroscopeAt: TimeInterval?
    private var lastAccelerationAt: TimeInterval?
    private var smoothedGyroscope: Double?
    private var smoothedAcceleration: Double?
    private var quietSince: TimeInterval?
    private var captureActive = false
    private var movementDetectedDuringCapture = false

    init(parameters: DNIMotionParameters = .default) {
        self.parameters = parameters
    }

    func start(capabilities: DNIMotionCapabilities, at timestamp: TimeInterval) {
        self.capabilities = capabilities
        started = true
        startedAt = timestamp
        lastGyroscopeAt = nil
        lastAccelerationAt = nil
        smoothedGyroscope = nil
        smoothedAcceleration = nil
        quietSince = nil
        captureActive = false
        movementDetectedDuringCapture = false
    }

    func stop() {
        started = false
        captureActive = false
        movementDetectedDuringCapture = false
    }

    func add(_ sample: DNIMotionSample) {
        guard started else { return }

        var rawSpike = false
        if capabilities.gyroscope, let value = sample.gyroscopeRadPerSecond, value.isFinite {
            let magnitude = max(0, value)
            smoothedGyroscope = smooth(previous: smoothedGyroscope, current: magnitude)
            lastGyroscopeAt = sample.timestamp
            rawSpike = rawSpike || magnitude > parameters.maxGyroscopeSpikeRadPerSecond
        }
        if capabilities.acceleration, let value = sample.linearAcceleration, value.isFinite {
            let magnitude = max(0, value)
            smoothedAcceleration = smooth(previous: smoothedAcceleration, current: magnitude)
            lastAccelerationAt = sample.timestamp
            rawSpike = rawSpike || magnitude > parameters.maxAccelerationSpike
        }

        guard hasFreshMeasurement(at: sample.timestamp) else { return }
        let moving = rawSpike || exceedsSmoothedThreshold(at: sample.timestamp)
        if moving {
            quietSince = nil
            if captureActive {
                movementDetectedDuringCapture = true
            }
        } else if quietSince == nil {
            quietSince = sample.timestamp
        }
    }

    func snapshot(at timestamp: TimeInterval) -> DNIMotionSnapshot {
        let sampling = started && hasFreshMeasurement(at: timestamp)
        let hasSensor = capabilities.sensorAvailable
        let waitingForFirstSample = started
            && hasSensor
            && lastGyroscopeAt == nil
            && lastAccelerationAt == nil
            && timestamp - startedAt < parameters.sensorStartupTimeout

        let quietDuration = sampling ? quietDurationMs(at: timestamp) : 0
        let stable: Bool
        if !started || !hasSensor {
            stable = true
        } else if waitingForFirstSample {
            stable = false
        } else if !sampling {
            // A missing or stale stream degrades to visual-only stability.
            stable = true
        } else {
            stable = !exceedsSmoothedThreshold(at: timestamp)
                && quietDuration >= Int(parameters.stableDuration * 1_000)
        }

        return DNIMotionSnapshot(
            sensorAvailable: hasSensor,
            gyroscopeAvailable: capabilities.gyroscope,
            accelerationAvailable: capabilities.acceleration,
            sampling: sampling,
            stable: stable,
            gyroscopeRadPerSecond: smoothedGyroscope,
            linearAcceleration: smoothedAcceleration,
            quietDurationMs: quietDuration
        )
    }

    @discardableResult
    func beginCapture(at timestamp: TimeInterval) -> Bool {
        captureActive = true
        movementDetectedDuringCapture = false
        let accepted = snapshot(at: timestamp).stable
        if !accepted {
            movementDetectedDuringCapture = true
        }
        return accepted
    }

    func captureMayProceed(at timestamp: TimeInterval) -> Bool {
        return captureActive
            && snapshot(at: timestamp).stable
    }

    func finishCapture(at timestamp: TimeInterval) -> DNIMotionCaptureOutcome {
        let current = snapshot(at: timestamp)
        let movement = movementDetectedDuringCapture || !current.stable
        captureActive = false
        movementDetectedDuringCapture = false
        return DNIMotionCaptureOutcome(
            // The shutter is gated while stable. After a photo exists, image
            // sharpness is the authoritative quality check; a later CoreMotion
            // spike must not discard an otherwise sharp capture.
            accepted: true,
            movementDetected: movement,
            snapshot: current
        )
    }

    func cancelCapture() {
        captureActive = false
        movementDetectedDuringCapture = false
    }

    private func smooth(previous: Double?, current: Double) -> Double {
        guard let previous = previous else { return current }
        return parameters.smoothingFactor * previous
            + (1 - parameters.smoothingFactor) * current
    }

    private func quietDurationMs(at timestamp: TimeInterval) -> Int {
        guard let quietSince = quietSince else { return 0 }
        return Int(max(0, timestamp - quietSince) * 1_000)
    }

    private func hasFreshMeasurement(at timestamp: TimeInterval) -> Bool {
        return isFresh(lastGyroscopeAt, at: timestamp)
            || isFresh(lastAccelerationAt, at: timestamp)
    }

    private func exceedsSmoothedThreshold(at timestamp: TimeInterval) -> Bool {
        let rotationMoving = isFresh(lastGyroscopeAt, at: timestamp)
            && (smoothedGyroscope ?? 0) > parameters.maxGyroscopeRadPerSecond
        let accelerationMoving = isFresh(lastAccelerationAt, at: timestamp)
            && (smoothedAcceleration ?? 0) > parameters.maxLinearAcceleration
        return rotationMoving || accelerationMoving
    }

    private func isFresh(_ measurementTime: TimeInterval?, at timestamp: TimeInterval) -> Bool {
        guard let measurementTime = measurementTime else { return false }
        return timestamp - measurementTime <= parameters.sensorStaleTimeout
    }
}
