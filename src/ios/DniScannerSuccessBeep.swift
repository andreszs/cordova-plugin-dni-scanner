import AVFoundation

final class DniScannerSuccessBeep {
    private var player: AVAudioPlayer?

    func play() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            let nextPlayer = try AVAudioPlayer(data: Self.data)
            nextPlayer.volume = 1
            nextPlayer.prepareToPlay()
            player = nextPlayer
            nextPlayer.play()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                guard let self = self, self.player === nextPlayer else { return }
                self.stop()
            }
        } catch {
            player = nil
        }
    }

    func stop() {
        player?.stop()
        player = nil
        try? AVAudioSession.sharedInstance().setActive(
            false, options: [.notifyOthersOnDeactivation]
        )
    }

    deinit {
        stop()
    }

    private static let data: Data = {
        let sampleRate: UInt32 = 22_050
        let sampleCount = Int(sampleRate / 10)
        let dataSize = UInt32(sampleCount * 2)
        var data = Data()

        func appendASCII(_ value: String) {
            data.append(contentsOf: value.utf8)
        }
        func appendUInt16(_ value: UInt16) {
            var littleEndian = value.littleEndian
            Swift.withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
        }
        func appendUInt32(_ value: UInt32) {
            var littleEndian = value.littleEndian
            Swift.withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
        }
        func appendInt16(_ value: Int16) {
            appendUInt16(UInt16(bitPattern: value))
        }

        appendASCII("RIFF")
        appendUInt32(36 + dataSize)
        appendASCII("WAVE")
        appendASCII("fmt ")
        appendUInt32(16)
        appendUInt16(1)
        appendUInt16(1)
        appendUInt32(sampleRate)
        appendUInt32(sampleRate * 2)
        appendUInt16(2)
        appendUInt16(16)
        appendASCII("data")
        appendUInt32(dataSize)

        for index in 0..<sampleCount {
            let time = Double(index) / Double(sampleRate)
            let remaining = Double(sampleCount - index) / Double(sampleRate)
            let envelope = min(1, time / 0.005) * min(1, remaining / 0.025)
            let value = sin(2 * Double.pi * 1_800 * time)
                * envelope * Double(Int16.max) * 0.55
            appendInt16(Int16(value.rounded()))
        }
        return data
    }()
}
