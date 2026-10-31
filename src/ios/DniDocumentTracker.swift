import CoreGraphics
import Foundation
import ImageIO
import Vision

/// Vision-native temporal document pipeline. A full document/rectangle pass
/// periodically anchors VNTrackRectangleRequest; the smoothed corners are used
/// for UI and stability while the unsmoothed observation continues tracking.
final class DNIDocumentTracker {
    private let engine = DNIEngine()
    private var sequenceHandler = VNSequenceRequestHandler()
    private var observation: VNRectangleObservation?
    private var smoothedQuad: DNIQuad?
    private var previousRawQuad: DNIQuad?
    private var lastDetection: DNIDetection?
    private var frameIndex = 0
    private var missedFrames = 0
    private var stableSince: CFTimeInterval = 0
    private var stableFrames = 0

    func update(
        raster: DNIRaster,
        checks: DNIChecks,
        allowedRegion: CGRect,
        profile: DNIScanProfile,
        at timestamp: CFTimeInterval
    ) throws -> DNIAnalysis {
        var pair: (detection: DNIDetection, observation: VNRectangleObservation)?
        let shouldDetect = observation == nil || frameIndex % 5 == 0 || missedFrames > 0
        frameIndex += 1

        if !shouldDetect, let currentObservation = observation {
            let request = VNTrackRectangleRequest(rectangleObservation: currentObservation)
            request.trackingLevel = .fast
            do {
                try sequenceHandler.perform(
                    [request],
                    on: raster.cgImage(),
                    orientation: .up
                )
                if let tracked = request.results?.first as? VNRectangleObservation,
                   tracked.confidence >= 0.38,
                   let detection = engine.detection(
                        from: tracked,
                        raster: raster,
                        allowedRegion: allowedRegion,
                        guideMarginPercent: DNIEngine.guideMarginPercent,
                        prior: smoothedQuad
                   ) {
                    pair = (detection, tracked)
                }
            } catch {
                pair = nil
            }
        }

        if pair == nil {
            pair = try engine.detectWithObservation(
                raster: raster,
                allowedRegion: allowedRegion,
                guideMarginPercent: DNIEngine.guideMarginPercent,
                prior: smoothedQuad
            )
            if pair == nil, smoothedQuad != nil {
                pair = try engine.detectWithObservation(
                    raster: raster,
                    allowedRegion: allowedRegion,
                    guideMarginPercent: DNIEngine.guideMarginPercent,
                    prior: nil
                )
            }
        }

        guard let current = pair else {
            missedFrames += 1
            if missedFrames <= 2, let previous = lastDetection {
                stableFrames = max(0, stableFrames - 1)
                return DNIAnalysis(
                    state: .holdStill,
                    detection: previous,
                    quality: nil,
                    imageWidth: raster.width,
                    imageHeight: raster.height,
                    stableFrameCount: stableFrames,
                    stableDurationMs: stableSince == 0
                        ? 0 : Int(max(0, timestamp - stableSince) * 1000)
                )
            }
            reset()
            return DNIAnalysis(
                state: .searching,
                detection: nil,
                quality: nil,
                imageWidth: raster.width,
                imageHeight: raster.height
            )
        }

        missedFrames = 0
        observation = current.observation
        let rawQuad = current.detection.quad
        let stable = updateStability(
            rawQuad,
            width: raster.width,
            height: raster.height,
            at: timestamp
        )
        let smooth = smoothedQuad.map {
            engine.blend($0, rawQuad, currentWeight: 0.58)
        } ?? rawQuad
        smoothedQuad = smooth
        previousRawQuad = rawQuad

        let detection = metrics(
            for: smooth,
            score: current.detection.score,
            width: raster.width,
            height: raster.height,
            source: current.detection.source
        )
        lastDetection = detection
        let quality = engine.evaluate(raster: raster, quad: smooth)
        var state = engine.classify(
            detection: detection,
            quality: quality,
            checks: checks,
            profile: profile
        )
        let duration = stableSince == 0
            ? 0 : Int(max(0, timestamp - stableSince) * 1000)
        let requiredFrames = checks.stability ? 4 : 2
        let requiredDuration = checks.stability
            ? DNIEngine.requiredStableDurationMs : 240
        if state == .ready
            && (!stable || stableFrames < requiredFrames || duration < requiredDuration) {
            state = .holdStill
        }
        return DNIAnalysis(
            state: state,
            detection: detection,
            quality: quality,
            imageWidth: raster.width,
            imageHeight: raster.height,
            stableFrameCount: stableFrames,
            stableDurationMs: duration
        )
    }

    private func updateStability(
        _ current: DNIQuad,
        width: Int,
        height: Int,
        at timestamp: CFTimeInterval
    ) -> Bool {
        guard let previous = previousRawQuad else {
            stableSince = timestamp
            stableFrames = 1
            return false
        }
        let movement = engine.quadDistance(previous, current, width: width, height: height)
        let areaChange = abs(current.area - previous.area) / max(1, previous.area)
        if movement <= 0.018 && areaChange <= 0.055 {
            stableFrames += 1
            return true
        }
        if movement <= 0.032 && areaChange <= 0.10 {
            stableFrames = max(1, stableFrames - 1)
            stableSince = max(stableSince, timestamp - 0.18)
            return false
        }
        stableSince = timestamp
        stableFrames = 1
        return false
    }

    private func metrics(
        for quad: DNIQuad,
        score: CGFloat,
        width: Int,
        height: Int,
        source: String
    ) -> DNIDetection {
        let area = quad.area / max(1, CGFloat(width * height))
        let center = quad.center
        let centerOffset = hypot(
            (center.x - CGFloat(width) * 0.5) / max(1, CGFloat(width) * 0.5),
            (center.y - CGFloat(height) * 0.5) / max(1, CGFloat(height) * 0.5)
        )
        let averageHeight = (quad.leftHeight + quad.rightHeight) * 0.5
        let aspect = (quad.topWidth + quad.bottomWidth) * 0.5 / max(1, averageHeight)
        return DNIDetection(
            quad: quad,
            score: score,
            areaFraction: area,
            centerOffset: centerOffset,
            observedAspect: aspect,
            source: source
        )
    }

    func reset() {
        sequenceHandler = VNSequenceRequestHandler()
        observation = nil
        smoothedQuad = nil
        previousRawQuad = nil
        lastDetection = nil
        frameIndex = 0
        missedFrames = 0
        stableSince = 0
        stableFrames = 0
    }
}
