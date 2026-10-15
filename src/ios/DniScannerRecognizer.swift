import Foundation

final class DNISideVariantRecognizer {
    private struct Prototype {
        let side: String
        let variant: Int
        let edges: [Double]
        let saturation: [Double]
    }

    private struct Features {
        let edges: [Double]
        let saturation: [Double]
        let mrz: Double
        let squareRegion: Double
        let wideRegion: Double
        let localizedLowerRight: Double
        let photoLeft: Double
        let photoRight: Double
        let monochromeRight: Double
        let rightDense: Double
    }

    private struct Candidate {
        let side: String
        let variant: Int
        let score: Double
        let templateScore: Double
    }

    // Coarse 4x3 signatures ported from Android. They contain no document pixels or text.
    private let prototypes: [Prototype] = [
        Prototype(side: "front", variant: 1,
            edges: [0.349,0.370,0.202,0.202,0.545,0.251,0.259,0.277,0.410,0.481,0.454,0.365],
            saturation: [0.090,0.138,0.142,0.059,0.119,0.120,0.164,0.081,0.040,0.048,0.065,0.047]),
        Prototype(side: "front", variant: 1,
            edges: [0.235,0.229,0.184,0.197,0.374,0.265,0.262,0.244,0.303,0.328,0.298,0.204],
            saturation: [0.276,0.245,0.333,0.365,0.281,0.256,0.270,0.289,0.328,0.328,0.323,0.360]),
        Prototype(side: "front", variant: 1,
            edges: [0.246,0.190,0.131,0.095,0.408,0.228,0.173,0.143,0.242,0.331,0.304,0.149],
            saturation: [0.050,0.041,0.081,0.085,0.056,0.077,0.163,0.163,0.109,0.114,0.116,0.147]),
        Prototype(side: "back", variant: 1,
            edges: [0.373,0.359,0.166,0.263,0.250,0.175,0.154,0.289,0.337,0.334,0.659,0.543],
            saturation: [0.196,0.130,0.083,0.045,0.420,0.276,0.087,0.052,0.080,0.157,0.157,0.125]),
        Prototype(side: "back", variant: 1,
            edges: [0.519,0.455,0.245,0.465,0.478,0.314,0.244,0.448,0.347,0.358,0.649,0.523],
            saturation: [0.217,0.144,0.093,0.064,0.429,0.281,0.102,0.079,0.092,0.188,0.228,0.201]),
        Prototype(side: "back", variant: 1,
            edges: [0.305,0.261,0.112,0.120,0.270,0.216,0.151,0.169,0.320,0.322,0.628,0.427],
            saturation: [0.238,0.203,0.201,0.291,0.196,0.165,0.183,0.266,0.288,0.289,0.379,0.377]),
        Prototype(side: "front", variant: 2,
            edges: [0.263,0.304,0.150,0.130,0.228,0.367,0.339,0.180,0.314,0.481,0.486,0.347],
            saturation: [0.094,0.092,0.088,0.066,0.173,0.170,0.116,0.078,0.159,0.240,0.105,0.051]),
        Prototype(side: "front", variant: 2,
            edges: [0.217,0.304,0.092,0.095,0.127,0.308,0.278,0.140,0.267,0.403,0.415,0.359],
            saturation: [0.312,0.278,0.237,0.260,0.461,0.358,0.256,0.276,0.354,0.296,0.320,0.352]),
        Prototype(side: "front", variant: 2,
            edges: [0.128,0.209,0.095,0.080,0.139,0.229,0.219,0.095,0.192,0.262,0.347,0.324],
            saturation: [0.036,0.040,0.030,0.025,0.191,0.177,0.034,0.031,0.091,0.143,0.028,0.035]),
        Prototype(side: "front", variant: 2,
            edges: [0.404,0.446,0.375,0.295,0.337,0.356,0.490,0.324,0.415,0.496,0.538,0.546],
            saturation: [0.060,0.083,0.073,0.053,0.112,0.283,0.091,0.044,0.107,0.262,0.150,0.142]),
        Prototype(side: "back", variant: 2,
            edges: [0.304,0.357,0.289,0.198,0.161,0.155,0.281,0.210,0.356,0.392,0.362,0.301],
            saturation: [0.129,0.154,0.132,0.000,0.116,0.133,0.095,0.001,0.047,0.051,0.043,0.036]),
        Prototype(side: "back", variant: 2,
            edges: [0.383,0.313,0.138,0.164,0.326,0.337,0.192,0.075,0.282,0.301,0.245,0.178],
            saturation: [0.171,0.169,0.206,0.300,0.190,0.195,0.183,0.255,0.290,0.282,0.271,0.282]),
        Prototype(side: "back", variant: 2,
            edges: [0.360,0.329,0.186,0.212,0.315,0.339,0.227,0.098,0.246,0.252,0.200,0.153],
            saturation: [0.313,0.287,0.323,0.424,0.279,0.264,0.300,0.378,0.339,0.293,0.306,0.368]),
        Prototype(side: "back", variant: 2,
            edges: [0.155,0.177,0.144,0.073,0.141,0.158,0.240,0.064,0.318,0.324,0.301,0.259],
            saturation: [0.060,0.087,0.121,0.141,0.095,0.145,0.137,0.144,0.151,0.174,0.175,0.180]),
        Prototype(side: "back", variant: 2,
            edges: [0.256,0.259,0.154,0.098,0.222,0.237,0.254,0.082,0.280,0.300,0.286,0.218],
            saturation: [0.117,0.174,0.086,0.060,0.168,0.243,0.146,0.066,0.048,0.061,0.066,0.071]),
        Prototype(side: "back", variant: 2,
            edges: [0.118,0.106,0.139,0.173,0.097,0.130,0.104,0.114,0.187,0.202,0.172,0.181],
            saturation: [0.121,0.150,0.081,0.132,0.098,0.159,0.102,0.100,0.116,0.119,0.114,0.116]),
        Prototype(side: "front", variant: 3,
            edges: [0.209,0.232,0.192,0.174,0.158,0.334,0.233,0.144,0.144,0.199,0.167,0.121],
            saturation: [0.184,0.215,0.209,0.223,0.185,0.185,0.174,0.247,0.226,0.236,0.281,0.163]),
        Prototype(side: "front", variant: 3,
            edges: [0.439,0.538,0.467,0.495,0.440,0.571,0.431,0.374,0.431,0.471,0.506,0.355],
            saturation: [0.154,0.217,0.229,0.182,0.123,0.172,0.207,0.181,0.199,0.202,0.183,0.109]),
        Prototype(side: "back", variant: 3,
            edges: [0.133,0.282,0.211,0.223,0.173,0.155,0.173,0.520,0.278,0.403,0.356,0.281],
            saturation: [0.168,0.142,0.172,0.166,0.226,0.247,0.270,0.149,0.233,0.278,0.286,0.265]),
        Prototype(side: "back", variant: 3,
            edges: [0.341,0.473,0.400,0.475,0.438,0.379,0.453,0.691,0.471,0.502,0.472,0.431],
            saturation: [0.076,0.066,0.066,0.063,0.121,0.104,0.119,0.047,0.139,0.141,0.140,0.138])
    ]

    func recognize(raster: DNIRaster) throws -> DNIRecognition {
        let scaled = try DNIImageFactory.shared.raster(from: raster.cgImage(), width: 256, height: 160)
        let features = makeFeatures(scaled)
        let all = candidates(features).sorted { $0.score > $1.score }
        guard let best = all.first else {
            return DNIRecognition(side: "unknown", variant: nil, sideConfidence: 0, variantConfidence: 0, signals: [])
        }
        let otherSide = all.filter { $0.side != best.side }.map(\.score).max() ?? 0
        let otherVariant = all.filter { $0.variant != best.variant }.map(\.score).max() ?? 0
        let sideConfidence = confidence(score: best.score, margin: best.score - otherSide)
        let variantConfidence = confidence(score: best.score, margin: best.score - otherVariant)
        let side = sideConfidence >= 0.76 ? best.side : "unknown"
        let variant: Int? = side == "unknown" || variantConfidence < 0.72 ? nil : best.variant
        var signals: [String] = []
        if best.templateScore > 0.55 { signals.append("layout_signature") }
        if features.mrz > 0.50 { signals.append("machine_readable_rows") }
        if features.squareRegion > 0.50 { signals.append("square_dense_region") }
        if features.wideRegion > 0.50 { signals.append("wide_dense_region") }
        if features.localizedLowerRight > 0.50 { signals.append("localized_lower_right_dense_region") }
        return DNIRecognition(
            side: side,
            variant: variant,
            sideConfidence: sideConfidence,
            variantConfidence: variantConfidence,
            signals: signals
        )
    }

    private func candidates(_ features: Features) -> [Candidate] {
        var values: [Candidate] = []
        for variant in 1...3 {
            values.append(score(side: "front", variant: variant, features: features))
            values.append(score(side: "back", variant: variant, features: features))
        }
        return values
    }

    private func score(side: String, variant: Int, features: Features) -> Candidate {
        let matching = prototypes.filter { $0.side == side && $0.variant == variant }
        let templateScore = matching.map { similarity(features, $0) }.max() ?? 0
        let ruleScore: Double
        if variant == 3 && side == "back" {
            ruleScore = 0.50 * features.squareRegion + 0.35 * features.mrz + 0.15 * features.rightDense
        } else if variant == 3 {
            ruleScore = 0.55 * features.photoLeft + 0.25 * (1 - features.mrz) + 0.20 * (1 - features.squareRegion)
        } else if variant == 2 && side == "front" {
            ruleScore = 0.36 * features.wideRegion + 0.30 * features.photoLeft
                + 0.24 * features.localizedLowerRight + 0.10 * (1 - features.mrz)
        } else if variant == 2 {
            ruleScore = 0.50 * features.mrz + 0.27 * features.monochromeRight
                + 0.13 * (1 - features.wideRegion) + 0.10 * (1 - features.localizedLowerRight)
        } else if side == "front" {
            ruleScore = 0.52 * features.mrz + 0.33 * features.photoRight + 0.15 * (1 - features.wideRegion)
        } else {
            ruleScore = 0.52 * features.wideRegion + 0.33 * features.monochromeRight + 0.15 * (1 - features.mrz)
        }
        return Candidate(
            side: side,
            variant: variant,
            score: clamp(0.88 * templateScore + 0.12 * ruleScore),
            templateScore: templateScore
        )
    }

    private func makeFeatures(_ raster: DNIRaster) -> Features {
        var luma = [Double](repeating: 0, count: raster.width * raster.height)
        var saturation = [Double](repeating: 0, count: luma.count)
        for pixel in 0..<luma.count {
            let offset = pixel * 4
            let red = Int(raster.rgba[offset])
            let green = Int(raster.rgba[offset + 1])
            let blue = Int(raster.rgba[offset + 2])
            luma[pixel] = Double(77 * red + 150 * green + 29 * blue) / 256
            let maximum = max(red, max(green, blue))
            let minimum = min(red, min(green, blue))
            saturation[pixel] = maximum == 0 ? 0 : Double(maximum - minimum) / Double(maximum)
        }

        var edges = [Double](repeating: 0, count: 12)
        var saturationGrid = [Double](repeating: 0, count: 12)
        var counts = [Int](repeating: 0, count: 12)
        for y in 1..<raster.height {
            for x in 1..<raster.width {
                let index = y * raster.width + x
                let gradient = min(1,
                    (abs(luma[index] - luma[index - 1])
                        + abs(luma[index] - luma[index - raster.width])) / 80)
                let cellX = min(3, x * 4 / raster.width)
                let cellY = min(2, y * 3 / raster.height)
                let cell = cellY * 4 + cellX
                edges[cell] += gradient
                saturationGrid[cell] += saturation[index]
                counts[cell] += 1
            }
        }
        for index in 0..<12 where counts[index] > 0 {
            edges[index] /= Double(counts[index])
            saturationGrid[index] /= Double(counts[index])
        }

        let bottom = average(edges, 8, 12)
        let middle = average(edges, 4, 8)
        let rightMiddle = edges[7]
        let surroundingRight = (edges[3] + edges[6] + edges[11]) / 3
        let lowerRight = (edges[10] + edges[11]) * 0.5
        let lowerLeft = (edges[8] + edges[9]) * 0.5
        let leftSaturation = (saturationGrid[0] + saturationGrid[4] + saturationGrid[8]) / 3
        let rightSaturation = (saturationGrid[3] + saturationGrid[7] + saturationGrid[11]) / 3
        let mrz = clamp((bottom - 0.18) / 0.30 + 0.25 * (bottom - middle))
        return Features(
            edges: edges,
            saturation: saturationGrid,
            mrz: mrz,
            squareRegion: clamp((rightMiddle - 0.30) / 0.35 + max(0, rightMiddle - surroundingRight)),
            wideRegion: clamp((lowerRight - 0.30) / 0.32),
            localizedLowerRight: clamp((lowerRight - lowerLeft - 0.03) / 0.18 + max(0, lowerRight - 0.28) / 0.25),
            photoLeft: clamp(0.45 + leftSaturation * 1.8 + (edges[4] - edges[7]) * 0.6),
            photoRight: clamp(0.45 + rightSaturation * 1.8 + (edges[7] - edges[4]) * 0.6),
            monochromeRight: clamp(0.55 + rightMiddle - rightSaturation * 2.0),
            rightDense: clamp((rightMiddle + edges[11]) * 0.9)
        )
    }

    private func similarity(_ features: Features, _ prototype: Prototype) -> Double {
        var edgeDistance = 0.0
        var normalizedEdgeDistance = 0.0
        var saturationDistance = 0.0
        let featureScale = max(0.035, average(features.edges, 0, features.edges.count))
        let prototypeScale = max(0.035, average(prototype.edges, 0, prototype.edges.count))
        for index in 0..<12 {
            edgeDistance += abs(features.edges[index] - prototype.edges[index])
            normalizedEdgeDistance += abs(features.edges[index] / featureScale - prototype.edges[index] / prototypeScale)
            saturationDistance += abs(features.saturation[index] - prototype.saturation[index])
        }
        edgeDistance /= 12
        normalizedEdgeDistance /= 12
        saturationDistance /= 12
        let absoluteScore = clamp(1 - (0.72 * edgeDistance + 0.28 * saturationDistance) / 0.34)
        let layoutScore = clamp(1 - (0.82 * normalizedEdgeDistance + 0.18 * saturationDistance) / 0.70)
        return 0.38 * absoluteScore + 0.62 * layoutScore
    }

    private func confidence(score: Double, margin: Double) -> Double {
        return clamp(0.34 + score * 0.48 + max(0, margin) * 1.15)
    }

    private func average(_ values: [Double], _ start: Int, _ end: Int) -> Double {
        guard end > start else { return 0 }
        return values[start..<end].reduce(0, +) / Double(end - start)
    }

    private func clamp(_ value: Double) -> Double {
        return max(0, min(1, value))
    }
}
