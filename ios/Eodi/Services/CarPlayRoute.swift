import AVFoundation

/// 지금 연결된 CarPlay 오디오 장치. 차마다 이름·UID가 달라서 어느 차인지 구분할 수 있음.
/// (CarPlay 권한 없이 오디오 출력 경로만 읽음)
enum CarPlayRoute {
    struct Info: Equatable {
        let uid: String
        let name: String
    }

    static func current() -> Info? {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        guard let port = outputs.first(where: { $0.portType == .carAudio }) else { return nil }
        return Info(uid: port.uid, name: port.portName)
    }

    /// 단축어가 CarPlay 연결 직후에 실행되면 오디오 경로가 아직 안 바뀌었을 수 있어서 잠깐 기다려 봄
    static func waitForCurrent(timeout: TimeInterval = 3) async -> Info? {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if let info = current() { return info }
            if Date() >= deadline { return nil }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
    }
}
