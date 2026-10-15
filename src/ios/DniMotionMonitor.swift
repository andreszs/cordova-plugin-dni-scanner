import CoreMotion
import Foundation

final class DNIMotionMonitor {
    private static let gravityMetersPerSecondSquared = 9.80665
    private static let updateInterval: TimeInterval = 1.0 / 50.0
    private static let fallbackGravitySmoothingFactor = 0.90

    private let manager: CMMotionManager
    private let capabilities: DNIMotionCapabilities
    private let stateQueue = DispatchQueue(label: "com.crediser.dniscanner.ios.motion-state")
    private let machine: DNIMotionStabilityMachine
    private var operationQueue: OperationQueue?
    private var monitoring = false
    private var fallbackGravity: CMAcceleration?

    init(
        manager: CMMotionManager = CMMotionManager(),
        parameters: DNIMotionParameters = .default
    ) {
        self.manager = manager
        self.machine = DNIMotionStabilityMachine(parameters: parameters)
        let deviceMotionAvailable = manager.isDeviceMotionAvailable
        self.capabilities = DNIMotionCapabilities(
            gyroscope: deviceMotionAvailable || manager.isGyroAvailable,
            acceleration: deviceMotionAvailable || manager.isAccelerometerAvailable
        )
    }

    deinit {
        manager.stopDeviceMotionUpdates()
        manager.stopGyroUpdates()
        manager.stopAccelerometerUpdates()
        operationQueue?.cancelAllOperations()
    }

    func start() {
        stop()

        let queue = OperationQueue()
        queue.name = "com.crediser.dniscanner.ios.motion-samples"
        queue.maxConcurrentOperationCount = 1
        queue.qualityOfService = .userInitiated
        queue.underlyingQueue = stateQueue
        operationQueue = queue
        fallbackGravity = nil

        if manager.isDeviceMotionAvailable {
            stateQueue.sync {
                monitoring = true
                machine.start(
                    capabilities: capabilities,
                    at: ProcessInfo.processInfo.systemUptime
                )
            }
            manager.deviceMotionUpdateInterval = Self.updateInterval
            manager.startDeviceMotionUpdates(to: queue) { [weak self] motion, _ in
                guard let self = self, let motion = motion else { return }
                let rotation = motion.rotationRate
                let acceleration = motion.userAcceleration
                self.machine.add(DNIMotionSample(
                    timestamp: motion.timestamp,
                    gyroscopeRadPerSecond: Self.magnitude(rotation.x, rotation.y, rotation.z),
                    linearAcceleration: Self.magnitude(
                        acceleration.x,
                        acceleration.y,
                        acceleration.z
                    ) * Self.gravityMetersPerSecondSquared
                ))
            }
            return
        }

        let hasGyroscope = manager.isGyroAvailable
        let hasAcceleration = manager.isAccelerometerAvailable
        stateQueue.sync {
            monitoring = true
            machine.start(
                capabilities: DNIMotionCapabilities(
                    gyroscope: hasGyroscope,
                    acceleration: hasAcceleration
                ),
                at: ProcessInfo.processInfo.systemUptime
            )
        }

        if hasGyroscope {
            manager.gyroUpdateInterval = Self.updateInterval
            manager.startGyroUpdates(to: queue) { [weak self] data, _ in
                guard let self = self, let data = data else { return }
                let rotation = data.rotationRate
                self.machine.add(DNIMotionSample(
                    timestamp: data.timestamp,
                    gyroscopeRadPerSecond: Self.magnitude(rotation.x, rotation.y, rotation.z),
                    linearAcceleration: nil
                ))
            }
        }

        if hasAcceleration {
            manager.accelerometerUpdateInterval = Self.updateInterval
            manager.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
                guard let self = self, let data = data else { return }
                let linear = self.fallbackLinearAcceleration(from: data.acceleration)
                self.machine.add(DNIMotionSample(
                    timestamp: data.timestamp,
                    gyroscopeRadPerSecond: nil,
                    linearAcceleration: linear
                ))
            }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
        manager.stopGyroUpdates()
        manager.stopAccelerometerUpdates()

        guard let queue = operationQueue else { return }
        queue.cancelAllOperations()
        queue.waitUntilAllOperationsAreFinished()
        stateQueue.sync {
            machine.stop()
            monitoring = false
            fallbackGravity = nil
        }
        operationQueue = nil
    }

    func snapshot() -> DNIMotionSnapshot {
        return stateQueue.sync {
            guard monitoring else {
                return DNIMotionSnapshot(
                    sensorAvailable: capabilities.sensorAvailable,
                    gyroscopeAvailable: capabilities.gyroscope,
                    accelerationAvailable: capabilities.acceleration,
                    sampling: false,
                    stable: true,
                    gyroscopeRadPerSecond: nil,
                    linearAcceleration: nil,
                    quietDurationMs: 0
                )
            }
            return machine.snapshot(at: ProcessInfo.processInfo.systemUptime)
        }
    }

    func beginCapture() -> Bool {
        return stateQueue.sync {
            machine.beginCapture(at: ProcessInfo.processInfo.systemUptime)
        }
    }

    func captureMayProceed() -> Bool {
        return stateQueue.sync {
            machine.captureMayProceed(at: ProcessInfo.processInfo.systemUptime)
        }
    }

    func finishCapture() -> DNIMotionCaptureOutcome {
        return stateQueue.sync {
            machine.finishCapture(at: ProcessInfo.processInfo.systemUptime)
        }
    }

    func cancelCapture() {
        stateQueue.sync {
            machine.cancelCapture()
        }
    }

    private func fallbackLinearAcceleration(from acceleration: CMAcceleration) -> Double {
        if let gravity = fallbackGravity {
            let factor = Self.fallbackGravitySmoothingFactor
            let updated = CMAcceleration(
                x: factor * gravity.x + (1 - factor) * acceleration.x,
                y: factor * gravity.y + (1 - factor) * acceleration.y,
                z: factor * gravity.z + (1 - factor) * acceleration.z
            )
            fallbackGravity = updated
            return Self.magnitude(
                acceleration.x - updated.x,
                acceleration.y - updated.y,
                acceleration.z - updated.z
            ) * Self.gravityMetersPerSecondSquared
        }
        fallbackGravity = acceleration
        return 0
    }

    private static func magnitude(_ x: Double, _ y: Double, _ z: Double) -> Double {
        return sqrt(x * x + y * y + z * z)
    }
}
