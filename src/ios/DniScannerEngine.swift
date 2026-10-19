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

    func raster(from cgImage: CGImage, width: Int, height: Int) throws -> DNIRaster {
        let source = CIImage(cgImage: cgImage)
        let scaleX = CGFloat(width) / source.extent.width
        let scaleY = CGFloat(height) / source.extent.height
        let scaled = source.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        return try renderExact(scaled, width: width, height: height)
    }

    func correctedImage(from source: DNIRaster, quad: DNIQuad, maxLongEdge: Int) throws -> CGImage {
        let sourceCG = try source.cgImage()
        let image = CIImage(cgImage: sourceCG)
        guard let filter = CIFilter(name: "CIPerspectiveCorrection") else {
            throw DNIError.processingFailed("Core Image no ofrece corrección de perspectiva.", false)
        }

        func ciPoint(_ point: CGPoint) -> CIVector {
            return CIVector(x: point.x, y: CGFloat(source.height) - point.y)
        }
        filter.setValue(image, forKey: kCIInputImageKey)
        filter.setValue(ciPoint(quad.topLeft), forKey: "inputTopLeft")
        filter.setValue(ciPoint(quad.topRight), forKey: "inputTopRight")
        filter.setValue(ciPoint(quad.bottomRight), forKey: "inputBottomRight")
        filter.setValue(ciPoint(quad.bottomLeft), forKey: "inputBottomLeft")

        guard let corrected = filter.outputImage, corrected.extent.width > 1, corrected.extent.height > 1 else {
            throw DNIError.processingFailed("No se pudo calcular la homografía del documento.", false)
        }

        let expectedAspect: CGFloat = 85.60 / 53.98
        let naturalWidth = max(
            (quad.topWidth + quad.bottomWidth) * 0.5,
            (quad.leftHeight + quad.rightHeight) * 0.5 * expectedAspect
        )
        let targetWidth = max(640, min(maxLongEdge, Int(naturalWidth.rounded())))
        let targetHeight = max(1, Int((CGFloat(targetWidth) / expectedAspect).rounded()))
        let translated = corrected.transformed(
            by: CGAffineTransform(translationX: -corrected.extent.origin.x, y: -corrected.extent.origin.y)
        )
        let scaled = translated.transformed(by: CGAffineTransform(
            scaleX: CGFloat(targetWidth) / corrected.extent.width,
            y: CGFloat(targetHeight) / corrected.extent.height
        ))
        let targetRect = CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight)
        guard let output = context.createCGImage(scaled, from: targetRect) else {
            throw DNIError.processingFailed("No se pudo generar el JPEG corregido.", false)
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
    static let minimumObservedAspect: CGFloat = 1.18
    static let maximumObservedAspect: CGFloat = 2.25
    static let minimumDocumentArea: CGFloat = 0.16
    static let maximumDocumentArea: CGFloat = 0.86
    static let maximumCenterOffset: CGFloat = 0.25
    static let minimumPerspectiveScore = 0.56
    static let minimumBrightness = 48.0
    static let maximumBrightness = 225.0
    static let maximumDarkClipping = 0.34
    static let maximumHighlightClipping = 0.48
    static let maximumGlare = 0.14
    static let maximumIlluminationSpread = 0.31
    static let maximumShadowBoundary = 0.08
    static let maximumEdgeObstruction = 0.28
    static let guideMarginPercent: CGFloat = 3
    static let stillDetectionMarginPercent: CGFloat = 8
    static let minimumSharpness = 70.0
    // Permissive diagnostics for downstream OCR. They do not gate capture.
    static let minimumOCRSharpness = 55.0
    static let minimumOCRBrightness = 35.0
    static let maximumOCRBrightness = 238.0
    static let maximumOCRDarkClipping = 0.50
    static let maximumOCRHighlightClipping = 0.60
    static let maximumOCRGlare = 0.28
    static let requiredStableFrames = 3
    static let requiredStableDurationMs = 500
    private let imageFactory = DNIImageFactory.shared
    private let geometricDetector = DNIGeometricDocumentDetector()

    static func maximumGlare(for profile: DNIScanProfile) -> Double {
        return profile == .genericCard ? 0.23 : maximumGlare
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
        guideMarginPercent: CGFloat
    ) throws -> DNIDetection? {
        let request = VNDetectRectanglesRequest()
        // Vision is the fast primary detector. Its preliminary filters are kept
        // deliberately broader than the scanner's own geometric validation so
        // faint outer borders are not displaced by rectangular content inside a DNI.
        request.maximumObservations = 16
        request.minimumConfidence = 0.30
        request.minimumAspectRatio = 0.35
        request.maximumAspectRatio = 0.90
        request.minimumSize = 0.10
        request.quadratureTolerance = 45

        let handler = VNImageRequestHandler(cgImage: try raster.cgImage(), orientation: .up)
        try handler.perform([request])
        let observations = request.results ?? []
        let margin = min(allowedRegion.width, allowedRegion.height) * guideMarginPercent / 100
        let permitted = allowedRegion.insetBy(dx: -margin, dy: -margin)
        let relaxedMinimumArea = Self.minimumDocumentArea * 0.55
        let relaxedMaximumArea = min(0.96, Self.maximumDocumentArea * 1.18)
        let targetArea = allowedRegion.width > 0.90 && allowedRegion.height > 0.90
            ? CGFloat(0.47)
            : max(0.01, min(0.99, allowedRegion.width * allowedRegion.height))

        var selected: DNIDetection?
        for observation in observations {
            let normalized = DNIQuad(
                topLeft: CGPoint(x: observation.topLeft.x, y: 1 - observation.topLeft.y),
                topRight: CGPoint(x: observation.topRight.x, y: 1 - observation.topRight.y),
                bottomRight: CGPoint(x: observation.bottomRight.x, y: 1 - observation.bottomRight.y),
                bottomLeft: CGPoint(x: observation.bottomLeft.x, y: 1 - observation.bottomLeft.y)
            )
            guard normalized.points.allSatisfy({ permitted.contains($0) }) else { continue }
            let quad = normalized.scaled(x: CGFloat(raster.width), y: CGFloat(raster.height))
            let areaFraction = quad.area / CGFloat(raster.width * raster.height)
            guard areaFraction >= relaxedMinimumArea,
                  areaFraction <= relaxedMaximumArea else { continue }
            let center = normalized.center
            let centerOffset = hypot(center.x - 0.5, center.y - 0.5) / hypot(0.5, 0.5)
            let width = (quad.topWidth + quad.bottomWidth) * 0.5
            let height = (quad.leftHeight + quad.rightHeight) * 0.5
            guard height > 1 else { continue }
            let observedAspect = width / height
            guard observedAspect >= Self.minimumObservedAspect,
                  observedAspect <= Self.maximumObservedAspect else { continue }
            let aspectScore = max(0, 1 - abs(observedAspect - Self.expectedAspect) / 0.75)
            let areaScore = max(0, 1 - abs(areaFraction - targetArea) / max(0.15, targetArea))
            let centerScore = max(0, 1 - centerOffset)
            let score = CGFloat(observation.confidence) * 0.45
                + aspectScore * 0.25
                + areaScore * 0.17
                + centerScore * 0.13
            let candidate = DNIDetection(
                quad: quad,
                score: score,
                areaFraction: areaFraction,
                centerOffset: centerOffset,
                observedAspect: observedAspect,
                source: "vision"
            )
            if selected == nil || candidate.score > selected!.score {
                selected = candidate
            }
        }
        if let selected { return selected }

        // Match Android when Vision cannot find the outer card: use the same
        // content-agnostic Sobel/Hough geometry, constrained to the visible guide.
        guard let fallback = geometricDetector.detect(
            width: raster.width,
            height: raster.height,
            luma: raster.luma,
            permittedRegion: permitted,
            targetAreaFraction: targetArea
        ) else { return nil }
        let quad = DNIQuad(
            topLeft: fallback.points[0],
            topRight: fallback.points[1],
            bottomRight: fallback.points[2],
            bottomLeft: fallback.points[3]
        )
        return DNIDetection(
            quad: quad,
            score: fallback.score,
            areaFraction: fallback.areaFraction,
            centerOffset: fallback.centerOffset,
            observedAspect: fallback.observedAspect,
            source: "hough"
        )
    }

    func evaluate(raster: DNIRaster, quad: DNIQuad) -> DNIQuality {
        let bounds = boundingRect(for: quad, width: raster.width, height: raster.height)
        var count = 0
        var sum = 0.0
        var dark = 0
        var clipped = 0
        var glare = 0
        var laplacianSum = 0.0
        var laplacianSquaredSum = 0.0
        var laplacianCount = 0

        let step = max(1, Int(max(bounds.width, bounds.height) / 360))
        if bounds.width >= 3 && bounds.height >= 3 {
            for y in stride(from: Int(bounds.minY) + 1, to: Int(bounds.maxY) - 1, by: step) {
                for x in stride(from: Int(bounds.minX) + 1, to: Int(bounds.maxX) - 1, by: step) {
                    let point = CGPoint(x: x, y: y)
                    guard contains(quad: quad, point: point) else { continue }
                    let value = raster.lumaAt(x: x, y: y)
                    count += 1
                    sum += Double(value)
                    if value <= 22 { dark += 1 }
                    if value >= 235 { clipped += 1 }
                    let rgb = raster.rgbAt(x: x, y: y)
                    let colorfulness = max(rgb.0, max(rgb.1, rgb.2)) - min(rgb.0, min(rgb.1, rgb.2))
                    if value >= 238 && colorfulness <= 34 { glare += 1 }

                    let laplacian = 4 * value
                        - raster.lumaAt(x: x - 1, y: y)
                        - raster.lumaAt(x: x + 1, y: y)
                        - raster.lumaAt(x: x, y: y - 1)
                        - raster.lumaAt(x: x, y: y + 1)
                    let lap = Double(laplacian)
                    laplacianSum += lap
                    laplacianSquaredSum += lap * lap
                    laplacianCount += 1
                }
            }
        }

        let safeCount = max(1, count)
        let meanLap = laplacianSum / Double(max(1, laplacianCount))
        let sharpness = max(0, laplacianSquaredSum / Double(max(1, laplacianCount)) - meanLap * meanLap)
        let illumination = illuminationMetrics(raster: raster, quad: quad)
        let edges = edgeMetrics(raster: raster, quad: quad)
        return DNIQuality(
            sharpness: sharpness,
            brightness: sum / Double(safeCount),
            darkClipping: Double(dark) / Double(safeCount),
            highlightClipping: Double(clipped) / Double(safeCount),
            glare: Double(glare) / Double(safeCount),
            perspective: perspectiveScore(quad),
            edgeGap: edges.gap,
            edgeObstruction: edges.warm,
            skinEdgeObstruction: edges.skin,
            illuminationSpread: illumination.spread,
            shadowBoundary: illumination.boundary
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
        if checks.perspective && quality.perspective < Self.minimumPerspectiveScore { return .adjustPerspective }
        if checks.brightness && (quality.brightness < Self.minimumBrightness || quality.darkClipping > Self.maximumDarkClipping) {
            return .improveLighting
        }
        if checks.brightness && (quality.brightness > Self.maximumBrightness || quality.highlightClipping > Self.maximumHighlightClipping) {
            return .reduceLighting
        }
        if checks.glare && quality.glare > Self.maximumGlare(for: profile) { return .avoidGlare }
        if checks.shadows
            && quality.illuminationSpread > Self.maximumIlluminationSpread(for: profile)
            && quality.shadowBoundary > Self.maximumShadowBoundary(for: profile) {
            return .unevenLighting
        }
        let edgeObstruction = profile == .genericCard
            ? quality.edgeGap
            : max(quality.edgeGap, max(quality.edgeObstruction, quality.skinEdgeObstruction))
        if checks.edgeOcclusion && edgeObstruction > Self.maximumEdgeObstruction {
            return .edgeOccluded
        }
        if checks.sharpness && quality.sharpness < Self.minimumSharpness { return .waitingForFocus }
        return .ready
    }

    private func boundingRect(for quad: DNIQuad, width: Int, height: Int) -> CGRect {
        let xs = quad.points.map(\.x)
        let ys = quad.points.map(\.y)
        let left = max(0, floor(xs.min() ?? 0))
        let top = max(0, floor(ys.min() ?? 0))
        let right = min(CGFloat(width - 1), ceil(xs.max() ?? CGFloat(width - 1)))
        let bottom = min(CGFloat(height - 1), ceil(ys.max() ?? CGFloat(height - 1)))
        return CGRect(x: left, y: top, width: max(1, right - left), height: max(1, bottom - top))
    }

    private func contains(quad: DNIQuad, point: CGPoint) -> Bool {
        let points = quad.points
        var sign: CGFloat = 0
        for index in points.indices {
            let first = points[index]
            let second = points[(index + 1) % points.count]
            let cross = (second.x - first.x) * (point.y - first.y)
                - (second.y - first.y) * (point.x - first.x)
            if abs(cross) < 0.001 { continue }
            if sign == 0 { sign = cross }
            if cross * sign < 0 { return false }
        }
        return true
    }

    private func perspectiveScore(_ quad: DNIQuad) -> Double {
        func ratio(_ first: CGFloat, _ second: CGFloat) -> CGFloat {
            return min(first, second) / max(1, max(first, second))
        }
        let widthScore = ratio(quad.topWidth, quad.bottomWidth)
        let heightScore = ratio(quad.leftHeight, quad.rightHeight)
        let diagonalScore = ratio(quad.topLeft.distance(to: quad.bottomRight), quad.topRight.distance(to: quad.bottomLeft))
        return Double(max(0, min(1, widthScore * 0.4 + heightScore * 0.4 + diagonalScore * 0.2)))
    }

    private func illuminationMetrics(raster: DNIRaster, quad: DNIQuad) -> (spread: Double, boundary: Double) {
        let columns = 6
        let rows = 4
        var tiles = [Double](repeating: 0, count: columns * rows)
        for row in 0..<rows {
            for column in 0..<columns {
                var values: [Int] = []
                for sampleY in 1...5 {
                    for sampleX in 1...5 {
                        let u = (CGFloat(column) + CGFloat(sampleX) / 6) / CGFloat(columns)
                        let v = (CGFloat(row) + CGFloat(sampleY) / 6) / CGFloat(rows)
                        let point = bilinearPoint(quad: quad, u: u, v: v)
                        values.append(raster.lumaAt(x: Int(point.x), y: Int(point.y)))
                    }
                }
                values.sort()
                tiles[row * columns + column] = Double(values[values.count / 2])
            }
        }
        let sorted = tiles.sorted()
        let low = sorted[Int(Double(sorted.count - 1) * 0.2)]
        let high = sorted[Int(Double(sorted.count - 1) * 0.8)]
        let spread = (high - low) / 255
        var differences: [Double] = []
        for row in 0..<rows {
            for column in 0..<columns {
                let current = tiles[row * columns + column]
                if column + 1 < columns {
                    differences.append(abs(current - tiles[row * columns + column + 1]) / 255)
                }
                if row + 1 < rows {
                    differences.append(abs(current - tiles[(row + 1) * columns + column]) / 255)
                }
            }
        }
        return (spread, differences.max() ?? 0)
    }

    private func edgeMetrics(raster: DNIRaster, quad: DNIQuad) -> (gap: Double, warm: Double, skin: Double) {
        let center = quad.center
        var lowContrast: [Bool] = []
        var warmFlags: [Bool] = []
        var skinFlags: [Bool] = []
        let pairs = [
            (quad.topLeft, quad.topRight),
            (quad.topRight, quad.bottomRight),
            (quad.bottomRight, quad.bottomLeft),
            (quad.bottomLeft, quad.topLeft)
        ]
        for pair in pairs {
            for index in 0..<32 {
                let t = (CGFloat(index) + 0.5) / 32
                let point = CGPoint(
                    x: pair.0.x + (pair.1.x - pair.0.x) * t,
                    y: pair.0.y + (pair.1.y - pair.0.y) * t
                )
                let dx = center.x - point.x
                let dy = center.y - point.y
                let length = max(1, hypot(dx, dy))
                let inside = CGPoint(x: point.x + dx / length * 5, y: point.y + dy / length * 5)
                let outside = CGPoint(x: point.x - dx / length * 4, y: point.y - dy / length * 4)
                let contrast = abs(
                    raster.lumaAt(x: Int(inside.x), y: Int(inside.y))
                        - raster.lumaAt(x: Int(outside.x), y: Int(outside.y))
                )
                lowContrast.append(contrast < 14)
                let rgb = raster.rgbAt(x: Int(point.x), y: Int(point.y))
                let warmth = rgb.0 - (rgb.1 + rgb.2) / 2
                let maxChannel = max(rgb.0, max(rgb.1, rgb.2))
                let minChannel = min(rgb.0, min(rgb.1, rgb.2))
                let plausibleSkin = rgb.0 > 55 && rgb.1 > 30 && rgb.2 > 20
                    && maxChannel - minChannel >= 16
                    && rgb.0 >= rgb.1 + 6 && rgb.0 >= rgb.2 + 10
                warmFlags.append(warmth > 52)
                skinFlags.append(plausibleSkin && contrast < 20)
            }
        }
        return (
            longestRun(lowContrast, divisor: 32),
            longestRun(warmFlags, divisor: 32),
            longestRun(skinFlags, divisor: 32)
        )
    }

    private func longestRun(_ values: [Bool], divisor: Int) -> Double {
        var longest = 0
        var current = 0
        for value in values {
            current = value ? current + 1 : 0
            longest = max(longest, current)
        }
        return min(1, Double(longest) / Double(divisor))
    }

    private func bilinearPoint(quad: DNIQuad, u: CGFloat, v: CGFloat) -> CGPoint {
        let top = CGPoint(
            x: quad.topLeft.x + (quad.topRight.x - quad.topLeft.x) * u,
            y: quad.topLeft.y + (quad.topRight.y - quad.topLeft.y) * u
        )
        let bottom = CGPoint(
            x: quad.bottomLeft.x + (quad.bottomRight.x - quad.bottomLeft.x) * u,
            y: quad.bottomLeft.y + (quad.bottomRight.y - quad.bottomLeft.y) * u
        )
        return CGPoint(x: top.x + (bottom.x - top.x) * v, y: top.y + (bottom.y - top.y) * v)
    }
}

final class DNIStillProcessor {
    private let engine = DNIEngine()
    private let factory = DNIImageFactory.shared
    private let recognizer = DNISideVariantRecognizer()

    func process(data: Data, options: DNIOptions, directory: URL) throws -> DNIProcessedScan {
        let source = try factory.raster(from: data, maxDimension: max(options.maxOutputLongEdge, 1280))
        let permitted = CGRect(x: 0.015, y: 0.015, width: 0.97, height: 0.97)
        guard let detection = try engine.detect(
            raster: source,
            allowedRegion: permitted,
            guideMarginPercent: DNIEngine.stillDetectionMarginPercent
        ) else {
            throw DNIError.retry("No se pudieron confirmar los cuatro bordes en la fotografía.", .searching)
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

        let correctedCG = try factory.correctedImage(
            from: source,
            quad: detection.quad,
            maxLongEdge: options.maxOutputLongEdge
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
            observedAspect: DNIEngine.expectedAspect
        )
        let outputState = engine.classify(
            detection: outputDetection,
            quality: quality,
            checks: outputOnlyChecks(options.checks),
            profile: options.profile
        )
        if outputState != .ready {
            throw DNIError.retry("La imagen corregida no alcanzó la calidad mínima.", outputState)
        }

        let recognition: DNIRecognition
        if options.profile == .dni {
            recognition = try recognizer.recognize(raster: corrected)
        } else {
            recognition = .unknown
        }
        let fileURL = directory.appendingPathComponent("dni-\(UUID().uuidString.lowercased()).jpg")
        guard let jpeg = UIImage(cgImage: correctedCG).jpegData(compressionQuality: 0.95) else {
            throw DNIError.processingFailed("No se pudo codificar el JPEG corregido.", false)
        }
        try jpeg.write(to: fileURL, options: .atomic)
        return DNIProcessedScan(
            fileURL: fileURL,
            width: corrected.width,
            height: corrected.height,
            detection: detection,
            quality: quality,
            recognition: recognition
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
            // The corrected image intentionally fills the output canvas, so its
            // perimeter has no outside background to compare. Occlusion was already
            // evaluated on the uncropped photograph above.
            "edgeOcclusion": false
        ])
    }
}
