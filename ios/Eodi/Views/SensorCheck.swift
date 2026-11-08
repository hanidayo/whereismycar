import CoreLocation
import CoreMotion
import SwiftUI
import UserNotifications

/// GPS·기압 셀프점검
@MainActor
final class SensorCheck: NSObject, ObservableObject, CLLocationManagerDelegate {
    enum Status { case unknown, ok, warn, fail }
    struct Item {
        var status: Status = .unknown
        var detail = "점검 전"
        var date: Date?
    }

    @Published var pressure = Item()
    @Published var gps = Item()
    @Published var running = false

    private let manager = CLLocationManager()
    private var best: CLLocation?
    private var waiter: CheckedContinuation<Void, Never>?

    func run() async {
        guard !running else { return }
        running = true
        defer { running = false }

        // 기압계
        if !CMAltimeter.isRelativeAltitudeAvailable() {
            pressure = Item(status: .fail, detail: "이 기기에는 기압계가 없어요", date: Date())
        } else if let kPa = await DriveRecorder.shared.readPressure() {
            pressure = Item(status: .ok, detail: String(format: "정상 · %.1f hPa", kPa * 10), date: Date())
        } else {
            pressure = Item(status: .fail, detail: "값을 못 읽었어요 · '동작 및 피트니스' 권한 확인", date: Date())
        }

        // GPS: 최대 15초 동안 받아서 가장 좋은 위치로 판단
        gps = Item(status: .unknown, detail: "위치 받는 중…", date: nil)
        best = nil
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.startUpdatingLocation()
        await withCheckedContinuation { c in
            waiter = c
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                self?.finishGPS()
            }
        }
        manager.stopUpdatingLocation()

        if let l = best {
            let acc = Int(l.horizontalAccuracy.rounded())
            if l.verticalAccuracy > 0, l.horizontalAccuracy <= 25 {
                gps = Item(status: .ok, detail: "위성 신호 · 정확도 \(acc)m", date: Date())
            } else {
                gps = Item(status: .warn, detail: "위성 신호 약함 · 정확도 \(acc)m\n실내면 밖에서 다시 점검해 보세요", date: Date())
            }
        } else {
            gps = Item(status: .fail, detail: "위치를 못 받았어요 · 위치 권한 확인", date: Date())
        }
    }

    private func finishGPS() {
        waiter?.resume()
        waiter = nil
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            for l in locations where l.horizontalAccuracy > 0 {
                if best == nil || l.horizontalAccuracy < best!.horizontalAccuracy { best = l }
            }
            // 위성 신호로 충분히 정확하면 바로 끝
            if let b = best, b.verticalAccuracy > 0, b.horizontalAccuracy <= 10 { finishGPS() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

/// 설정 맨 위: 자동 기록 준비 상태 + 셀프점검
struct SensorCheckSections: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder
    @StateObject private var check = SensorCheck()
    @State private var notificationsOK: Bool?
    @State private var preciseOK = CLLocationManager().accuracyAuthorization == .fullAccuracy

    var body: some View {
        Section {
            let auth = recorder.authorizationStatus
            row("위치 권한", ok(auth == .authorizedAlways || auth == .authorizedWhenInUse), auth == .notDetermined ? "아직 안 물어봄" : nil)
            row("항상 위치 허용", auth == .authorizedAlways ? .ok : .warn, auth == .authorizedAlways ? nil : "화면이 꺼진 채 운전하면 기록이 안 될 수 있어요")
            row("정확한 위치", ok(preciseOK), preciseOK ? nil : "설정 › 어디댔지 › 위치 › 정확한 위치 켜기")
            let motion = CMAltimeter.authorizationStatus()
            row("동작·기압 권한", motion == .authorized ? .ok : (motion == .notDetermined ? .warn : .fail),
                motion == .authorized ? nil : "설정 › 어디댔지 › 동작 및 피트니스")
            row("알림", notificationsOK.map { ok($0) } ?? .unknown, nil)
            let linked = store.cars.filter { store.carPlayLink(for: $0.id) != nil }.count
            row("CarPlay 지정", linked == store.cars.count ? .ok : .warn,
                "\(linked)/\(store.cars.count)대 · 블루투스 자동화만 쓰면 없어도 돼요")
            row("가족 공유", store.sync.isConfigured ? .ok : .warn, store.sync.isConfigured ? nil : "꺼져 있어요")
        } header: {
            Text("자동 기록 준비 상태")
        } footer: {
            Text("단축어 자동화(블루투스·CarPlay)는 앱에서 확인할 수 없어요. 단축어 앱 › 자동화에서 확인해 주세요.")
        }

        Section {
            HStack(alignment: .top, spacing: 12) {
                tile("기압 센서", check.pressure, icon: "barometer")
                Divider()
                tile("GPS", check.gps, icon: "location.fill")
            }
            .padding(.vertical, 4)
            Button {
                Task { await check.run() }
            } label: {
                HStack {
                    Text(check.running ? "점검 중…" : "셀프점검 하기").fontWeight(.semibold)
                    Spacer()
                    if check.running { ProgressView() } else { Image(systemName: "chevron.right") }
                }
            }
            .disabled(check.running)
        } header: {
            Text("GPS · 기압 셀프점검")
        } footer: {
            Text("GPS는 하늘이 보이는 곳에서 점검해야 정확해요. 층 기준점은 위성 신호가 잡힐 때만 정해져요.")
        }
        .task {
            let s = await UNUserNotificationCenter.current().notificationSettings()
            notificationsOK = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            preciseOK = CLLocationManager().accuracyAuthorization == .fullAccuracy
        }
    }

    private func ok(_ b: Bool) -> SensorCheck.Status { b ? .ok : .fail }

    private func symbol(_ s: SensorCheck.Status) -> (String, Color) {
        switch s {
        case .ok: ("checkmark.circle.fill", Color(hex: 0x34C38F))
        case .warn: ("exclamationmark.circle.fill", Color(hex: 0xE69A28))
        case .fail: ("xmark.circle.fill", Color(hex: 0xEF5350))
        case .unknown: ("circle.dashed", Palette.sub)
        }
    }

    private func row(_ title: String, _ s: SensorCheck.Status, _ detail: String?) -> some View {
        let (sym, color) = symbol(s)
        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: sym).font(.title3).foregroundStyle(color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Palette.ink)
                if let detail { Text(detail).font(.caption).foregroundStyle(Palette.sub) }
            }
            Spacer()
            Text(s == .ok ? "완료" : s == .unknown ? "확인 중" : "확인 필요")
                .font(.caption.weight(.semibold)).foregroundStyle(color)
        }
    }

    private func tile(_ title: String, _ item: SensorCheck.Item, icon: String) -> some View {
        let (sym, color) = symbol(item.status)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: sym).foregroundStyle(color)
                Text(title).font(.headline).foregroundStyle(Palette.ink)
            }
            Text(item.detail).font(.caption).foregroundStyle(item.status == .ok ? color : Palette.sub)
                .fixedSize(horizontal: false, vertical: true)
            if let d = item.date {
                Text(d.formatted(date: .numeric, time: .shortened)).font(.caption2).foregroundStyle(Palette.sub)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
