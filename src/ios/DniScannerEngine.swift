import AVFoundation
import CoreImage
import Foundation
import UIKit
import Vision

struct DNIRaster {
    let width: Int
    let height: Int
    let rgba: [UInt8]
    let luma: [UInt8]

    func cgImage() throws -> CGImage {
        let data = Data(rgba) as CFData
        guard let provider = CGDataProvider(data: data),
              let image = CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider,
                decode: nil,
                shouldInterpolate: true,
                intent: .defaultIntent
              ) else {
            throw DNIError.processingFailed("No se pudo preparar la imagen para analizar.", false)
        }
        return image
    }

    func lumaAt(x: Int, y: Int) -> Int {
        let safeX = max(0, min(width - 1, x))
        let safeY = max(0, min(height - 1, y))
        return Int(luma[safeY * width + safeX])
    }

    func rgbAt(x: Int, y: Int) -> (Int, Int, Int) {
        let safeX = max(0, min(width - 1, x))
        let safeY = max(0, min(height - 1, y))
        let index = (safeY * width + safeX) * 4
        return (Int(rgba[index]), Int(rgba[index + 1]), Int(rgba[index + 2]))
    }
}

final class DNIImageFactory {
    static let shared = DNIImageFactory()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()

    func raster(
        from sampleBuffer: CMSampleBuffer,
        maxDimension: Int,
        viewportAspectRatio: CGFloat
    ) throws -> DNIRaster {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            throw DNIError.processingFailed("La cámara no entregó una imagen válida.", true)
        }
        let source = CIImage(cvPixelBuffer: pixelBuffer)
        return try render(
            centerCrop(source, toAspectRatio: viewportAspectRatio),
            maxDimension: maxDimension
        )
    }

    func raster(from data: Data, maxDimension: Int) throws -> DNIRaster {
        guard let image = CIImage(
            data: data,
            options: [.applyOrientationProperty: true]
        ) else {
            throw DNIError.processingFailed("No se pudo decodificar la fotografía capturada.", false)
        }
        return try render(image, maxDimension: maxDimension)
    }

    func orientedDimensions(from data: Data) throws -> (width: Int, height: Int) {
        guard let image = CIImage(
            data: data,
            options: [.applyOrientationProperty: true]
        ) else {
            throw DNIError.processingFailed("No se pudo decodificar la fotografía capturada.", false)
        }
        let extent = image.extent.integral
        guard extent.width > 1, extent.height > 1 else {
            throw DNIError.processingFailed("La imagen capturada no tiene dimensiones válidas.", false)
        }
        return (
            width: Int(extent.width.rounded()),
            height: Int(extent.height.rounded())
        )
    }

    func raster(from cgImage: CGImage, width: Int, height: Int) throws -> DNIRaster {
        let source = CIImage(cgImage: cgImage)
        let scaleX = CGFloat(width) / source.extent.width
        let scaleY = CGFloat(height) / source.extent.height
        let scaled = source.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        return try renderExact(scaled, width: width, height: height)
    }

    func cropRect(
        from source: DNIRaster,
        quad: DNIQuad,
        paddingFraction: CGFloat,
        guardPixels: CGFloat = 1
    ) -> CGRect? {
        let paddingX = (quad.topWidth + quad.bottomWidth) * 0.5 * max(0, paddingFraction)
        let paddingY = (quad.leftHeight + quad.rightHeight) * 0.5 * max(0, paddingFraction)
        let minX = floor((quad.points.map(\.x).min() ?? 0) - paddingX)
        let maxX = ceil((quad.points.map(\.x).max() ?? 0) + paddingX)
        let minY = floor((quad.points.map(\.y).min() ?? 0) - paddingY)
        let maxY = ceil((quad.points.map(\.y).max() ?? 0) + paddingY)
        guard minX >= guardPixels, minY >= guardPixels,
              maxX <= CGFloat(source.width - 1) - guardPixels,
              maxY <= CGFloat(source.height - 1) - guardPixels,
              maxX - minX >= 2, maxY - minY >= 2 else {
            return nil
        }
        return CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }

    func croppedImage(
        from source: DNIRaster,
        quad: DNIQuad,
        maxLongEdge: Int,
        paddingFraction: CGFloat
    ) throws -> CGImage {
        let sourceCG = try source.cgImage()
        let image = CIImage(cgImage: sourceCG)
        guard let topDownRect = cropRect(
            from: source,
            quad: quad,
            paddingFraction: paddingFraction
        ) else {
            throw DNIError.processingFailed(
                "No hay margen suficiente para recortar el documento completo.",
                true
            )
        }
        let ciRect = CGRect(
            x: topDownRect.minX,
            y: CGFloat(source.height) - topDownRect.maxY,
            width: topDownRect.width,
            height: topDownRect.height
        )
        let cropped = image.cropped(to: ciRect)
        let scale = min(1, CGFloat(maxLongEdge) / max(ciRect.width, ciRect.height))
        let targetWidth = max(1, Int((ciRect.width * scale).rounded()))
        let targetHeight = max(1, Int((ciRect.height * scale).rounded()))
        let translated = cropped.transformed(
            by: CGAffineTransform(translationX: -ciRect.origin.x, y: -ciRect.origin.y)
        )
        let scaled = translated.transformed(by: CGAffineTransform(
            scaleX: CGFloat(targetWidth) / ciRect.width,
            y: CGFloat(targetHeight) / ciRect.height
        ))
        let targetRect = CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight)
        guard let output = context.createCGImage(scaled, from: targetRect) else {
            throw DNIError.processingFailed("No se pudo generar el JPEG recortado.", false)
        }
        return output
    }

    private func render(_ source: CIImage, maxDimension: Int) throws -> DNIRaster {
        let extent = source.extent.integral
        guard extent.width > 1, extent.height > 1 else {
            throw DNIError.processingFailed("La imagen capturada no tiene dimensiones válidas.", false)
        }
        let scale = min(1, CGFloat(maxDimension) / max(extent.width, extent.height))
        let width = max(2, Int((extent.width * scale).rounded()))
        let height = max(2, Int((extent.height * scale).rounded()))
        let normalized = source
            .transformed(by: CGAffineTransform(translationX: -extent.origin.x, y: -extent.origin.y))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return try renderExact(normalized, width: width, height: height)
    }

    private func centerCrop(_ source: CIImage, toAspectRatio targetAspect: CGFloat) -> CIImage {
        let extent = source.extent.integral
        guard extent.width > 1, extent.height > 1, targetAspect.isFinite, targetAspect > 0 else {
            return source
        }
        let sourceAspect = extent.width / extent.height
        let crop: CGRect
        if sourceAspect > targetAspect {
            let width = extent.height * targetAspect
            crop = CGRect(
                x: extent.midX - width / 2,
                y: extent.minY,
                width: width,
                height: extent.height
            )
        } else {
            let height = extent.width / targetAspect
            crop = CGRect(
                x: extent.minX,
                y: extent.midY - height / 2,
                width: extent.width,
                height: height
            )
        }
        return source.cropped(to: crop)
    }

    private func renderExact(_ image: CIImage, width: Int, height: Int) throws -> DNIRaster {
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        rgba.withUnsafeMutableBytes { bytes in
            guard let address = bytes.baseAddress else { return }
            context.render(
                image,
                toBitmap: address,
                rowBytes: width * 4,
                bounds: CGRect(x: 0, y: 0, width: width, height: height),
                format: .RGBA8,
                colorSpace: colorSpace
            )
        }
        var luma = [UInt8](repeating: 0, count: width * height)
        for pixel in 0..<(width * height) {
            let offset = pixel * 4
            let value = (77 * Int(rgba[offset])
                + 150 * Int(rgba[offset + 1])
                + 29 * Int(rgba[offset + 2])) >> 8
            luma[pixel] = UInt8(clamping: value)
        }
        return DNIRaster(width: width, height: height, rgba: rgba, luma: luma)
    }
}

final class DNIEngine {
    static let expectedAspect: CGFloat = 85.60 / 53.98
    static let minimumObservedAspect: CGFloat = 1.35
    static let maximumObservedAspect: CGFloat = 2.25
    static let minimumDocumentArea: CGFloat = 0.16
    static let maximumDocumentArea: CGFloat = 0.86
    static let maximumCenterOffset: CGFloat = 0.25
    static let maximumRectangleDeviationDegrees: CGFloat = 1.5
    static let maximumSymmetricPerspectiveDeviationDegrees: CGFloat = 8
    static let maximumTrapezoidSymmetryErrorDegrees: CGFloat = 2.5
    static let maximumInPlaneRotationDegrees: CGFloat = 2
    static let minimumBrightness = 36.0
    // Pale DNI v1/v2 stock is not overexposed merely because much of the card
    // is white. Require both a very high mean and substantial lost highlights.
    static let maximumBrightness = 245.0
    static let maximumDarkClipping = 0.45
    static let maximumHighlightClipping = 0.48
    static let maximumGlare = 0.14
    static let maximumIlluminationSpread = 0.52
    static let maximumShadowBoundary = 0.30
    static let maximumEdgeObstruction = 1.0
    // Keep the dotted guide as an aiming aid. A card aligned by hand may sit a
    // few pixels outside it and should still be eligible when the source image
    // contains all four physical edges.
    static let guideMarginPercent: CGFloat = 6
    static let stillDetectionMarginPercent: CGFloat = 8
    static let minimumSharpness = 55.0
    // Permissive diagnostics for downstream OCR. They do not gate capture.
    static let minimumOCRSharpness = 55.0
    static let minimumOCRBrightness = 35.0
    static let maximumOCRBrightness = 238.0
    static let maximumOCRDarkClipping = 0.50
    static let maximumOCRHighlightClipping = 0.60
    static let maximumOCRGlare = 0.28
    static let requiredStableFrames = 4
    static let requiredStableDurationMs = 500
    private let imageFactory = DNIImageFactory.shared

    static func maximumGlare(for profile: DNIScanProfile) -> Double {
        return profile == .genericCard ? 0.23 : maximumGlare
    }

    static func isOverexposed(_ quality: DNIQuality) -> Bool {
        return quality.brightness > maximumBrightness
            && quality.highlightClipping > maximumHighlightClipping
    }

    static func maximumIlluminationSpread(for profile: DNIScanProfile) -> Double {
        return profile == .genericCard ? 0.45 : maximumIlluminationSpread
    }

    static func maximumShadowBoundary(for profile: DNIScanProfile) -> Double {
        return profile == .genericCard ? 0.36 : maximumShadowBoundary
    }

    func analyze(
        raster: DNIRaster,
        checks: DNIChecks,
        allowedRegion: CGRect,
        profile: DNIScanProfile = .dni
    ) throws -> DNIAnalysis {
        guard let detection = try detect(
            raster: raster,
            allowedRegion: allowedRegion,
            guideMarginPercent: Self.guideMarginPercent
        ) else {
            return DNIAnalysis(
                state: .searching,
                detection: nil,
                quality: nil,
                imageWidth: raster.width,
                imageHeight: raster.height
            )
        }
        let quality = evaluate(raster: raster, quad: detection.quad)
        let state = classify(
            detection: detection,
            quality: quality,
            checks: checks,
            profile: profile
        )
        return DNIAnalysis(
            state: state,
            detection: detection,
            quality: quality,
            imageWidth: raster.width,
            imageHeight: raster.height
        )
    }

    func detect(
        raster: DNIRaster,
        allowedRegion: CGRect,
        guideMarginPercent: CGFloat,
        prior: DNIQuad? = nil
    ) throws -> DNIDetection? {
        return try detectWithObservation(
            raster: raster,
            allowedRegion: allowedRegion,
            guideMarginPercent: guideMarginPercent,
            prior: prior
        )?.detection
    }

    /// Native Apple detector used by both still analysis and temporal tracking.
    /// Document segmentation handles low-contrast pages; rectangle detection is
    /// a local, deterministic fallback and does not require a model download.
    func detectWithObservation(
        raster: DNIRaster,
        allowedRegion: CGRect,
        guideMarginPercent: CGFloat,
        prior: DNIQuad? = nil
    ) throws -> (detection: DNIDetection, observation: VNRectangleObservation)? {
        let image = try raster.cgImage()
        let documentRequest = VNDetectDocumentSegmentationRequest()
        let handler = VNImageRequestHandler(cgImage: image, orientation: .up)
        try handler.perform([documentRequest])
        if let observations = documentRequest.results,
           let selected = select(
                observations: observations,
                raster: raster,
                allowedRegion: allowedRegion,
                guideMarginPercent: guideMarginPercent,
                prior: prior,
                source: "vision-document"
           ) {
            return selected
        }

        let rectangleRequest = VNDetectRectanglesRequest()
        rectangleRequest.maximumObservations = 16
        rectangleRequest.minimumConfidence = 0.28
        rectangleRequest.minimumAspectRatio = 0.32
        rectangleRequest.maximumAspectRatio = 0.92
        rectangleRequest.minimumSize = 0.10
        rectangleRequest.quadratureTolerance = 18
        try VNImageRequestHandler(cgImage: image, orientation: .up).perform([rectangleRequest])
        return select(
            observations: rectangleRequest.results ?? [],
            raster: raster,
            allowedRegion: allowedRegion,
            guideMarginPercent: guideMarginPercent,
            prior: prior,
            source: "vision-rectangle"
        )
    }

    func detection(
        from observation: VNRectangleObservation,
        raster: DNIRaster,
        allowedRegion: CGRect,
        guideMarginPercent: CGFloat,
        prior: DNIQuad? = nil,
        source: String = "vision-track"
    ) -> DNIDetection? {
        return select(
            observations: [observation],
            raster: raster,
            allowedRegion: allowedRegion,
            guideMarginPercent: guideMarginPercent,
            prior: prior,
            source: source
        )?.detection
    }

    private func select(
        observations: [VNRectangleObservation],
        raster: DNIRaster,
        allowedRegion: CGRect,
        guideMarginPercent: CGFloat,
        prior: DNIQuad?,
        source: String
    ) -> (detection: DNIDetection, observation: VNRectangleObservation)? {
        let margin = min(allowedRegion.width, allowedRegion.height) * guideMarginPercent / 100
        let permitted = allowedRegion.insetBy(dx: -margin, dy: -margin)
        let targetArea = allowedRegion.width > 0.90 && allowedRegion.height > 0.90
            ? CGFloat(0.47)
            : max(0.01, min(0.99, allowedRegion.width * allowedRegion.height))
        var selected: (DNIDetection, VNRectangleObservation)?
        for observation in observations {
            let normalized = normalizedQuad(observation)
            guard normalized.points.allSatisfy({ permitted.contains($0) }) else { continue }
            let quad = normalized.scaled(x: CGFloat(raster.width), y: CGFloat(raster.height))
            let areaFraction = quad.area / CGFloat(raster.width * raster.height)
            guard areaFraction >= 0.10, areaFraction <= 0.94 else { continue }
            let height = (quad.leftHeight + quad.rightHeight) * 0.5
            let aspect = (quad.topWidth + quad.bottomWidth) * 0.5 / max(1, height)
            guard aspect >= 1.08, aspect <= 2.75 else { continue }
            let center = normalized.center
            let centerOffset = hypot(center.x - 0.5, center.y - 0.5) / hypot(0.5, 0.5)
            let priorScore: CGFloat
            if let prior {
                let distance = quadDistance(prior, quad, width: raster.width, height: raster.height)
                guard distance <= 0.14 else { continue }
                priorScore = max(0, 1 - distance / 0.14)
            } else {
                priorScore = 0.5
            }
            let shape = CGFloat(perspectiveScore(quad))
            let aspectScore = max(0, 1 - abs(aspect - Self.expectedAspect) / 0.85)
            let areaScore = max(0, 1 - abs(areaFraction - targetArea) / max(0.18, targetArea))
            let score = CGFloat(observation.confidence) * 0.30
                + shape * 0.20 + aspectScore * 0.16 + areaScore * 0.14
                + max(0, 1 - centerOffset) * 0.08 + priorScore * 0.12
            guard score >= 0.45 else { continue }
            let detection = DNIDetection(
                quad: quad,
                score: score,
                areaFraction: areaFraction,
                centerOffset: centerOffset,
                observedAspect: aspect,
                source: source
            )
            if selected == nil || detection.score > selected!.0.score {
                selected = (detection, observation)
            }
        }
        return selected
    }

    private func normalizedQuad(_ observation: VNRectangleObservation) -> DNIQuad {
        return DNIQuad(
            topLeft: CGPoint(x: observation.topLeft.x, y: 1 - observation.topLeft.y),
            topRight: CGPoint(x: observation.topRight.x, y: 1 - observation.topRight.y),
            bottomRight: CGPoint(x: observation.bottomRight.x, y: 1 - observation.bottomRight.y),
            bottomLeft: CGPoint(x: observation.bottomLeft.x, y: 1 - observation.bottomLeft.y)
        )
    }

    func quadDistance(_ first: DNIQuad, _ second: DNIQuad, width: Int, height: Int) -> CGFloat {
        let diagonal = max(1, hypot(CGFloat(width), CGFloat(height)))
        let sum = zip(first.points, second.points).reduce(CGFloat(0)) { value, pair in
            let distance = pair.0.distance(to: pair.1) / diagonal
            return value + distance * distance
        }
        return sqrt(sum / 4)
    }

    func blend(_ previous: DNIQuad, _ current: DNIQuad, currentWeight: CGFloat) -> DNIQuad {
        let points = zip(previous.points, current.points).map { pair in
            let (old, new) = pair
            CGPoint(
                x: old.x + (new.x - old.x) * currentWeight,
                y: old.y + (new.y - old.y) * currentWeight
            )
        }
        return DNIQuad(
            topLeft: points[0], topRight: points[1],
            bottomRight: points[2], bottomLeft: points[3]
        )
    }

    func evaluate(raster: DNIRaster, quad: DNIQuad) -> DNIQuality {
        let perspective = perspectiveScore(quad)
        let normalized: DNIRaster
        if let image = try? imageFactory.croppedImage(
            from: raster,
            quad: quad,
            maxLongEdge: 512,
            paddingFraction: 0
        ), let rectified = try? imageFactory.raster(
            from: image,
            width: image.width,
            height: image.height
        ) {
            normalized = rectified
        } else {
            normalized = raster
        }
        let insetX = max(2, Int(Double(normalized.width) * 0.08))
        let insetY = max(2, Int(Double(normalized.height) * 0.08))
        let minX = insetX
        let maxX = max(minX + 2, normalized.width - insetX)
        let minY = insetY
        let maxY = max(minY + 2, normalized.height - insetY)
        var count = 0
        var sum = 0.0
        var dark = 0
        var clipped = 0
        var tileMeans = [Double](repeating: 0, count: 9)
        var tileSharpness = [Double](repeating: 0, count: 9)
        for row in 0..<3 {
            for column in 0..<3 {
                let left = minX + (maxX - minX) * column / 3
                let right = minX + (maxX - minX) * (column + 1) / 3
                let top = minY + (maxY - minY) * row / 3
                let bottom = minY + (maxY - minY) * (row + 1) / 3
                var tileCount = 0
                var tileSum = 0.0
                var laplacianSum = 0.0
                var laplacianSquared = 0.0
                for y in max(1, top)..<min(normalized.height - 1, bottom) {
                    for x in max(1, left)..<min(normalized.width - 1, right) {
                        let value = normalized.lumaAt(x: x, y: y)
                        tileCount += 1
                        tileSum += Double(value)
                        count += 1
                        sum += Double(value)
                        if value <= 20 { dark += 1 }
                        if value >= 250 { clipped += 1 }
                        let laplacian = 4 * value
                            - normalized.lumaAt(x: x - 1, y: y)
                            - normalized.lumaAt(x: x + 1, y: y)
                            - normalized.lumaAt(x: x, y: y - 1)
                            - normalized.lumaAt(x: x, y: y + 1)
                        let lap = Double(laplacian)
                        laplacianSum += lap
                        laplacianSquared += lap * lap
                    }
                }
                let index = row * 3 + column
                let safeTileCount = Double(max(1, tileCount))
                tileMeans[index] = tileSum / safeTileCount
                let meanLaplacian = laplacianSum / safeTileCount
                tileSharpness[index] = max(
                    0,
                    laplacianSquared / safeTileCount - meanLaplacian * meanLaplacian
                )
            }
        }
        let sortedSharpness = tileSharpness.sorted()
        let sharpness = sortedSharpness[6]
        var maximumBoundary = 0.0
        for row in 0..<3 {
            for column in 0..<3 {
                let index = row * 3 + column
                if row > 0 {
                    maximumBoundary = max(
                        maximumBoundary,
                        abs(tileMeans[index] - tileMeans[(row - 1) * 3 + column]) / 255
                    )
                }
                if column > 0 {
                    maximumBoundary = max(
                        maximumBoundary,
                        abs(tileMeans[index] - tileMeans[row * 3 + column - 1]) / 255
                    )
                }
            }
        }
        let safeCount = Double(max(1, count))
        let clipping = Double(clipped) / safeCount
        return DNIQuality(
            sharpness: sharpness,
            brightness: sum / safeCount,
            darkClipping: Double(dark) / safeCount,
            highlightClipping: clipping,
            glare: clipping,
            perspective: perspective,
            edgeGap: 0,
            edgeObstruction: 0,
            skinEdgeObstruction: 0,
            illuminationSpread: ((tileMeans.max() ?? 0) - (tileMeans.min() ?? 0)) / 255,
            shadowBoundary: maximumBoundary
        )
    }

    func classify(
        detection: DNIDetection,
        quality: DNIQuality,
        checks: DNIChecks,
        profile: DNIScanProfile = .dni
    ) -> DNIAnalysisState {
        if checks.documentSize && detection.areaFraction < Self.minimumDocumentArea { return .moveCloser }
        if checks.documentSize && detection.areaFraction > Self.maximumDocumentArea { return .moveAway }
        if checks.centering && detection.centerOffset > Self.maximumCenterOffset { return .centerDocument }
        if detection.quad.maximumInPlaneRotationDegrees
            > Self.maximumInPlaneRotationDegrees { return .adjustPerspective }
        if !detection.quad.hasAcceptablePerspective(
            freeLimit: Self.maximumRectangleDeviationDegrees,
            symmetricLimit: Self.maximumSymmetricPerspectiveDeviationDegrees,
            symmetryTolerance: Self.maximumTrapezoidSymmetryErrorDegrees
        ) { return .adjustPerspective }
        if checks.brightness && (quality.brightness < Self.minimumBrightness || quality.darkClipping > Self.maximumDarkClipping) {
            return .improveLighting
        }
        if checks.brightness && quality.highlightClipping > 0.42 && quality.glare > 0.10 {
            return .reduceLighting
        }
        if checks.glare && quality.highlightClipping > 0.26 && quality.glare > 0.26 {
            return .avoidGlare
        }
        if checks.shadows
            && quality.illuminationSpread > Self.maximumIlluminationSpread(for: profile)
            && quality.shadowBoundary > Self.maximumShadowBoundary(for: profile) {
            return .unevenLighting
        }
        if checks.sharpness && quality.sharpness < Self.minimumSharpness { return .waitingForFocus }
        return .ready
    }

    private func perspectiveScore(_ quad: DNIQuad) -> Double {
        func ratio(_ first: CGFloat, _ second: CGFloat) -> CGFloat {
            return min(first, second) / max(1, max(first, second))
        }
        let widthScore = ratio(quad.topWidth, quad.bottomWidth)
        let heightScore = ratio(quad.leftHeight, quad.rightHeight)
        let diagonalScore = ratio(quad.topLeft.distance(to: quad.bottomRight), quad.topRight.distance(to: quad.bottomLeft))
        let points = quad.points
        var angleScore: CGFloat = 0
        for index in points.indices {
            let previous = points[(index + 3) % points.count]
            let current = points[index]
            let next = points[(index + 1) % points.count]
            let first = CGPoint(x: previous.x - current.x, y: previous.y - current.y)
            let second = CGPoint(x: next.x - current.x, y: next.y - current.y)
            let denominator = hypot(first.x, first.y) * hypot(second.x, second.y)
            let cosine = denominator < 0.0001
                ? 1
                : abs((first.x * second.x + first.y * second.y) / denominator)
            angleScore += max(0, 1 - cosine)
        }
        angleScore /= CGFloat(points.count)
        return Double(max(0, min(
            1,
            0.30 * widthScore
                + 0.25 * heightScore
                + 0.20 * diagonalScore
                + 0.25 * angleScore
        )))
    }

}

final class DNIStillProcessor {
    private let engine = DNIEngine()
    private let factory = DNIImageFactory.shared
    private let recognizer = DNISideVariantRecognizer()

    func process(
        data: Data,
        options: DNIOptions,
        directory: URL,
        previewDetection: DNIDetection? = nil,
        previewWidth: Int = 0,
        previewHeight: Int = 0
    ) throws -> DNIProcessedScan {
        let rawDimensions = try factory.orientedDimensions(from: data)
        let source = try factory.raster(
            from: data,
            maxDimension: max(options.preferredCaptureLongEdge, options.maxOutputLongEdge)
        )
        let permitted = CGRect(x: 0.015, y: 0.015, width: 0.97, height: 0.97)
        let prior: DNIQuad?
        let previewAspect = previewHeight > 0
            ? CGFloat(previewWidth) / CGFloat(previewHeight) : 0
        let sourceAspect = CGFloat(source.width) / CGFloat(source.height)
        if let previewDetection, previewWidth > 0, previewHeight > 0,
           abs(previewAspect - sourceAspect) / max(0.01, sourceAspect) < 0.08 {
            prior = previewDetection.quad.scaled(
                x: CGFloat(source.width) / CGFloat(previewWidth),
                y: CGFloat(source.height) / CGFloat(previewHeight)
            )
        } else {
            prior = nil
        }
        var detection = try engine.detect(
            raster: source,
            allowedRegion: permitted,
            guideMarginPercent: DNIEngine.stillDetectionMarginPercent,
            prior: prior
        )
        if detection == nil, prior != nil {
            detection = try engine.detect(
                raster: source,
                allowedRegion: permitted,
                guideMarginPercent: DNIEngine.stillDetectionMarginPercent,
                prior: nil
            )
        }
        if let prior {
            if let stillDetection = detection {
                let match = comparePreviewTrack(
                    prior: prior,
                    detected: stillDetection.quad,
                    width: source.width,
                    height: source.height
                )
                if match.isTight {
                    // High-resolution Vision corners agree with the stable preview.
                } else if match.isBounded {
                    let bounded = blend(prior, stillDetection.quad, currentWeight: 0.25)
                    detection = detectionForQuad(
                        bounded,
                        score: stillDetection.score,
                        width: source.width,
                        height: source.height,
                        source: "bounded_still"
                    )
                } else {
                    // Never allow a late Vision proposal to replace the stable
                    // preview geometry with a materially different crop.
                    detection = detectionForQuad(
                        prior,
                        score: min(stillDetection.score, 0.55),
                        width: source.width,
                        height: source.height,
                        source: "preview_guard"
                    )
                }
            } else {
                detection = detectionForQuad(
                    prior,
                    score: 0.50,
                    width: source.width,
                    height: source.height,
                    source: "preview_fallback"
                )
            }
        }
        guard let detection else {
            throw DNIError.retry("No se pudieron confirmar los cuatro bordes en la fotografía.", .searching)
        }
        guard factory.cropRect(
            from: source,
            quad: detection.quad,
            paddingFraction: 0.06,
            guardPixels: 2
        ) != nil else {
            throw DNIError.retry(
                "No se pudo conservar completo el contorno del DNI.",
                .searching
            )
        }
        let preQuality = engine.evaluate(raster: source, quad: detection.quad)
        let preState = engine.classify(
            detection: detection,
            quality: preQuality,
            checks: options.checks,
            profile: options.profile
        )
        if preState != .ready && preState != .moveCloser && preState != .moveAway && preState != .centerDocument {
            throw DNIError.retry("La fotografía final no alcanzó la calidad mínima.", preState)
        }

        let rawQuad = detection.quad.scaled(
            x: CGFloat(rawDimensions.width) / CGFloat(source.width),
            y: CGFloat(rawDimensions.height) / CGFloat(source.height)
        )
        let effectiveDocumentLongEdge = Int(max(
            (rawQuad.topWidth + rawQuad.bottomWidth) * 0.5,
            (rawQuad.leftHeight + rawQuad.rightHeight) * 0.5 * DNIEngine.expectedAspect
        ).rounded())
        guard effectiveDocumentLongEdge >= options.minEffectiveDocumentLongEdge else {
            throw DNIError.retry(
                "La fotografía no alcanzó la resolución efectiva mínima.",
                .moveCloser
            )
        }

        let correctedCG = try factory.croppedImage(
            from: source,
            quad: detection.quad,
            maxLongEdge: options.maxOutputLongEdge,
            paddingFraction: 0.06
        )
        let corrected = try factory.raster(
            from: correctedCG,
            width: correctedCG.width,
            height: correctedCG.height
        )
        let outputQuad = DNIQuad(
            topLeft: .zero,
            topRight: CGPoint(x: corrected.width - 1, y: 0),
            bottomRight: CGPoint(x: corrected.width - 1, y: corrected.height - 1),
            bottomLeft: CGPoint(x: 0, y: corrected.height - 1)
        )
        let quality = engine.evaluate(raster: corrected, quad: outputQuad)
        let outputDetection = DNIDetection(
            quad: outputQuad,
            score: detection.score,
            areaFraction: 1,
            centerOffset: 0,
            observedAspect: CGFloat(corrected.width) / max(1, CGFloat(corrected.height))
        )
        let outputState = engine.classify(
            detection: outputDetection,
            quality: quality,
            checks: outputOnlyChecks(options.checks),
            profile: options.profile
        )
        if outputState != .ready {
            throw DNIError.retry("La imagen recortada no alcanzó la calidad mínima.", outputState)
        }
        if options.checks.sharpness
            && quality.sharpness < options.minStillSharpnessVariance {
            throw DNIError.retry(
                "La imagen recortada no alcanzó la nitidez mínima.",
                .waitingForFocus
            )
        }

        let recognition: DNIRecognition
        if options.profile == .dni {
            recognition = try recognizer.recognize(raster: corrected)
        } else {
            recognition = .unknown
        }
        let fileURL = directory.appendingPathComponent("dni-\(UUID().uuidString.lowercased()).jpg")
        guard let jpeg = UIImage(cgImage: correctedCG).jpegData(compressionQuality: 0.95) else {
            throw DNIError.processingFailed("No se pudo codificar el JPEG recortado.", false)
        }
        try jpeg.write(to: fileURL, options: .atomic)
        return DNIProcessedScan(
            fileURL: fileURL,
            rawWidth: rawDimensions.width,
            rawHeight: rawDimensions.height,
            effectiveDocumentLongEdge: max(1, effectiveDocumentLongEdge),
            width: corrected.width,
            height: corrected.height,
            detection: detection,
            quality: quality,
            recognition: recognition
        )
    }

    private struct TrackMatch {
        let rmsDistance: CGFloat
        let maximumCornerDistance: CGFloat
        let areaRatio: CGFloat
        let aspectChange: CGFloat

        var isTight: Bool {
            rmsDistance <= 0.012
                && maximumCornerDistance <= 0.020
                && areaRatio >= 0.92 && areaRatio <= 1.08
                && aspectChange <= 0.08
        }

        var isBounded: Bool {
            rmsDistance <= 0.030
                && maximumCornerDistance <= 0.045
                && areaRatio >= 0.84 && areaRatio <= 1.18
                && aspectChange <= 0.16
        }
    }

    private func comparePreviewTrack(
        prior: DNIQuad,
        detected: DNIQuad,
        width: Int,
        height: Int
    ) -> TrackMatch {
        let diagonal = max(1, hypot(CGFloat(width), CGFloat(height)))
        let distances = zip(prior.points, detected.points).map {
            $0.0.distance(to: $0.1) / diagonal
        }
        let rms = sqrt(distances.reduce(0) { $0 + $1 * $1 } / 4)
        let maximum = distances.max() ?? 1
        let areaRatio = detected.area / max(1, prior.area)
        let priorAspect = (prior.topWidth + prior.bottomWidth) * 0.5
            / max(1, (prior.leftHeight + prior.rightHeight) * 0.5)
        let detectedAspect = (detected.topWidth + detected.bottomWidth) * 0.5
            / max(1, (detected.leftHeight + detected.rightHeight) * 0.5)
        let aspectChange = abs(detectedAspect - priorAspect) / max(0.01, priorAspect)
        return TrackMatch(
            rmsDistance: rms,
            maximumCornerDistance: maximum,
            areaRatio: areaRatio,
            aspectChange: aspectChange
        )
    }

    private func blend(
        _ previous: DNIQuad,
        _ current: DNIQuad,
        currentWeight: CGFloat
    ) -> DNIQuad {
        func point(_ first: CGPoint, _ second: CGPoint) -> CGPoint {
            CGPoint(
                x: first.x + (second.x - first.x) * currentWeight,
                y: first.y + (second.y - first.y) * currentWeight
            )
        }
        return DNIQuad(
            topLeft: point(previous.topLeft, current.topLeft),
            topRight: point(previous.topRight, current.topRight),
            bottomRight: point(previous.bottomRight, current.bottomRight),
            bottomLeft: point(previous.bottomLeft, current.bottomLeft)
        )
    }

    private func detectionForQuad(
        _ quad: DNIQuad,
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

    private func outputOnlyChecks(_ checks: DNIChecks) -> DNIChecks {
        return try! DNIChecks(dictionary: [
            "documentSize": false,
            "centering": false,
            "perspective": false,
            "stability": false,
            "sharpness": checks.sharpness,
            "brightness": checks.brightness,
            "glare": checks.glare,
            "shadows": checks.shadows,
            // Occlusion was already evaluated on the uncropped photograph above.
            "edgeOcclusion": false
        ])
    }
}
