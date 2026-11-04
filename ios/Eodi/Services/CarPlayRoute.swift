import AVFoundation

/// 지금 연결된 CarPlay 오디오 장치. 차마다 이름·UID가 달라서 어느 차인지 구분할 수 있음.
/// (CarPlay 권한 없이 오디오 출력 경로만 읽음)
enum CarPlayRoute {
    struct Info: Equatable {
        let uid: String
        let name: String
    }

    /// 앱 오디오 세션이 꺼져 있으면 iOS가 실제 출력 경로(CarPlay)를 안 알려 줄 때가 있어서,
    /// 다른 앱 소리를 끊지 않는 방식(ambient + mixWithOthers)으로 잠깐 켜고 읽음
    private static func withActiveSession<T>(_ body: (AVAudioSession) -> T) -> T {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
        let activated = (try? session.setActive(true)) != nil
        defer { if activated { try? session.setActive(false, options: .notifyOthersOnDeactivation) } }
        return body(session)
    }

    static func current() -> Info? {
        withActiveSession { session in
            let ports = session.currentRoute.outputs + session.currentRoute.inputs
            guard let port = ports.first(where: { $0.portType == .carAudio }) else { return nil }
            return Info(uid: port.uid, name: port.portName)
        }
    }

    /// 진단용: 지금 오디오 출력·입력 목록 ("종류 · 이름")
    static func describeRoute() -> [String] {
        withActiveSession { session in
            let r = session.currentRoute
            let outs = r.outputs.map { "출력 \($0.portType.rawValue) · \($0.portName)" }
            let ins = r.inputs.map { "입력 \($0.portType.rawValue) · \($0.portName)" }
            return outs + ins
        }
    }

    /// 단축어가 CarPlay 연결 직후에 실행되면 오디오 경로가 아직 안 바뀌었을 수 있어서 잠깐 기다려 봄
    static func waitForCurrent(timeout: TimeInterval = 4) async -> Info? {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if let info = current() { return info }
            if Date() >= deadline { return nil }
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
    }
}
