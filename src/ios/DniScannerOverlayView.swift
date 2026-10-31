import UIKit

final class DNIOverlayView: UIView {
    private static let progressSegments = 5
    private static let progressTransitionDuration: CFTimeInterval = 0.26
    private static let portraitElementGap: CGFloat = 18
    private static let progressHeight: CGFloat = 8
    private(set) var analysis: DNIAnalysis?
    private var message = "Buscando DNI"
    private var instruction = ""
    private var debugMode = false
    private var scanOrientation = "portrait"
    private var checks: DNIChecks
    private let profile: DNIScanProfile
    private var progressStartColors: [UIColor] = []
    private var progressTargetColors: [UIColor] = []
    private var progressTransitionStartedAt: [CFTimeInterval] = []

    override class var layerClass: AnyClass { CAShapeLayer.self }

    init(
        instruction: String,
        debugMode: Bool,
        orientation: String,
        checks: DNIChecks,
        profile: DNIScanProfile
    ) {
        self.instruction = instruction
        self.debugMode = debugMode
        self.scanOrientation = orientation
        self.checks = checks
        self.profile = profile
        super.init(frame: .zero)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(_ analysis: DNIAnalysis?, message: String) {
        self.analysis = analysis
        self.message = message
        setNeedsDisplay()
    }

    func guideRect(in bounds: CGRect? = nil) -> CGRect {
        let area = bounds ?? self.bounds
        let horizontalMargin: CGFloat = 20
        var width = max(1, area.width - horizontalMargin * 2)
        var height = width / DNIEngine.expectedAspect
        let usesLandscapeLayout = scanOrientation == "landscape"
            || (scanOrientation == "auto" && area.width > area.height)
        let maximumHeight = area.height * (usesLandscapeLayout ? 0.80 : 0.46)
        if height > maximumHeight {
            height = maximumHeight
            width = height * DNIEngine.expectedAspect
        }
        return CGRect(
            x: area.midX - width / 2,
            y: area.midY - height / 2,
            width: width,
            height: height
        )
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let guide = guideRect()
        let usesLandscapeLayout = scanOrientation == "landscape"
            || (scanOrientation == "auto" && bounds.width > bounds.height)

        context.saveGState()
        context.setFillColor(UIColor.black.withAlphaComponent(0.52).cgColor)
        let shade = UIBezierPath(rect: bounds)
        shade.append(UIBezierPath(roundedRect: guide, cornerRadius: 12))
        shade.usesEvenOddFillRule = true
        shade.fill()
        context.restoreGState()

        let color = colorForState(analysis?.state ?? .searching)
        let guidePath = UIBezierPath(roundedRect: guide, cornerRadius: 12)
        guidePath.setLineDash([10, 8], count: 2, phase: 0)
        guidePath.lineWidth = 3
        color.setStroke()
        guidePath.stroke()

        if debugMode, let current = analysis, let detection = current.detection {
            let mapped = detection.quad.points.map {
                mapToView($0, imageWidth: current.imageWidth, imageHeight: current.imageHeight)
            }
            if mapped.count == 4 {
                let path = UIBezierPath()
                path.move(to: mapped[0])
                for point in mapped.dropFirst() { path.addLine(to: point) }
                path.close()
                path.lineWidth = 2
                UIColor(white: 0.86, alpha: 0.9).setStroke()
                path.stroke()
            }
        }

        if !instruction.isEmpty {
            let font = UIFont.systemFont(ofSize: 17, weight: .semibold)
            let height = ceil(font.lineHeight) + 16
            drawInlineText(
                instruction,
                top: usesLandscapeLayout
                    ? guide.minY + 12
                    : max(
                        safeAreaInsets.top + 12,
                        guide.minY - height - Self.portraitElementGap
                    ),
                maxWidth: max(120, guide.width - 24),
                font: font,
                color: color == colorForState(.searching) ? color : .white,
                background: UIColor.black.withAlphaComponent(0.65),
                horizontalPadding: 28,
                verticalPadding: 16
            )
        }

        drawProgress(guide: guide, usesLandscapeLayout: usesLandscapeLayout)

        let messageFont = UIFont.systemFont(ofSize: 18, weight: .semibold)
        let messageHeight = ceil(messageFont.lineHeight) + 20
        drawInlineText(
            message,
            top: usesLandscapeLayout
                ? guide.maxY - messageHeight - 12
                : min(
                    bounds.height - safeAreaInsets.bottom - messageHeight - 8,
                    guide.maxY
                        + Self.portraitElementGap
                        + Self.progressHeight
                        + Self.portraitElementGap
                ),
            maxWidth: max(120, guide.width - 32),
            font: messageFont,
            color: color,
            background: UIColor.black.withAlphaComponent(0.80),
            horizontalPadding: 32,
            verticalPadding: 20
        )

        if debugMode {
            drawDebugPanel()
        }
    }

    private func drawProgress(guide: CGRect, usesLandscapeLayout: Bool) {
        let completed = progressCompleted()
        let green = UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        let intermediate = UIColor(red: 0.64, green: 0.84, blue: 0.29, alpha: 1)
        let yellow = UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        let future = UIColor.white.withAlphaComponent(0.40)
        var targets = (0..<Self.progressSegments).map { index in
            index < completed || completed == Self.progressSegments
                ? green : index == completed ? yellow : future
        }
        if analysis?.state == .capturing || analysis?.state == .processing {
            targets[Self.progressSegments - 1] = intermediate
        }

        let now = CACurrentMediaTime()
        ensureProgressAnimation(targets, now: now)
        let totalWidth = min(guide.width * 0.72, 320)
        let gap: CGFloat = 6
        let segmentWidth = (totalWidth - gap * CGFloat(Self.progressSegments - 1))
            / CGFloat(Self.progressSegments)
        let height = Self.progressHeight
        let centerY = usesLandscapeLayout
            ? guide.maxY - 64
            : guide.maxY + Self.portraitElementGap + height / 2
        var x = bounds.midX - totalWidth / 2
        var animating = false
        for index in 0..<Self.progressSegments {
            let color = animatedProgressColor(index, now: now)
            color.setFill()
            UIBezierPath(
                roundedRect: CGRect(
                    x: x,
                    y: centerY - height / 2,
                    width: segmentWidth,
                    height: height
                ),
                cornerRadius: height / 2
            ).fill()
            animating = animating || !color.isEqual(progressTargetColors[index])
            x += segmentWidth + gap
        }
        if animating { setNeedsDisplay() }
    }

    private func progressCompleted() -> Int {
        guard let current = analysis else { return 0 }
        switch current.state {
        case .searching, .retrying:
            return 0
        case .moveCloser, .moveAway, .centerDocument, .adjustPerspective:
            return current.detection == nil ? 0 : 1
        case .improveLighting, .reduceLighting, .avoidGlare, .unevenLighting,
             .edgeOccluded, .waitingForFocus:
            return current.detection == nil ? 0 : 2
        case .holdStill:
            return 3
        case .ready, .focusing, .capturing, .processing:
            return 4
        case .success:
            return Self.progressSegments
        }
    }

    private func ensureProgressAnimation(_ targets: [UIColor], now: CFTimeInterval) {
        guard progressTargetColors.count == targets.count else {
            progressStartColors = targets
            progressTargetColors = targets
            progressTransitionStartedAt = Array(repeating: now, count: targets.count)
            return
        }
        for index in targets.indices where !progressTargetColors[index].isEqual(targets[index]) {
            progressStartColors[index] = animatedProgressColor(index, now: now)
            progressTargetColors[index] = targets[index]
            progressTransitionStartedAt[index] = now
        }
    }

    private func animatedProgressColor(_ index: Int, now: CFTimeInterval) -> UIColor {
        var amount = CGFloat(min(1, max(0,
            (now - progressTransitionStartedAt[index]) / Self.progressTransitionDuration)))
        amount = amount * amount * (3 - 2 * amount)
        return blendColor(progressStartColors[index], progressTargetColors[index], amount: amount)
    }

    private func blendColor(_ from: UIColor, _ to: UIColor, amount: CGFloat) -> UIColor {
        var fromR: CGFloat = 0, fromG: CGFloat = 0, fromB: CGFloat = 0, fromA: CGFloat = 0
        var toR: CGFloat = 0, toG: CGFloat = 0, toB: CGFloat = 0, toA: CGFloat = 0
        from.getRed(&fromR, green: &fromG, blue: &fromB, alpha: &fromA)
        to.getRed(&toR, green: &toG, blue: &toB, alpha: &toA)
        return UIColor(
            red: fromR + (toR - fromR) * amount,
            green: fromG + (toG - fromG) * amount,
            blue: fromB + (toB - fromB) * amount,
            alpha: fromA + (toA - fromA) * amount
        )
    }

    private struct DebugRow {
        let text: String
        let acceptable: Bool
        let enabled: Bool
    }

    private func debugRows() -> [DebugRow] {
        var rows = [
            DebugRow(
                text: analysis.map { "frame: \($0.imageWidth)x\($0.imageHeight)" } ?? "frame: -",
                acceptable: true,
                enabled: false
            ),
            DebugRow(text: "profile: \(profile.rawValue)", acceptable: true, enabled: false),
            DebugRow(
                text: "state: \(analysis?.state.rawValue ?? "searching")",
                acceptable: analysis?.state == .ready,
                enabled: true
            )
        ]
        guard let current = analysis,
              let detection = current.detection,
              let quality = current.quality else {
            rows.append(contentsOf: [
                DebugRow(text: "source: -", acceptable: false, enabled: false),
                DebugRow(text: "score/aspect: -", acceptable: false, enabled: false),
                DebugRow(text: "area/center: -", acceptable: false, enabled: checks.documentSize),
                DebugRow(text: "perspective: -", acceptable: false, enabled: true),
                DebugRow(text: "focus: -", acceptable: false, enabled: checks.sharpness),
                DebugRow(text: "light: -", acceptable: false, enabled: checks.brightness),
                DebugRow(text: "glare: -", acceptable: false, enabled: checks.glare),
                DebugRow(text: "shadow: -", acceptable: false, enabled: checks.shadows),
                DebugRow(text: "edge: -", acceptable: false, enabled: checks.edgeOcclusion),
                DebugRow(
                    text: "skin: -",
                    acceptable: false,
                    enabled: checks.edgeOcclusion && profile == .dni
                ),
                DebugRow(text: "hold: -", acceptable: false, enabled: true)
            ])
            rows.append(motionRow(analysis?.motion))
            return rows
        }

        let exposureAcceptable = quality.brightness >= DNIEngine.minimumBrightness
            && quality.darkClipping <= DNIEngine.maximumDarkClipping
            && !DNIEngine.isOverexposed(quality)
        let shadowAcceptable = quality.illuminationSpread
                <= DNIEngine.maximumIlluminationSpread(for: profile)
            || quality.shadowBoundary <= DNIEngine.maximumShadowBoundary(for: profile)
        let borderAcceptable = quality.edgeGap <= DNIEngine.maximumEdgeObstruction
            && (profile == .genericCard
                || quality.edgeObstruction <= DNIEngine.maximumEdgeObstruction)
        let skinAcceptable = quality.skinEdgeObstruction <= DNIEngine.maximumEdgeObstruction
        let visuallyStable = current.stableFrameCount >= DNIEngine.requiredStableFrames
            && current.stableDurationMs >= DNIEngine.requiredStableDurationMs

        rows.append(contentsOf: [
            DebugRow(text: "source: \(detection.source)", acceptable: true, enabled: false),
            DebugRow(
                text: String(format: "score/aspect: %.3f/%.3f", detection.score, detection.observedAspect),
                acceptable: detection.observedAspect >= DNIEngine.minimumObservedAspect,
                enabled: true
            ),
            DebugRow(
                text: String(
                    format: "area/center: %.3f/%.3f",
                    detection.areaFraction,
                    detection.centerOffset
                ),
                acceptable: detection.areaFraction >= DNIEngine.minimumDocumentArea
                    && detection.areaFraction <= DNIEngine.maximumDocumentArea
                    && detection.centerOffset <= DNIEngine.maximumCenterOffset,
                enabled: checks.documentSize || checks.centering
            ),
            DebugRow(
                text: String(
                    format: "geometry: p%.2f r%.2f deg",
                    detection.quad.maximumPerspectiveDeviationDegrees,
                    detection.quad.maximumInPlaneRotationDegrees
                ),
                acceptable: detection.quad.hasAcceptablePerspective(
                    freeLimit: DNIEngine.maximumRectangleDeviationDegrees,
                    symmetricLimit: DNIEngine.maximumSymmetricPerspectiveDeviationDegrees,
                    symmetryTolerance: DNIEngine.maximumTrapezoidSymmetryErrorDegrees
                ) && detection.quad.maximumInPlaneRotationDegrees
                    <= DNIEngine.maximumInPlaneRotationDegrees,
                enabled: true
            ),
            DebugRow(
                text: String(format: "focus: %.0f", quality.sharpness),
                acceptable: quality.sharpness >= DNIEngine.minimumSharpness,
                enabled: checks.sharpness
            ),
            DebugRow(
                text: String(
                    format: "light: %.0f d%.2f c%.2f",
                    quality.brightness,
                    quality.darkClipping,
                    quality.highlightClipping
                ),
                acceptable: exposureAcceptable,
                enabled: checks.brightness
            ),
            DebugRow(
                text: String(format: "glare: %.3f", quality.glare),
                acceptable: quality.glare <= DNIEngine.maximumGlare(for: profile),
                enabled: checks.glare
            ),
            DebugRow(
                text: String(
                    format: "shadow: %.3f/%.3f",
                    quality.illuminationSpread,
                    quality.shadowBoundary
                ),
                acceptable: shadowAcceptable,
                enabled: checks.shadows
            ),
            DebugRow(
                text: String(
                    format: "edge: %.2f warm %.2f",
                    quality.edgeGap,
                    quality.edgeObstruction
                ),
                acceptable: borderAcceptable,
                enabled: checks.edgeOcclusion
            ),
            DebugRow(
                text: String(format: "skin: %.3f", quality.skinEdgeObstruction),
                acceptable: profile == .genericCard || skinAcceptable,
                enabled: checks.edgeOcclusion && profile == .dni
            ),
            DebugRow(
                text: String(
                    format: "hold: %df/%dms",
                    current.stableFrameCount,
                    current.stableDurationMs
                ),
                acceptable: visuallyStable,
                enabled: checks.stability
            ),
            motionRow(current.motion)
        ])
        return rows
    }

    private func motionRow(_ motion: DNIMotionSnapshot?) -> DebugRow {
        guard let motion = motion else {
            return DebugRow(text: "motion: -", acceptable: false, enabled: checks.stability)
        }
        let gyroscope = motion.gyroscopeRadPerSecond.map { String(format: "%.3f", $0) } ?? "--"
        let acceleration = motion.linearAcceleration.map { String(format: "%.3f", $0) } ?? "--"
        return DebugRow(
            text: "motion: g\(gyroscope) a\(acceleration) \(motion.quietDurationMs)ms",
            acceptable: motion.stable,
            enabled: checks.stability && motion.sensorAvailable && motion.sampling
        )
    }

    private func drawDebugPanel() {
        // Diagnostics are an absolute overlay and intentionally ignore the
        // guide, progress indicator, and status-label layout.
        let rows = debugRows()
        let landscape = bounds.width > bounds.height
        let columns = 2
        let rowsPerColumn = Int(ceil(Double(rows.count) / Double(columns)))
        let font = UIFont.monospacedSystemFont(ofSize: 9, weight: .medium)
        let lineHeight: CGFloat = 12
        let padding: CGFloat = 6
        let panelWidth = min(landscape ? 360 : 310, bounds.width - 16)
        let left: CGFloat = 8
        let panelHeight = CGFloat(rowsPerColumn) * lineHeight + padding * 2
        let top = bounds.height - safeAreaInsets.bottom - panelHeight - 8
        let panel = CGRect(x: left, y: top, width: panelWidth, height: panelHeight)
        UIColor.black.withAlphaComponent(0.36).setFill()
        UIBezierPath(roundedRect: panel, cornerRadius: 7).fill()

        let columnWidth = (panelWidth - padding * 2) / CGFloat(columns)
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingTail
        for (index, row) in rows.enumerated() {
            let column = index / rowsPerColumn
            let line = index % rowsPerColumn
            let color: UIColor
            if !row.enabled {
                color = UIColor(red: 0.67, green: 0.69, blue: 0.71, alpha: 1)
            } else if row.acceptable {
                color = UIColor(red: 0.33, green: 0.90, blue: 0.54, alpha: 1)
            } else {
                color = UIColor(red: 1, green: 0.40, blue: 0.40, alpha: 1)
            }
            let textRect = CGRect(
                x: left + padding + CGFloat(column) * columnWidth,
                y: top + padding + CGFloat(line) * lineHeight,
                width: columnWidth - 4,
                height: lineHeight
            )
            (row.text as NSString).draw(
                with: textRect,
                options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph],
                context: nil
            )
        }
    }

    private func drawInlineText(
        _ text: String,
        top: CGFloat,
        maxWidth: CGFloat,
        font: UIFont,
        color: UIColor,
        background: UIColor,
        horizontalPadding: CGFloat,
        verticalPadding: CGFloat
    ) {
        let measured = (text as NSString).size(withAttributes: [.font: font])
        let width = min(maxWidth, ceil(measured.width) + horizontalPadding)
        let height = ceil(font.lineHeight) + verticalPadding
        let rect = CGRect(
            x: bounds.midX - width / 2,
            y: top,
            width: width,
            height: height
        )
        drawText(
            text,
            in: rect,
            font: font,
            color: color,
            background: background,
            lineBreakMode: .byTruncatingTail
        )
    }

    func mapToView(_ point: CGPoint, imageWidth: Int, imageHeight: Int) -> CGPoint {
        guard imageWidth > 0, imageHeight > 0 else { return .zero }
        let scale = max(bounds.width / CGFloat(imageWidth), bounds.height / CGFloat(imageHeight))
        let offsetX = (bounds.width - CGFloat(imageWidth) * scale) * 0.5
        let offsetY = (bounds.height - CGFloat(imageHeight) * scale) * 0.5
        return CGPoint(x: offsetX + point.x * scale, y: offsetY + point.y * scale)
    }

    private func drawText(
        _ text: String,
        in rect: CGRect,
        font: UIFont,
        color: UIColor,
        background: UIColor,
        alignment: NSTextAlignment = .center,
        lineBreakMode: NSLineBreakMode = .byWordWrapping
    ) {
        let backgroundPath = UIBezierPath(roundedRect: rect, cornerRadius: 9)
        background.setFill()
        backgroundPath.fill()
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = lineBreakMode
        let textRect = rect.insetBy(dx: 10, dy: 8)
        (text as NSString).draw(
            with: textRect,
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph],
            context: nil
        )
    }

    private func colorForState(_ state: DNIAnalysisState) -> UIColor {
        switch state {
        case .holdStill, .waitingForFocus, .ready, .capturing, .processing, .success:
            return UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        case .searching, .retrying:
            return UIColor(red: 0.94, green: 0.27, blue: 0.27, alpha: 1)
        case .focusing:
            return UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        default:
            return UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        }
    }
}
