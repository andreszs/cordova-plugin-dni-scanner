import Foundation

private struct TestFailure: Error, CustomStringConvertible {
    let description: String
}

private func expect(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        throw TestFailure(description: message)
    }
}

private let bothSensors = DNIMotionCapabilities(gyroscope: true, acceleration: true)

private func addQuietSamples(
    to machine: DNIMotionStabilityMachine,
    from start: TimeInterval,
    through end: TimeInterval
) {
    var timestamp = start
    while timestamp <= end + 0.000_001 {
        machine.add(DNIMotionSample(
            timestamp: timestamp,
            gyroscopeRadPerSecond: 0.01,
            linearAcceleration: 0.05
        ))
        timestamp += 0.02
    }
}

private func stableMachine() -> DNIMotionStabilityMachine {
    let machine = DNIMotionStabilityMachine()
    machine.start(capabilities: bothSensors, at: 0)
    addQuietSamples(to: machine, from: 0, through: 0.72)
    return machine
}

private func syntheticDocumentFrame() -> (width: Int, height: Int, luma: [UInt8]) {
    let width = 320
    let height = 240
    var luma = [UInt8](repeating: 42, count: width * height)
    for y in 48..<193 {
        for x in 45..<275 {
            let border = x < 49 || x >= 271 || y < 52 || y >= 189
            luma[y * width + x] = border ? 235 : 188
        }
    }
    return (width, height, luma)
}

private let tests: [(String, () throws -> Void)] = [
    ("fallback without sensors", {
        let machine = DNIMotionStabilityMachine()
        machine.start(
            capabilities: DNIMotionCapabilities(gyroscope: false, acceleration: false),
            at: 0
        )
        let snapshot = machine.snapshot(at: 0)
        try expect(!snapshot.sensorAvailable, "sensorAvailable should be false")
        try expect(!snapshot.sampling, "sampling should be false")
        try expect(snapshot.stable, "visual-only fallback must be stable")
    }),
    ("initial sensor wait and startup fallback", {
        let machine = DNIMotionStabilityMachine()
        machine.start(capabilities: bothSensors, at: 0)
        try expect(!machine.snapshot(at: 0.50).stable, "startup wait must block")
        let fallback = machine.snapshot(at: 1.01)
        try expect(!fallback.sampling, "missing sensors should not report sampling")
        try expect(fallback.stable, "startup timeout must fall back to visual stability")
    }),
    ("rotation rejection", {
        let machine = stableMachine()
        try expect(machine.snapshot(at: 0.72).stable, "quiet device should be stable")
        machine.add(DNIMotionSample(
            timestamp: 0.74,
            gyroscopeRadPerSecond: 0.50,
            linearAcceleration: 0.05
        ))
        try expect(!machine.snapshot(at: 0.74).stable, "rotation spike must reject")
    }),
    ("acceleration rejection", {
        let machine = stableMachine()
        machine.add(DNIMotionSample(
            timestamp: 0.74,
            gyroscopeRadPerSecond: 0.01,
            linearAcceleration: 1.10
        ))
        try expect(!machine.snapshot(at: 0.74).stable, "acceleration spike must reject")
    }),
    ("recovery after 300 ms quiet", {
        let machine = stableMachine()
        machine.add(DNIMotionSample(
            timestamp: 0.74,
            gyroscopeRadPerSecond: 0.50,
            linearAcceleration: 1.10
        ))
        addQuietSamples(to: machine, from: 0.76, through: 1.80)
        let recovered = machine.snapshot(at: 1.80)
        try expect(recovered.stable, "device should recover after the quiet window")
        try expect(recovered.quietDurationMs >= 300, "quiet duration should reach 300 ms")
    }),
    ("movement during capture", {
        let machine = stableMachine()
        try expect(machine.beginCapture(at: 0.72), "stable capture should start")
        machine.add(DNIMotionSample(
            timestamp: 0.80,
            gyroscopeRadPerSecond: 0.60,
            linearAcceleration: 1.20
        ))
        addQuietSamples(to: machine, from: 0.82, through: 1.90)
        let outcome = machine.finishCapture(at: 1.90)
        try expect(outcome.movementDetected, "capture must retain movement latch")
        try expect(!outcome.accepted, "moved capture must be discarded")
    }),
    ("registered sensor becomes stale", {
        let machine = stableMachine()
        let stale = machine.snapshot(at: 1.48)
        try expect(!stale.sampling, "stale stream should stop sampling")
        try expect(stale.stable, "stale stream must fall back to visual stability")
    }),
    ("public motion metadata schema", {
        let machine = DNIMotionStabilityMachine()
        machine.start(
            capabilities: DNIMotionCapabilities(gyroscope: true, acceleration: false),
            at: 0
        )
        machine.add(DNIMotionSample(
            timestamp: 0,
            gyroscopeRadPerSecond: 0.0184,
            linearAcceleration: nil
        ))
        let dictionary = machine.snapshot(at: 0).dictionary
        let expected = Set([
            "sensorAvailable", "gyroscopeAvailable", "accelerationAvailable", "sampling",
            "stable", "gyroscopeRadPerSecond", "linearAcceleration", "quietDurationMs"
        ])
        try expect(Set(dictionary.keys) == expected, "motion schema keys changed")
        try expect(dictionary["gyroscopeRadPerSecond"] as? Double == 0.018, "gyro rounding changed")
        try expect(dictionary["linearAcceleration"] is NSNull, "missing acceleration must be null")
    }),
    ("geometric DNI fallback detects four borders", {
        let frame = syntheticDocumentFrame()
        let detection = DNIGeometricDocumentDetector().detect(
            width: frame.width,
            height: frame.height,
            luma: frame.luma,
            permittedRegion: CGRect(x: 0.05, y: 0.05, width: 0.90, height: 0.90),
            targetAreaFraction: 0.43
        )
        try expect(detection != nil, "Sobel/Hough fallback should detect the synthetic card")
        try expect(abs((detection?.observedAspect ?? 0) - 1.59) < 0.15, "card aspect should be preserved")
    }),
    ("geometric DNI fallback honors the visible guide", {
        let frame = syntheticDocumentFrame()
        let detection = DNIGeometricDocumentDetector().detect(
            width: frame.width,
            height: frame.height,
            luma: frame.luma,
            permittedRegion: CGRect(x: 0.25, y: 0.25, width: 0.50, height: 0.50),
            targetAreaFraction: 0.25
        )
        try expect(detection == nil, "a card outside the guide must not be accepted")
    }),
    ("automatic orientation is accepted", {
        let options = try DNIOptions(dictionary: [
            "side": "front",
            "orientation": "auto"
        ])
        try expect(options.orientation == "auto", "auto orientation should be preserved")
    }),
    ("side validation defaults to off", {
        let options = try DNIOptions(dictionary: ["side": "front"])
        try expect(options.sideValidation == .off, "permissive side validation must be the default")
        try expect(
            options.preferredCaptureLongEdge == 1600,
            "preferred capture long edge must default to 1600"
        )
    }),
    ("preferred capture long edge is validated", {
        let options = try DNIOptions(dictionary: [
            "side": "front",
            "preferredCaptureLongEdge": 2048
        ])
        try expect(options.preferredCaptureLongEdge == 2048, "valid capture size should be preserved")
        do {
            _ = try DNIOptions(dictionary: [
                "side": "front",
                "preferredCaptureLongEdge": 1279
            ])
            throw TestFailure(description: "capture size below the range should fail")
        } catch is TestFailure {
            throw TestFailure(description: "invalid capture size was accepted")
        } catch {
            // Expected DNIError.invalidArgument.
        }
    }),
    ("capture resolution prefers higher before lower", {
        let candidates = [
            DNICaptureDimensions(width: 1024, height: 768),
            DNICaptureDimensions(width: 1920, height: 1440),
            DNICaptureDimensions(width: 1280, height: 960)
        ]
        let higher = DNICaptureResolutionSelector.select(
            candidates,
            preferredLongEdge: 1600
        )
        try expect(higher?.longEdge == 1920, "closest higher resolution should win")
        let lower = DNICaptureResolutionSelector.select(
            candidates,
            preferredLongEdge: 2048
        )
        try expect(lower?.longEdge == 1920, "closest lower resolution should be the fallback")
    }),
    ("corrected output never upscales the effective document", {
        try expect(
            DNIOutputSizing.longEdge(
                effectiveDocumentLongEdge: 520,
                maximumLongEdge: 1280
            ) == 520,
            "a low-resolution document must not be enlarged"
        )
        try expect(
            DNIOutputSizing.longEdge(
                effectiveDocumentLongEdge: 1600,
                maximumLongEdge: 1280
            ) == 1280,
            "the configured output maximum must still cap larger captures"
        )
    }),
    ("side validation policies are accepted", {
        let advisory = try DNIOptions(dictionary: [
            "side": "front",
            "sideValidation": "advisory"
        ])
        let strict = try DNIOptions(dictionary: [
            "side": "back",
            "sideValidation": "strict"
        ])
        try expect(advisory.sideValidation == .advisory, "advisory policy should be preserved")
        try expect(strict.sideValidation == .strict, "strict policy should be preserved")
    }),
    ("invalid side validation is rejected", {
        do {
            _ = try DNIOptions(dictionary: [
                "side": "front",
                "sideValidation": "relaxed"
            ])
            throw TestFailure(description: "unknown side policy should fail")
        } catch is TestFailure {
            throw TestFailure(description: "unknown side policy was accepted")
        } catch {
            // Expected DNIError.invalidArgument.
        }
    }),
    ("capture orientation remains locked until the photo callback", {
        var state = DNICaptureOrientationState()
        state.beginCapture(at: .landscapeLeft)
        state.beginCapture(at: .portrait)
        try expect(state.locked == .landscapeLeft, "rotation must not replace the capture lock")
        state.receivePhoto()
        try expect(state.locked == nil, "photo callback should release autorotation")
        try expect(state.captured == .landscapeLeft, "accepted result must retain effective orientation")
        state.discardPhoto()
        try expect(state.captured == nil, "discarded photo must clear captured orientation")
    })
]

var failures = 0
for (name, test) in tests {
    do {
        try test()
        print("PASS: \(name)")
    } catch {
        failures += 1
        print("FAIL: \(name): \(error)")
    }
}

if failures > 0 {
    exit(1)
}

print("PASS: \(tests.count) deterministic iOS tests")
