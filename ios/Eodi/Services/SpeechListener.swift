import AVFoundation
import Speech

/// 한국어 음성 인식 (말을 멈추면 자동으로 끝)
@MainActor
final class SpeechListener: ObservableObject {
    @Published private(set) var text = ""
    /// 인식 후보들 (첫 번째가 가장 그럴듯함)
    @Published private(set) var alternatives: [String] = []
    @Published private(set) var listening = false
    @Published private(set) var error: String?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "ko-KR"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silenceTimer: Task<Void, Never>?

    func start() async {
        error = nil
        let speechOK = await withCheckedContinuation { c in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0 == .authorized) }
        }
        let micOK = await AVAudioApplication.requestRecordPermission()
        guard speechOK, micOK else { error = "설정에서 마이크·음성 인식 권한을 허용해 주세요"; return }
        guard let recognizer, recognizer.isAvailable else { error = "지금은 음성 인식을 쓸 수 없어요"; return }

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            self.error = "마이크를 켤 수 없어요"; return
        }

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.contextualStrings = ["지하", "층", "기둥", "에이", "비", "씨", "디", "에프", "지", "에이치",
                                 "에이 1", "비 2", "씨 3", "디 4", "지하 1층", "지하 2층", "지하 3층", "지하 4층"]
        req.taskHint = .search
        request = req

        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            req.append(buffer)
        }
        engine.prepare()
        do { try engine.start() } catch { self.error = "마이크를 켤 수 없어요"; cleanup(); return }

        text = ""
        alternatives = []
        listening = true
        scheduleSilenceStop(after: 6)  // 아무 말도 안 하면 6초 뒤 끝
        task = recognizer.recognitionTask(with: req) { [weak self] result, err in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.text = result.bestTranscription.formattedString
                    self.alternatives = result.transcriptions.map(\.formattedString)
                    self.scheduleSilenceStop(after: 1.6)  // 말을 멈추면 끝
                    if result.isFinal { self.stop() }
                }
                if err != nil { self.stop() }
            }
        }
    }

    private func scheduleSilenceStop(after seconds: Double) {
        silenceTimer?.cancel()
        silenceTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            if !Task.isCancelled { self?.stop() }
        }
    }

    func stop() {
        guard listening else { return }
        listening = false
        request?.endAudio()
        task?.finish()
        cleanup()
    }

    private func cleanup() {
        silenceTimer?.cancel()
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        request = nil
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
