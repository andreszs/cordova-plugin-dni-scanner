import AVFoundation
import UIKit

protocol DNIViewControllerDelegate: AnyObject {
    func dniScanner(
        _ controller: DNIViewController,
        didFinish scan: DNIProcessedScan,
        flashFired: Bool?,
        motion: DNIMotionSnapshot
    )
    func dniScannerDidCancel(_ controller: DNIViewController)
    func dniScanner(_ controller: DNIViewController, didFail error: DNIError)
}

final class DNIViewController: UIViewController {
    weak var delegate: DNIViewControllerDelegate?

    private let options: DNIOptions
    private let successBeep = DniScannerSuccessBeep()
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.crediser.dniscanner.ios.session")
    private let analysisQueue = DispatchQueue(label: "com.crediser.dniscanner.ios.analysis", qos: .userInitiated)
    private let processingQueue = DispatchQueue(label: "com.crediser.dniscanner.ios.processing", qos: .userInitiated)
    private let tracker = DNIDocumentTracker()
    private let geometryEngine = DNIEngine()
    private let stillProcessor = DNIStillProcessor()
    private let motionMonitor = DNIMotionMonitor()
    private let instructionStateStabilizer = DNIInstructionStateStabilizer(
        confirmationDuration: 0.5,
        lossGraceDuration: 0.3
    )
    private let stateLock = NSLock()
    private let geometryLock = NSLock()

    private var previewLayer: AVCaptureVideoPreviewLayer!
    private var overlayView: DNIOverlayView!
    private var closeButton: UIButton?
    private var flashButton: UIButton?
    private var captureButton: UIButton?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var photoOutput: AVCapturePhotoOutput?
    private var selectedPhotoDimensions: CMVideoDimensions?
    private var camera: AVCaptureDevice?
    private var currentFlashMode: String
    private var lastAnalysisTime: CFTimeInterval = 0
    private var captureInProgress = false
    private var analysisSuspended = false
    private var completed = false
    private var orientationTransitionInProgress = false
    private var captureOrientationState = DNICaptureOrientationState()
    private var latestTrackedAnalysis: DNIAnalysis?
    private var latestAnalysisTime: CFTimeInterval = 0
    private var captureReferenceQuad: DNIQuad?
    private var capturedPreviewAnalysis: DNIAnalysis?
    private var manualCaptureRequested = false
    private var lastWarmFocusTime: CFTimeInterval = 0
    private var timeoutWorkItem: DispatchWorkItem?
    private var previewAspectRatio: CGFloat = 16 / 9
    private var normalizedGuideRegion = CGRect(x: 0.13, y: 0.12, width: 0.74, height: 0.76)
    private var acceptedMotionSnapshot: DNIMotionSnapshot?
    private var focusMeteringSequence = 0
    private var pendingFocusMeteringSequence: Int?
    private let focusMeteringTimeout: TimeInterval = 0.7

    var effectiveFlashMode: String { currentFlashMode }
    var effectiveCaptureOrientation: String {
        stateLock.lock()
        let captured = captureOrientationState.captured
        stateLock.unlock()
        return (captured ?? currentEffectiveOrientation()).rawValue
    }

    init(options: DNIOptions) {
        self.options = options
        self.currentFlashMode = options.flashMode
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalPresentationCapturesStatusBarAppearance = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var shouldAutorotate: Bool { lockedCaptureOrientation == nil }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        if let locked = lockedCaptureOrientation {
            return interfaceOrientationMask(for: locked)
        }
        switch options.orientation {
        case "landscape": return .landscape
        case "auto": return .allButUpsideDown
        default: return .portrait
        }
    }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation {
        switch options.orientation {
        case "landscape": return .landscapeRight
        case "auto": return interfaceOrientation(for: currentEffectiveOrientation())
        default: return .portrait
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)

        overlayView = DNIOverlayView(
            instruction: options.instructionText,
            debugMode: options.debugMode,
            orientation: options.orientation,
            checks: options.checks,
            profile: options.profile
        )
        overlayView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(overlayView)
        NSLayoutConstraint.activate([
            overlayView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlayView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlayView.topAnchor.constraint(equalTo: view.topAnchor),
            overlayView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        createControls()
        configureSession()
        scheduleTimeout()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if options.checks.stability {
            motionMonitor.start()
        }
        sessionQueue.async { [weak self] in
            guard let self = self, !self.session.isRunning, !self.isCompleted else { return }
            self.session.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timeoutWorkItem?.cancel()
        setTorch(enabled: false)
        motionMonitor.stop()
        sessionQueue.async { [weak self] in
            guard let self = self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
        updatePreviewGeometry()
        updateVideoOrientation()
    }

    override func viewWillTransition(
        to size: CGSize,
        with coordinator: UIViewControllerTransitionCoordinator
    ) {
        setOrientationTransitionInProgress(true)
        resetStability()
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: { [weak self] _ in
            self?.previewLayer.frame = CGRect(origin: .zero, size: size)
        }, completion: { [weak self] _ in
            guard let self = self else { return }
            self.previewLayer.frame = self.view.bounds
            self.overlayView.setNeedsDisplay()
            self.updatePreviewGeometry()
            self.updateVideoOrientation()
            self.setOrientationTransitionInProgress(false)
        })
    }

    func abortForWebViewReset() {
        stateLock.lock()
        let shouldClose = !completed
        completed = true
        stateLock.unlock()
        guard shouldClose else { return }
        timeoutWorkItem?.cancel()
        setTorch(enabled: false)
        motionMonitor.stop()
        dismiss(animated: false)
    }

    private var isCompleted: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return completed
    }

    private func createControls() {
        if options.showCloseButton {
            let button = makeButton(title: "Cerrar")
            button.addTarget(self, action: #selector(cancelByUser), for: .touchUpInside)
            view.addSubview(button)
            closeButton = button
            NSLayoutConstraint.activate([
                button.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
                button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
                button.heightAnchor.constraint(equalToConstant: 44)
            ])
        }
        if options.showFlashButton {
            let button = makeButton(title: flashLabel())
            button.addTarget(self, action: #selector(cycleFlash), for: .touchUpInside)
            view.addSubview(button)
            flashButton = button
            NSLayoutConstraint.activate([
                button.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
                button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
                button.heightAnchor.constraint(equalToConstant: 44)
            ])
        }
        let capture = makeButton(title: "Capturar")
        capture.addTarget(self, action: #selector(captureManually), for: .touchUpInside)
        view.addSubview(capture)
        captureButton = capture
        NSLayoutConstraint.activate([
            capture.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            capture.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            capture.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    private func makeButton(title: String) -> UIButton {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.setTitle(title, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.backgroundColor = UIColor.black.withAlphaComponent(0.62)
        button.layer.cornerRadius = 10
        button.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
        return button
    }

    private func configureSession() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                self.session.beginConfiguration()
                self.session.sessionPreset = .photo
                guard let camera = AVCaptureDevice.default(
                    .builtInWideAngleCamera,
                    for: .video,
                    position: .back
                ) else {
                    throw DNIError.cameraUnavailable("No hay una cámara trasera disponible.", false)
                }
                self.camera = camera
                let input = try AVCaptureDeviceInput(device: camera)
                guard self.session.canAddInput(input) else {
                    throw DNIError.cameraUnavailable("No se pudo conectar la cámara trasera.", true)
                }
                self.session.addInput(input)

                let video = AVCaptureVideoDataOutput()
                video.alwaysDiscardsLateVideoFrames = true
                video.videoSettings = [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
                ]
                video.setSampleBufferDelegate(self, queue: self.analysisQueue)
                guard self.session.canAddOutput(video) else {
                    throw DNIError.cameraUnavailable("No se pudo configurar el análisis de cámara.", true)
                }
                self.session.addOutput(video)
                self.videoOutput = video

                let photo = AVCapturePhotoOutput()
                guard self.session.canAddOutput(photo) else {
                    throw DNIError.cameraUnavailable("No se pudo configurar la captura de fotografías.", true)
                }
                self.session.addOutput(photo)
                photo.maxPhotoQualityPrioritization = .quality
                if #available(iOS 16.0, *) {
                    let candidates = camera.activeFormat.supportedMaxPhotoDimensions.map {
                        DNICaptureDimensions(width: $0.width, height: $0.height)
                    }
                    if let selected = DNICaptureResolutionSelector.select(
                        candidates,
                        preferredLongEdge: self.options.preferredCaptureLongEdge
                    ) {
                        let dimensions = CMVideoDimensions(
                            width: selected.width,
                            height: selected.height
                        )
                        photo.maxPhotoDimensions = dimensions
                        self.selectedPhotoDimensions = dimensions
                    } else {
                        photo.isHighResolutionCaptureEnabled = true
                    }
                } else {
                    photo.isHighResolutionCaptureEnabled = true
                }
                self.photoOutput = photo
                self.session.commitConfiguration()

                DispatchQueue.main.async {
                    self.flashButton?.isHidden = !camera.hasFlash
                    if !camera.hasFlash { self.currentFlashMode = "off" }
                    self.applyFlashMode()
                    self.updateVideoOrientation()
                }
            } catch let error as DNIError {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.fail(error) }
            } catch {
                self.session.commitConfiguration()
                DispatchQueue.main.async {
                    self.fail(.cameraUnavailable("No se pudo abrir la cámara: \(error.localizedDescription)", true))
                }
            }
        }
    }

    private func updateVideoOrientation() {
        let orientation = currentVideoOrientation()
        [previewLayer.connection, videoOutput?.connection(with: .video), photoOutput?.connection(with: .video)]
            .compactMap { $0 }
            .forEach { connection in
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = orientation
                }
            }
    }

    private func currentVideoOrientation() -> AVCaptureVideoOrientation {
        if let locked = lockedCaptureOrientation {
            return videoOrientation(for: locked)
        }
        guard options.orientation != "portrait" else { return .portrait }
        switch view.window?.windowScene?.interfaceOrientation {
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
        case .portrait where options.orientation == "auto":
            return .portrait
        default:
            return options.orientation == "landscape" ? .landscapeRight : .portrait
        }
    }

    private func currentEffectiveOrientation() -> DNIEffectiveOrientation {
        switch currentVideoOrientation() {
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        default: return .portrait
        }
    }

    private func videoOrientation(
        for orientation: DNIEffectiveOrientation
    ) -> AVCaptureVideoOrientation {
        switch orientation {
        case .portrait: return .portrait
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        }
    }

    private func interfaceOrientation(
        for orientation: DNIEffectiveOrientation
    ) -> UIInterfaceOrientation {
        switch orientation {
        case .portrait: return .portrait
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        }
    }

    private func interfaceOrientationMask(
        for orientation: DNIEffectiveOrientation
    ) -> UIInterfaceOrientationMask {
        switch orientation {
        case .portrait: return .portrait
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        }
    }

    private func updatePreviewGeometry() {
        let size = view.bounds.size
        guard size.width > 0, size.height > 0 else { return }
        let guide = overlayView.guideRect(in: CGRect(origin: .zero, size: size))
        geometryLock.lock()
        previewAspectRatio = size.width / size.height
        normalizedGuideRegion = CGRect(
            x: guide.minX / size.width,
            y: guide.minY / size.height,
            width: guide.width / size.width,
            height: guide.height / size.height
        )
        geometryLock.unlock()
    }

    private func currentPreviewGeometry() -> (aspectRatio: CGFloat, guide: CGRect) {
        geometryLock.lock()
        defer { geometryLock.unlock() }
        return (previewAspectRatio, normalizedGuideRegion)
    }

    private func handleAnalysis(_ raw: DNIAnalysis) {
        var analysis = raw
        let motion = motionMonitor.snapshot()
        analysis.motion = motion
        if options.checks.stability, analysis.state == .ready, !motion.stable {
            analysis.state = .holdStill
        }
        stateLock.lock()
        latestTrackedAnalysis = analysis
        latestAnalysisTime = CACurrentMediaTime()
        stateLock.unlock()

        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.isCompleted, !self.isCapturing else { return }
            let instructionState = self.instructionStateStabilizer.update(
                candidate: analysis.state,
                at: CACurrentMediaTime()
            )
            var visibleAnalysis = analysis
            visibleAnalysis.state = instructionState
            self.overlayView.update(
                visibleAnalysis,
                message: self.message(for: instructionState)
            )
            if analysis.state == .holdStill && analysis.stableDurationMs >= 180 {
                self.warmFocus(at: analysis)
            }
            if self.options.autoCapture && analysis.state == .ready {
                self.beginAutomaticCapture(analysis)
            }
        }
    }

    private var isCapturing: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return captureInProgress
    }

    private var isAnalysisSuspended: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return analysisSuspended
    }

    private var lockedCaptureOrientation: DNIEffectiveOrientation? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return captureOrientationState.locked
    }

    private var isOrientationTransitioning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return orientationTransitionInProgress
    }

    private func setCapturing(_ value: Bool) {
        stateLock.lock()
        captureInProgress = value
        if !value {
            pendingFocusMeteringSequence = nil
        }
        stateLock.unlock()
    }

    private func setOrientationTransitionInProgress(_ value: Bool) {
        stateLock.lock()
        orientationTransitionInProgress = value
        stateLock.unlock()
    }

    private func lockCaptureOrientation() {
        let effective = currentEffectiveOrientation()
        stateLock.lock()
        captureOrientationState.beginCapture(at: effective)
        stateLock.unlock()
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        updateVideoOrientation()
    }

    private func releaseCaptureOrientation(preservingCapturedOrientation: Bool) {
        stateLock.lock()
        if preservingCapturedOrientation {
            captureOrientationState.receivePhoto()
        } else {
            captureOrientationState.discardPhoto()
        }
        stateLock.unlock()
        guard options.orientation == "auto" else { return }
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        UIViewController.attemptRotationToDeviceOrientation()
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.overlayView.setNeedsDisplay()
            self.updatePreviewGeometry()
            self.updateVideoOrientation()
        }
    }

    private func beginAutomaticCapture(_ analysis: DNIAnalysis) {
        beginCapture(analysis, manual: false)
    }

    @objc private func captureManually() {
        guard !isCompleted, !isCapturing else { return }
        let analysis = latestAnalysisSnapshot().analysis ?? DNIAnalysis(
            state: .searching,
            detection: nil,
            quality: nil,
            imageWidth: 0,
            imageHeight: 0
        )
        beginCapture(analysis, manual: true)
    }

    private func beginCapture(_ analysis: DNIAnalysis, manual: Bool) {
        guard !isCapturing, !isCompleted, !isOrientationTransitioning,
              photoOutput != nil else { return }
        if !manual, options.checks.stability, !motionMonitor.beginCapture() {
            resetStability()
            overlayView.update(analysis, message: message(for: .holdStill))
            return
        }
        lockCaptureOrientation()
        setCapturing(true)
        manualCaptureRequested = manual
        captureReferenceQuad = analysis.detection?.quad
        captureButton?.isEnabled = false
        var focusing = analysis
        focusing.state = .focusing
        instructionStateStabilizer.force(.focusing)
        overlayView.update(focusing, message: "Enfocando…")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { [weak self] in
            self?.focusAndMeterThenCapture(focusing)
        }
    }

    private func focusAndMeterThenCapture(_ analysis: DNIAnalysis) {
        guard !isCompleted, isCapturing else { return }
        stateLock.lock()
        focusMeteringSequence += 1
        let sequence = focusMeteringSequence
        pendingFocusMeteringSequence = sequence
        stateLock.unlock()

        DispatchQueue.main.asyncAfter(deadline: .now() + focusMeteringTimeout) { [weak self] in
            self?.finishFocusMetering(sequence: sequence)
        }

        guard let detection = analysis.detection,
              analysis.imageWidth > 0, analysis.imageHeight > 0,
              let camera = camera else { return }
        let layerPoint = overlayView.mapToView(
            detection.quad.center,
            imageWidth: analysis.imageWidth,
            imageHeight: analysis.imageHeight
        )
        let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
        let deadline = CACurrentMediaTime() + focusMeteringTimeout
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                try camera.lockForConfiguration()
                if camera.isFocusPointOfInterestSupported {
                    camera.focusPointOfInterest = devicePoint
                    if camera.isFocusModeSupported(.autoFocus) {
                        camera.focusMode = .autoFocus
                    }
                }
                if camera.isExposurePointOfInterestSupported {
                    camera.exposurePointOfInterest = devicePoint
                    if camera.isExposureModeSupported(.autoExpose) {
                        camera.exposureMode = .autoExpose
                    }
                }
                camera.unlockForConfiguration()
                self.waitForFocusAndExposure(
                    camera: camera,
                    sequence: sequence,
                    earliestCompletion: CACurrentMediaTime() + 0.12,
                    deadline: deadline
                )
            } catch {
                // The bounded timeout triggers capture when AF/AE cannot be configured.
            }
        }
    }

    private func waitForFocusAndExposure(
        camera: AVCaptureDevice,
        sequence: Int,
        earliestCompletion: CFTimeInterval,
        deadline: CFTimeInterval
    ) {
        let now = CACurrentMediaTime()
        guard now < deadline else { return }
        if now >= earliestCompletion && !camera.isAdjustingFocus && !camera.isAdjustingExposure {
            DispatchQueue.main.async { [weak self] in
                self?.finishFocusMetering(sequence: sequence)
            }
            return
        }
        sessionQueue.asyncAfter(deadline: .now() + 0.04) { [weak self] in
            self?.waitForFocusAndExposure(
                camera: camera,
                sequence: sequence,
                earliestCompletion: earliestCompletion,
                deadline: deadline
            )
        }
    }

    private func finishFocusMetering(sequence: Int) {
        stateLock.lock()
        let mayCapture = pendingFocusMeteringSequence == sequence
            && captureInProgress && !completed
        if mayCapture {
            pendingFocusMeteringSequence = nil
        }
        stateLock.unlock()
        guard mayCapture else { return }
        if !manualCaptureRequested && !hasFreshCaptureGeometry() {
            retry(message: message(for: .holdStill))
            return
        }
        showCaptureStage(.capturing, message: "Tomando foto")
        capturePhoto()
    }

    private func capturePhoto() {
        guard !isCompleted else { return }
        guard let output = photoOutput else {
            motionMonitor.cancelCapture()
            releaseCaptureOrientation(preservingCapturedOrientation: false)
            setCapturing(false)
            fail(.captureFailed("La salida fotográfica dejó de estar disponible.", true))
            return
        }
        if !manualCaptureRequested, options.checks.stability, !motionMonitor.captureMayProceed() {
            motionMonitor.cancelCapture()
            resumeAfterMotion()
            return
        }
        capturedPreviewAnalysis = latestAnalysisSnapshot().analysis
        let settings: AVCapturePhotoSettings
        if output.availablePhotoCodecTypes.contains(.jpeg) {
            settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        } else {
            settings = AVCapturePhotoSettings()
        }
        settings.photoQualityPrioritization = .quality
        if #available(iOS 16.0, *), let dimensions = selectedPhotoDimensions {
            settings.maxPhotoDimensions = dimensions
        } else {
            settings.isHighResolutionPhotoEnabled = true
        }
        if let camera = camera, camera.hasFlash, currentFlashMode == "auto" {
            settings.flashMode = .auto
        } else {
            settings.flashMode = .off
        }
        output.capturePhoto(with: settings, delegate: self)
    }

    private func processPhoto(_ data: Data, flashFired: Bool?) {
        processingQueue.async { [weak self] in
            guard let self = self, !self.isCompleted else { return }
            do {
                let directory = try self.cacheDirectory()
                let preview = self.capturedPreviewAnalysis
                let scan = try self.stillProcessor.process(
                    data: data,
                    options: self.options,
                    directory: directory,
                    previewDetection: preview?.detection,
                    previewWidth: preview?.imageWidth ?? 0,
                    previewHeight: preview?.imageHeight ?? 0
                )
                if self.options.profile == .dni
                    && self.options.sideValidation == .strict
                    && scan.recognition.side != self.options.side {
                    try? FileManager.default.removeItem(at: scan.fileURL)
                    let requested = self.options.side == "front" ? "frente" : "dorso"
                    let message = scan.recognition.side == "unknown"
                        ? "No se pudo confirmar el \(requested)"
                        : "⚠ Mostrá el \(requested) del DNI"
                    DispatchQueue.main.async { self.retry(message: message) }
                    return
                }
                DispatchQueue.main.async {
                    self.finish(scan: scan, flashFired: flashFired)
                }
            } catch DNIError.retry(let message, let state) {
                DispatchQueue.main.async {
                    self.retry(message: self.message(for: state, fallback: message))
                }
            } catch let error as DNIError {
                DispatchQueue.main.async { self.fail(error) }
            } catch {
                DispatchQueue.main.async {
                    self.fail(.processingFailed("No se pudo procesar la captura: \(error.localizedDescription)", false))
                }
            }
        }
    }

    private func retry(message: String) {
        guard !isCompleted else { return }
        motionMonitor.cancelCapture()
        releaseCaptureOrientation(preservingCapturedOrientation: false)
        resetStability()
        showCaptureStage(.retrying, message: message)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self, !self.isCompleted else { return }
            self.setCapturing(false)
            self.stateLock.lock()
            self.analysisSuspended = false
            self.stateLock.unlock()
            self.manualCaptureRequested = false
            self.captureReferenceQuad = nil
            self.captureButton?.isEnabled = true
            self.instructionStateStabilizer.force(.searching)
            self.overlayView.update(nil, message: "Buscando DNI")
        }
    }

    private func resetStability() {
        analysisQueue.async { [weak self] in
            self?.tracker.reset()
        }
    }

    private func resumeAfterMotion() {
        guard !isCompleted else { return }
        resetStability()
        setCapturing(false)
        manualCaptureRequested = false
        captureReferenceQuad = nil
        captureButton?.isEnabled = true
        releaseCaptureOrientation(preservingCapturedOrientation: false)
        instructionStateStabilizer.force(.holdStill)
        overlayView.update(nil, message: message(for: .holdStill))
    }

    private func showCaptureStage(_ state: DNIAnalysisState, message: String) {
        instructionStateStabilizer.force(state)
        guard var stage = overlayView.analysis else {
            overlayView.update(nil, message: message)
            return
        }
        stage.state = state
        overlayView.update(stage, message: message)
    }

    private func hasFreshCaptureGeometry() -> Bool {
        let snapshot = latestAnalysisSnapshot()
        guard let reference = captureReferenceQuad,
              let latest = snapshot.analysis,
              let detection = latest.detection,
              CACurrentMediaTime() - snapshot.time <= 0.55,
              latest.state == .ready || latest.state == .holdStill,
              latest.imageWidth > 0, latest.imageHeight > 0 else { return false }
        return geometryEngine.quadDistance(
            reference,
            detection.quad,
            width: latest.imageWidth,
            height: latest.imageHeight
        ) <= 0.045
    }

    private func latestAnalysisSnapshot() -> (analysis: DNIAnalysis?, time: CFTimeInterval) {
        stateLock.lock()
        defer { stateLock.unlock() }
        return (latestTrackedAnalysis, latestAnalysisTime)
    }

    private func warmFocus(at analysis: DNIAnalysis) {
        let now = CACurrentMediaTime()
        guard now - lastWarmFocusTime >= 0.9,
              let detection = analysis.detection,
              analysis.imageWidth > 0, analysis.imageHeight > 0,
              let camera = camera else { return }
        lastWarmFocusTime = now
        let layerPoint = overlayView.mapToView(
            detection.quad.center,
            imageWidth: analysis.imageWidth,
            imageHeight: analysis.imageHeight
        )
        let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
        sessionQueue.async {
            do {
                try camera.lockForConfiguration()
                if camera.isFocusPointOfInterestSupported {
                    camera.focusPointOfInterest = devicePoint
                    if camera.isFocusModeSupported(.continuousAutoFocus) {
                        camera.focusMode = .continuousAutoFocus
                    }
                }
                if camera.isExposurePointOfInterestSupported {
                    camera.exposurePointOfInterest = devicePoint
                    if camera.isExposureModeSupported(.continuousAutoExposure) {
                        camera.exposureMode = .continuousAutoExposure
                    }
                }
                camera.unlockForConfiguration()
            } catch {
                // Manual capture and the bounded focus pass remain available.
            }
        }
    }

    private func cacheDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("dni-scanner", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func scheduleTimeout() {
        guard options.timeoutMs > 0 else { return }
        let item = DispatchWorkItem { [weak self] in
            self?.fail(.captureFailed("No se obtuvo una captura válida dentro del tiempo configurado.", true))
        }
        timeoutWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(options.timeoutMs) / 1000, execute: item)
    }

    @objc private func cancelByUser() {
        stateLock.lock()
        guard !completed else {
            stateLock.unlock()
            return
        }
        completed = true
        stateLock.unlock()
        timeoutWorkItem?.cancel()
        setTorch(enabled: false)
        motionMonitor.cancelCapture()
        motionMonitor.stop()
        delegate?.dniScannerDidCancel(self)
    }

    private func finish(scan: DNIProcessedScan, flashFired: Bool?) {
        stateLock.lock()
        guard !completed else {
            stateLock.unlock()
            return
        }
        showCaptureStage(.success, message: "✓ Listo")
        completed = true
        stateLock.unlock()
        timeoutWorkItem?.cancel()
        setTorch(enabled: false)
        let motion = acceptedMotionSnapshot ?? motionMonitor.snapshot()
        motionMonitor.stop()
        if options.beepEnabled {
            successBeep.play()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.26) { [weak self] in
            guard let self = self else { return }
            self.delegate?.dniScanner(
                self,
                didFinish: scan,
                flashFired: flashFired,
                motion: motion
            )
        }
    }

    private func fail(_ error: DNIError) {
        stateLock.lock()
        guard !completed else {
            stateLock.unlock()
            return
        }
        completed = true
        stateLock.unlock()
        timeoutWorkItem?.cancel()
        setTorch(enabled: false)
        motionMonitor.cancelCapture()
        motionMonitor.stop()
        delegate?.dniScanner(self, didFail: error)
    }

    @objc private func cycleFlash() {
        guard camera?.hasFlash == true else { return }
        switch currentFlashMode {
        case "off": currentFlashMode = "auto"
        case "auto": currentFlashMode = "on"
        default: currentFlashMode = "off"
        }
        applyFlashMode()
    }

    private func applyFlashMode() {
        setTorch(enabled: currentFlashMode == "on")
        flashButton?.setTitle(flashLabel(), for: .normal)
    }

    private func setTorch(enabled: Bool) {
        guard let camera = camera, camera.hasTorch else { return }
        do {
            try camera.lockForConfiguration()
            if enabled && camera.isTorchModeSupported(.on) {
                try camera.setTorchModeOn(level: min(AVCaptureDevice.maxAvailableTorchLevel, 0.65))
            } else {
                camera.torchMode = .off
            }
            camera.unlockForConfiguration()
        } catch {
            // Flash is optional; capture can continue without torch control.
        }
    }

    private func flashLabel() -> String {
        switch currentFlashMode {
        case "on": return "Flash: sí"
        case "auto": return "Flash: auto"
        default: return "Flash: no"
        }
    }

    private func message(for state: DNIAnalysisState, fallback: String? = nil) -> String {
        switch state {
        case .searching: return fallback ?? "Ubicá el DNI dentro del marco"
        case .moveCloser: return "Acercá el DNI"
        case .moveAway: return "Alejá el DNI"
        case .centerDocument: return "Centrá el DNI"
        case .adjustPerspective: return "Alineá el celular con el DNI"
        case .improveLighting: return "Necesitás más luz"
        case .reduceLighting: return "Hay demasiada luz"
        case .avoidGlare: return "Evitá reflejos sobre el DNI"
        case .unevenLighting: return "Evitá sombras sobre el DNI"
        case .edgeOccluded: return "No tapes los bordes con los dedos"
        case .holdStill: return "Mantené el celular quieto"
        case .waitingForFocus: return "Esperando enfoque"
        case .ready: return "Listo"
        case .focusing: return "Enfocando…"
        case .capturing: return "Tomando foto"
        case .processing: return "Procesando imagen…"
        case .retrying: return "Reintentando captura"
        case .success: return "✓ Listo"
        }
    }
}

extension DNIViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard !isCompleted, !isOrientationTransitioning, !isAnalysisSuspended else { return }
        let now = CACurrentMediaTime()
        guard now - lastAnalysisTime >= 0.10 else { return }
        lastAnalysisTime = now
        do {
            let geometry = currentPreviewGeometry()
            let raster = try DNIImageFactory.shared.raster(
                from: sampleBuffer,
                maxDimension: 512,
                viewportAspectRatio: geometry.aspectRatio
            )
            let result = try tracker.update(
                raster: raster,
                checks: options.checks,
                allowedRegion: geometry.guide,
                profile: options.profile,
                at: now
            )
            handleAnalysis(result)
        } catch {
            DispatchQueue.main.async { [weak self] in
                guard let self = self, !self.isCompleted, !self.isCapturing else { return }
                self.overlayView.update(nil, message: "No se pudo analizar la cámara")
            }
        }
    }
}

extension DNIViewController: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        let motionSnapshot: DNIMotionSnapshot
        if options.checks.stability && !manualCaptureRequested {
            let motionOutcome = motionMonitor.finishCapture()
            motionSnapshot = motionOutcome.snapshot
        } else {
            motionSnapshot = motionMonitor.snapshot()
        }
        let data = photo.fileDataRepresentation()
        let flashFired = photo.resolvedSettings.isFlashEnabled
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.isCompleted else { return }
            self.releaseCaptureOrientation(preservingCapturedOrientation: error == nil && data != nil)
            self.acceptedMotionSnapshot = motionSnapshot
            if let error = error {
                self.fail(.captureFailed(
                    "No se pudo tomar la fotografía: \(error.localizedDescription)",
                    true
                ))
                return
            }
            guard let data = data else {
                self.fail(.captureFailed("La cámara no devolvió la fotografía capturada.", true))
                return
            }
            self.stateLock.lock()
            self.analysisSuspended = true
            self.stateLock.unlock()
            self.showCaptureStage(.processing, message: "Procesando imagen…")
            self.processPhoto(data, flashFired: flashFired)
        }
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
        error: Error?
    ) {
        guard let error = error else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.isCompleted,
                  self.lockedCaptureOrientation != nil else { return }
            self.motionMonitor.cancelCapture()
            self.releaseCaptureOrientation(preservingCapturedOrientation: false)
            self.setCapturing(false)
            self.fail(.captureFailed(
                "No se pudo completar la fotografía: \(error.localizedDescription)",
                true
            ))
        }
    }
}
