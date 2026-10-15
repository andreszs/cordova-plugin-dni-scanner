import CoreGraphics
import Foundation

struct DNIGeometricDetection {
    let points: [CGPoint]
    let score: CGFloat
    let areaFraction: CGFloat
    let centerOffset: CGFloat
    let observedAspect: CGFloat
}

/// Geometric fallback equivalent to Android's Sobel/Hough document detector.
/// It intentionally knows nothing about DNI contents or layouts.
final class DNIGeometricDocumentDetector {
    private let expectedAspect: CGFloat = 85.60 / 53.98
    private let minimumObservedAspect: CGFloat = 1.18
    private let maximumObservedAspect: CGFloat = 2.25
    private let minimumDocumentArea: CGFloat = 0.18
    private let maximumDocumentArea: CGFloat = 0.82
    private let minimumDetectionScore: CGFloat = 0.38

    private let angleBinCount = 90
    private let maximumLineCount = 40
    private let maximumPairCount = 48
    private let rhoSuppression = 12
    private let angleSuppression = 2
    private let edgeSampleStep = 2
    private let minimumEdgeThreshold = 72
    private let edgeThresholdStandardDeviationFactor = 1.10

    func detect(
        width: Int,
        height: Int,
        luma: [UInt8],
        permittedRegion: CGRect,
        targetAreaFraction: CGFloat
    ) -> DNIGeometricDetection? {
        guard width >= 40, height >= 40, luma.count >= width * height else { return nil }

        let gradients = computeGradients(width: width, height: height, luma: luma)
        guard gradients.edgeThreshold > 0 else { return nil }

        let diagonal = Int(ceil(hypot(Double(width), Double(height))))
        let rhoCount = diagonal * 2 + 1
        var accumulator = [Int32](repeating: 0, count: angleBinCount * rhoCount)
        let cosines = (0..<angleBinCount).map { cos(Double.pi * Double($0) / Double(angleBinCount)) }
        let sines = (0..<angleBinCount).map { sin(Double.pi * Double($0) / Double(angleBinCount)) }

        for y in stride(from: 1, to: height - 1, by: edgeSampleStep) {
            for x in stride(from: 1, to: width - 1, by: edgeSampleStep) {
                let index = y * width + x
                let magnitude = Int(gradients.magnitudes[index])
                guard magnitude >= gradients.edgeThreshold else { continue }
                var normal = atan2(Double(gradients.gy[index]), Double(gradients.gx[index]))
                if normal < 0 { normal += Double.pi }
                if normal >= Double.pi { normal -= Double.pi }
                let centerBin = Int((normal * Double(angleBinCount) / Double.pi).rounded()) % angleBinCount
                let weight = min(384, magnitude)
                for delta in -1...1 {
                    let bin = (centerBin + delta + angleBinCount) % angleBinCount
                    let rho = Int((Double(x) * cosines[bin] + Double(y) * sines[bin]).rounded()) + diagonal
                    guard rho >= 0, rho < rhoCount else { continue }
                    accumulator[bin * rhoCount + rho] += Int32(delta == 0 ? weight : weight / 2)
                }
            }
        }

        let lines = strongestLines(
            accumulator: accumulator,
            diagonal: diagonal,
            rhoCount: rhoCount,
            cosines: cosines,
            sines: sines
        )
        guard lines.count >= 4 else { return nil }
        let pairs = parallelPairs(lines: lines, width: width, height: height)
        guard pairs.count >= 2 else { return nil }

        let maximumLineScore = CGFloat(max(1, lines[0].score))
        var best: DNIGeometricDetection?
        for firstIndex in pairs.indices {
            for secondIndex in pairs.indices where secondIndex > firstIndex {
                let first = pairs[firstIndex]
                let second = pairs[secondIndex]
                let normalDifference = angleDifference(first.averageTheta, second.averageTheta)
                guard normalDifference >= 58 * .pi / 180,
                      normalDifference <= 122 * .pi / 180,
                      let points = intersections(first: first, second: second),
                      isInsideFrame(points, width: width, height: height, marginFraction: 0.08),
                      isInsidePermittedRegion(points, width: width, height: height, region: permittedRegion)
                else { continue }

                let areaFraction = polygonArea(points) / CGFloat(width * height)
                guard areaFraction >= minimumDocumentArea * 0.55,
                      areaFraction <= min(0.96, maximumDocumentArea * 1.18)
                else { continue }

                let averageWidth = (distance(points[0], points[1]) + distance(points[3], points[2])) * 0.5
                let averageHeight = (distance(points[0], points[3]) + distance(points[1], points[2])) * 0.5
                guard averageHeight >= 1 else { continue }
                let observedAspect = averageWidth / averageHeight
                guard observedAspect >= minimumObservedAspect,
                      observedAspect <= maximumObservedAspect else { continue }

                let edgeSupportScore = edgeSupport(
                    points: points,
                    gradients: gradients,
                    width: width,
                    height: height
                )
                let aspectScore = clamp01(1 - abs(observedAspect - expectedAspect) / 0.75)
                let areaScore = clamp01(
                    1 - abs(areaFraction - targetAreaFraction) / max(0.15, targetAreaFraction)
                )
                let center = polygonCenter(points)
                let dx = (center.x - CGFloat(width) * 0.5) / (CGFloat(width) * 0.5)
                let dy = (center.y - CGFloat(height) * 0.5) / (CGFloat(height) * 0.5)
                let centerOffset = hypot(dx, dy)
                let centerScore = clamp01(1 - centerOffset)
                let lineScore = clamp01(CGFloat(first.score + second.score) / (4 * maximumLineScore))
                let score = 0.48 * edgeSupportScore
                    + 0.17 * aspectScore
                    + 0.13 * areaScore
                    + 0.10 * centerScore
                    + 0.12 * lineScore
                let candidate = DNIGeometricDetection(
                    points: points,
                    score: score,
                    areaFraction: areaFraction,
                    centerOffset: centerOffset,
                    observedAspect: observedAspect
                )
                if best == nil || candidate.score > best!.score { best = candidate }
            }
        }
        guard let best, best.score >= minimumDetectionScore else { return nil }
        return best
    }

    private struct GradientField {
        let magnitudes: [Int16]
        let gx: [Int16]
        let gy: [Int16]
        let edgeThreshold: Int
    }

    private struct Line {
        let theta: Double
        let rho: Double
        let score: Int
        let cosine: Double
        let sine: Double
    }

    private struct LinePair {
        let first: Line
        let second: Line
        var score: Int { first.score + second.score }
        var averageTheta: Double {
            let x = cos(first.theta * 2) + cos(second.theta * 2)
            let y = sin(first.theta * 2) + sin(second.theta * 2)
            let value = atan2(y, x) * 0.5
            return value < 0 ? value + .pi : value
        }
    }

    private func computeGradients(width: Int, height: Int, luma: [UInt8]) -> GradientField {
        let count = width * height
        var magnitudes = [Int16](repeating: 0, count: count)
        var gxValues = [Int16](repeating: 0, count: count)
        var gyValues = [Int16](repeating: 0, count: count)
        var sum = 0.0
        var sumSquares = 0.0
        var sampleCount = 0

        for y in 1..<(height - 1) {
            for x in 1..<(width - 1) {
                let topLeft = Int(luma[(y - 1) * width + x - 1])
                let top = Int(luma[(y - 1) * width + x])
                let topRight = Int(luma[(y - 1) * width + x + 1])
                let left = Int(luma[y * width + x - 1])
                let right = Int(luma[y * width + x + 1])
                let bottomLeft = Int(luma[(y + 1) * width + x - 1])
                let bottom = Int(luma[(y + 1) * width + x])
                let bottomRight = Int(luma[(y + 1) * width + x + 1])
                let gx = -topLeft - 2 * left - bottomLeft + topRight + 2 * right + bottomRight
                let gy = -topLeft - 2 * top - topRight + bottomLeft + 2 * bottom + bottomRight
                let magnitude = min(Int(Int16.max), abs(gx) + abs(gy))
                let index = y * width + x
                magnitudes[index] = Int16(magnitude)
                gxValues[index] = Int16(clamping: gx)
                gyValues[index] = Int16(clamping: gy)
                if x & 3 == 0, y & 3 == 0 {
                    sum += Double(magnitude)
                    sumSquares += Double(magnitude * magnitude)
                    sampleCount += 1
                }
            }
        }
        let mean = sampleCount == 0 ? 0 : sum / Double(sampleCount)
        let variance = sampleCount == 0 ? 0 : max(0, sumSquares / Double(sampleCount) - mean * mean)
        let threshold = Int(max(
            Double(minimumEdgeThreshold),
            mean + edgeThresholdStandardDeviationFactor * sqrt(variance)
        ))
        return GradientField(
            magnitudes: magnitudes,
            gx: gxValues,
            gy: gyValues,
            edgeThreshold: threshold
        )
    }

    private func strongestLines(
        accumulator: [Int32],
        diagonal: Int,
        rhoCount: Int,
        cosines: [Double],
        sines: [Double]
    ) -> [Line] {
        var mutable = accumulator
        var result: [Line] = []
        for _ in 0..<maximumLineCount {
            guard let bestIndex = mutable.indices.max(by: { mutable[$0] < mutable[$1] }),
                  mutable[bestIndex] > 0 else { break }
            let bestAngle = bestIndex / rhoCount
            let bestRho = bestIndex % rhoCount
            let theta = Double.pi * Double(bestAngle) / Double(angleBinCount)
            result.append(Line(
                theta: theta,
                rho: Double(bestRho - diagonal),
                score: Int(mutable[bestIndex]),
                cosine: cosines[bestAngle],
                sine: sines[bestAngle]
            ))
            for angleDelta in -angleSuppression...angleSuppression {
                let angle = (bestAngle + angleDelta + angleBinCount) % angleBinCount
                let lowerRho = max(0, bestRho - rhoSuppression)
                let upperRho = min(rhoCount - 1, bestRho + rhoSuppression)
                for rho in lowerRho...upperRho { mutable[angle * rhoCount + rho] = 0 }
            }
        }
        return result.sorted { $0.score > $1.score }
    }

    private func parallelPairs(lines: [Line], width: Int, height: Int) -> [LinePair] {
        let minimumSeparation = Double(min(width, height)) * 0.20
        let maximumSeparation = hypot(Double(width), Double(height)) * 0.92
        var result: [LinePair] = []
        for firstIndex in lines.indices {
            for secondIndex in lines.indices where secondIndex > firstIndex {
                let first = lines[firstIndex]
                let second = lines[secondIndex]
                guard angleDifference(first.theta, second.theta) <= 12 * .pi / 180 else { continue }
                let separation = abs(first.rho - second.rho)
                guard separation >= minimumSeparation, separation <= maximumSeparation else { continue }
                result.append(LinePair(first: first, second: second))
            }
        }
        return Array(result.sorted { $0.score > $1.score }.prefix(maximumPairCount))
    }

    private func intersections(first: LinePair, second: LinePair) -> [CGPoint]? {
        let raw = [
            intersection(first.first, second.first),
            intersection(first.first, second.second),
            intersection(first.second, second.first),
            intersection(first.second, second.second)
        ]
        guard raw.allSatisfy({ $0 != nil }) else { return nil }
        let center = polygonCenter(raw.compactMap { $0 })
        let sorted = raw.compactMap { $0 }.sorted {
            atan2($0.y - center.y, $0.x - center.x) < atan2($1.y - center.y, $1.x - center.x)
        }
        guard let topLeftIndex = sorted.indices.min(by: {
            sorted[$0].x + sorted[$0].y < sorted[$1].x + sorted[$1].y
        }) else { return nil }
        var ordered = (0..<4).map { sorted[(topLeftIndex + $0) % 4] }
        if signedCross(ordered[0], ordered[1], ordered[2]) < 0 {
            ordered = [ordered[0], ordered[3], ordered[2], ordered[1]]
        }
        return isConvex(ordered) ? ordered : nil
    }

    private func intersection(_ first: Line, _ second: Line) -> CGPoint? {
        let determinant = first.cosine * second.sine - second.cosine * first.sine
        guard abs(determinant) >= 0.00001 else { return nil }
        return CGPoint(
            x: (first.rho * second.sine - second.rho * first.sine) / determinant,
            y: (second.rho * first.cosine - first.rho * second.cosine) / determinant
        )
    }

    private func edgeSupport(
        points: [CGPoint],
        gradients: GradientField,
        width: Int,
        height: Int
    ) -> CGFloat {
        var supported = 0
        var total = 0
        for side in 0..<4 {
            let start = points[side]
            let end = points[(side + 1) % 4]
            let sampleCount = max(20, min(100, Int(distance(start, end)) / 3))
            for sample in 0...sampleCount {
                let fraction = CGFloat(sample) / CGFloat(sampleCount)
                let x = Int((start.x + (end.x - start.x) * fraction).rounded())
                let y = Int((start.y + (end.y - start.y) * fraction).rounded())
                var localMaximum = 0
                for offsetY in -2...2 {
                    for offsetX in -2...2 {
                        let px = x + offsetX
                        let py = y + offsetY
                        if px > 0, px < width - 1, py > 0, py < height - 1 {
                            localMaximum = max(localMaximum, Int(gradients.magnitudes[py * width + px]))
                        }
                    }
                }
                if localMaximum >= gradients.edgeThreshold { supported += 1 }
                total += 1
            }
        }
        return total == 0 ? 0 : CGFloat(supported) / CGFloat(total)
    }

    private func isInsideFrame(
        _ points: [CGPoint],
        width: Int,
        height: Int,
        marginFraction: CGFloat
    ) -> Bool {
        let marginX = CGFloat(width) * marginFraction
        let marginY = CGFloat(height) * marginFraction
        return points.allSatisfy {
            $0.x >= -marginX && $0.y >= -marginY
                && $0.x <= CGFloat(width) + marginX && $0.y <= CGFloat(height) + marginY
        }
    }

    private func isInsidePermittedRegion(
        _ points: [CGPoint],
        width: Int,
        height: Int,
        region: CGRect
    ) -> Bool {
        return points.allSatisfy {
            let normalized = CGPoint(x: $0.x / CGFloat(width), y: $0.y / CGFloat(height))
            return normalized.x >= region.minX && normalized.x <= region.maxX
                && normalized.y >= region.minY && normalized.y <= region.maxY
        }
    }

    private func isConvex(_ points: [CGPoint]) -> Bool {
        var sign: CGFloat = 0
        for index in 0..<4 {
            let cross = signedCross(points[index], points[(index + 1) % 4], points[(index + 2) % 4])
            guard abs(cross) >= 0.001 else { return false }
            if index == 0 { sign = cross.sign == .minus ? -1 : 1 }
            else if (cross.sign == .minus ? -1 : 1) != sign { return false }
        }
        return true
    }

    private func polygonArea(_ points: [CGPoint]) -> CGFloat {
        var sum: CGFloat = 0
        for index in points.indices {
            let next = points[(index + 1) % points.count]
            sum += points[index].x * next.y - next.x * points[index].y
        }
        return abs(sum) * 0.5
    }

    private func polygonCenter(_ points: [CGPoint]) -> CGPoint {
        let sum = points.reduce(CGPoint.zero) { CGPoint(x: $0.x + $1.x, y: $0.y + $1.y) }
        return CGPoint(x: sum.x / CGFloat(points.count), y: sum.y / CGFloat(points.count))
    }

    private func signedCross(_ first: CGPoint, _ second: CGPoint, _ third: CGPoint) -> CGFloat {
        return (second.x - first.x) * (third.y - first.y)
            - (second.y - first.y) * (third.x - first.x)
    }

    private func distance(_ first: CGPoint, _ second: CGPoint) -> CGFloat {
        return hypot(first.x - second.x, first.y - second.y)
    }

    private func angleDifference(_ first: Double, _ second: Double) -> Double {
        let difference = abs(first - second).truncatingRemainder(dividingBy: .pi)
        return difference > .pi / 2 ? .pi - difference : difference
    }

    private func clamp01(_ value: CGFloat) -> CGFloat { max(0, min(1, value)) }
}
