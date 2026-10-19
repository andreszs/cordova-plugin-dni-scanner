import UIKit

final class DNIOverlayView: UIView {
    private(set) var analysis: DNIAnalysis?
    private var message = "Buscando DNI"
    private var instruction = ""
    private var debugMode = false
    private var scanOrientation = "portrait"
    private var checks: DNIChecks

    override class var layerClass: AnyClass { CAShapeLayer.self }

    init(instruction: String, debugMode: Bool, orientation: String, checks: DNIChecks) {
        self.instruction = instruction
        self.debugMode = debugMode
        self.scanOrientation = orientation
        self.checks = checks
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
        let maximumHeight = area.height * (usesLandscapeLayout ? 0.58 : 0.46)
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

        if let current = analysis, let detection = current.detection {
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
                top: max(safeAreaInsets.top + 12, guide.minY - height - 14),
                maxWidth: max(120, guide.width - 24),
                font: font,
                color: color == colorForState(.searching) ? color : .white,
                background: UIColor.black.withAlphaComponent(0.65),
                horizontalPadding: 28,
                verticalPadding: 16
            )
        }

        let messageFont = UIFont.systemFont(ofSize: 18, weight: .semibold)
        let messageHeight = ceil(messageFont.lineHeight) + 20
        drawInlineText(
            message,
            top: min(
                bounds.height - safeAreaInsets.bottom - messageHeight - 8,
                guide.maxY + 18
            ),
            maxWidth: max(120, guide.width - 32),
            font: messageFont,
            color: color,
            background: UIColor.black.withAlphaComponent(0.80),
            horizontalPadding: 32,
            verticalPadding: 20
        )

        if debugMode {
            drawDebugPanel(guide: guide)
        }
    }

    private struct DebugRow {
        let text: String
        let acceptable: Bool
        let enabled: Bool
    }

    private func debugRows() -> [DebugRow] {
        guard let current = analysis,
              let detection = current.detection,
              let quality = current.quality else {
            return [
                DebugRow(text: "documento --", acceptable: false, enabled: true),
                DebugRow(text: "estado buscando", acceptable: false, enabled: true),
                motionRow(analysis?.motion)
            ]
        }

        let exposureAcceptable = quality.brightness >= DNIEngine.minimumBrightness
            && quality.brightness <= DNIEngine.maximumBrightness
            && quality.darkClipping <= DNIEngine.maximumDarkClipping
            && quality.highlightClipping <= DNIEngine.maximumHighlightClipping
        let shadowAcceptable = quality.illuminationSpread <= DNIEngine.maximumIlluminationSpread
            || quality.shadowBoundary <= DNIEngine.maximumShadowBoundary
        let borderAcceptable = quality.edgeGap <= DNIEngine.maximumEdgeObstruction
            && quality.edgeObstruction <= DNIEngine.maximumEdgeObstruction
        let skinAcceptable = quality.skinEdgeObstruction <= DNIEngine.maximumEdgeObstruction
        let visuallyStable = current.stableFrameCount >= DNIEngine.requiredStableFrames
            && current.stableDurationMs >= DNIEngine.requiredStableDurationMs

        return [
            DebugRow(text: "detector \(detection.source)", acceptable: true, enabled: false),
            DebugRow(
                text: String(format: "area %.3f", detection.areaFraction),
                acceptable: detection.areaFraction >= DNIEngine.minimumDocumentArea
                    && detection.areaFraction <= DNIEngine.maximumDocumentArea,
                enabled: checks.documentSize
            ),
            DebugRow(
                text: String(format: "centro %.3f", detection.centerOffset),
                acceptable: detection.centerOffset <= DNIEngine.maximumCenterOffset,
                enabled: checks.centering
            ),
            DebugRow(
                text: String(format: "persp %.3f", quality.perspective),
                acceptable: quality.perspective >= DNIEngine.minimumPerspectiveScore,
                enabled: checks.perspective
            ),
            DebugRow(
                text: String(format: "foco %.0f", quality.sharpness),
                acceptable: quality.sharpness >= DNIEngine.minimumSharpness,
                enabled: checks.sharpness
            ),
            DebugRow(
                text: String(
                    format: "luz %.0f d%.2f c%.2f",
                    quality.brightness,
                    quality.darkClipping,
                    quality.highlightClipping
                ),
                acceptable: exposureAcceptable,
                enabled: checks.brightness
            ),
            DebugRow(
                text: String(format: "glare %.3f", quality.glare),
                acceptable: quality.glare <= DNIEngine.maximumGlare,
                enabled: checks.glare
            ),
            DebugRow(
                text: String(
                    format: "sombra %.3f/%.3f",
                    quality.illuminationSpread,
                    quality.shadowBoundary
                ),
                acceptable: shadowAcceptable,
                enabled: checks.shadows
            ),
            DebugRow(
                text: String(
                    format: "borde gap %.2f warm %.2f",
                    quality.edgeGap,
                    quality.edgeObstruction
                ),
                acceptable: borderAcceptable,
                enabled: checks.edgeOcclusion
            ),
            DebugRow(
                text: String(format: "piel cruza %.3f", quality.skinEdgeObstruction),
                acceptable: skinAcceptable,
                enabled: checks.edgeOcclusion
            ),
            DebugRow(
                text: String(
                    format: "visual %df %dms",
                    current.stableFrameCount,
                    current.stableDurationMs
                ),
                acceptable: visuallyStable,
                enabled: checks.stability
            ),
            motionRow(current.motion)
        ]
    }

    private func motionRow(_ motion: DNIMotionSnapshot?) -> DebugRow {
        guard let motion = motion else {
            return DebugRow(text: "movimiento --", acceptable: false, enabled: false)
        }
        let gyroscope = motion.gyroscopeRadPerSecond.map { String(format: "%.3f", $0) } ?? "--"
        let acceleration = motion.linearAcceleration.map { String(format: "%.3f", $0) } ?? "--"
        return DebugRow(
            text: "mov g\(gyroscope) a\(acceleration) \(motion.quietDurationMs)ms",
            acceptable: motion.stable,
            enabled: checks.stability && motion.sensorAvailable && motion.sampling
        )
    }

    private func drawDebugPanel(guide: CGRect) {
        let rows = debugRows()
        let landscape = bounds.width > bounds.height
        let columns = landscape ? 1 : 2
        let rowsPerColumn = Int(ceil(Double(rows.count) / Double(columns)))
        let font = UIFont.monospacedSystemFont(ofSize: 11, weight: .medium)
        let lineHeight: CGFloat = 14
        let padding: CGFloat = 7
        let panelWidth: CGFloat
        let left: CGFloat
        let top: CGFloat
        if landscape {
            let availableLeft = max(120, guide.minX - 22)
            panelWidth = min(210, availableLeft - 10)
            left = max(8, guide.minX - panelWidth - 12)
            top = max(70, guide.minY)
        } else {
            panelWidth = min(guide.width, bounds.width - 24)
            left = (bounds.width - panelWidth) * 0.5
            top = min(
                bounds.height - CGFloat(rowsPerColumn) * lineHeight - padding * 2 - 8,
                guide.maxY + 92
            )
        }
        let panelHeight = CGFloat(rowsPerColumn) * lineHeight + padding * 2
        let panel = CGRect(x: left, y: top, width: panelWidth, height: panelHeight)
        UIColor.black.withAlphaComponent(0.72).setFill()
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

    private func mapToView(_ point: CGPoint, imageWidth: Int, imageHeight: Int) -> CGPoint {
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
        case .ready, .capturing:
            return UIColor(red: 0.31, green: 0.95, blue: 0.45, alpha: 1)
        case .holdStill, .waitingForFocus:
            return UIColor(red: 1, green: 0.82, blue: 0.25, alpha: 1)
        default:
            return UIColor(red: 1, green: 0.35, blue: 0.35, alpha: 1)
        }
    }
}
