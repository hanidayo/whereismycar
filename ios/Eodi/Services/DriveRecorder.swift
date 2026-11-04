import Foundation
import Combine
import CoreLocation
import CoreMotion

struct PressureSample: Codable {
    var date: Date
    var kPa: Double
}

/// 운전 중 상태. 앱이 중간에 종료돼도 이어갈 수 있게 저장합니다.
struct DriveState: Codable {
    var carID: UUID
    var startedAt: Date
    /// 마지막으로 GPS가 좋았던 시점(=주차장 입구 직전)의 기압
    var refPressure: Double?
    var refLat: Double?
    var refLon: Double?
    var refAccuracy: Double?
    var refDate: Date?
    /// 기준점에서 차가 향하던 방향(도) — 입구 구분용
    var refCourse: Double?
    var lastLat: Double?
    var lastLon: Double?
    var lastAccuracy: Double?
    /// 마지막으로 의미 있게(moveThreshold 이상) 움직인 위치·시각 — 자동 종료 판단용
    var moveLat: Double?
    var moveLon: Double?
    var lastMoveAt: Date?
    /// 기준점 이후 가장 높은 기압(=가장 낮은 위치). 자동 종료 때 층 계산에 씀
    var peakPressure: Double?
}

@MainActor
final class DriveRecorder: NSObject, ObservableObject {
    static let shared = DriveRecorder()

    @Published private(set) var state: DriveState?
    @Published private(set) var latestPressure: PressureSample?
    @Published private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined

    /// 이 정확도(m) 이하면 "지상에서 GPS가 잘 잡힌 상태"로 봄
    static let goodAccuracy: CLLocationAccuracy = 25
    /// 기준 기압은 마지막 GPS 시점보다 몇 초 앞의 값을 씀(경사로에 들어서기 전)
    static let lookback: TimeInterval = 5
    /// 이만큼 움직이지 않으면(지하에서 GPS가 안 잡히는 시간 포함) 주차한 것으로 보고 자동 종료
    static let idleLimit: TimeInterval = 30 * 60
    /// 운전 시작 후 이 시간이 지나면 무조건 자동 종료(배터리 보호)
    static let maxDuration: TimeInterval = 8 * 60 * 60
    /// 이 거리(m) 이상 움직여야 "움직였다"로 봄
    static let moveThreshold: CLLocationDistance = 100

    private let locationManager = CLLocationManager()
    private let altimeter = CMAltimeter()
    private var buffer: [PressureSample] = []
    private var sensorsRunning = false
    private var watchdog: Timer?
    private var autoParking = false
    private let stateKey = "eodi.driveState"

    var isAltimeterAvailable: Bool { CMAltimeter.isRelativeAltitudeAvailable() }

    /// 시스템이 마지막으로 알고 있는 위치 (차 자동 선택용)
    var lastKnownLocation: CLLocation? { locationManager.location }

    /// 실시간: 기준점 대비 높이 변화(m)
    var liveDelta: Double? {
        guard let ref = state?.refPressure, let now = latestPressure?.kPa else { return nil }
        return Altitude.meters(kPa: now) - Altitude.meters(kPa: ref)
    }

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.activityType = .automotiveNavigation
        locationManager.pausesLocationUpdatesAutomatically = false
        authorizationStatus = locationManager.authorizationStatus

        if let data = UserDefaults.standard.data(forKey: stateKey),
           let saved = try? JSONDecoder().decode(DriveState.self, from: data) {
            state = saved
            startSensors() // 운전 중에 앱이 재시작된 경우
        }
    }

    func requestPermissions() {
        switch locationManager.authorizationStatus {
        case .notDetermined: locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse: locationManager.requestAlwaysAuthorization()
        default: break
        }
    }

    // MARK: - 운전 시작 / 주차

    func startDrive(carID: UUID) {
        // 이미 기록 중이면(자동화가 두 번 실행되는 등) 기준점은 유지하고 차만 맞춤
        if var s = state, Date().timeIntervalSince(s.startedAt) < 15 * 60 {
            s.carID = carID
            state = s
            persist()
            startSensors()
            return
        }
        state = DriveState(carID: carID, startedAt: Date())
        buffer.removeAll()
        persist()
        startSensors()
    }

    /// 주차 확정: 지금 기압과 기준 기압 차이로 층을 계산하고 기록을 남김
    /// auto: 단축어가 실패해 기록이 계속 켜져 있을 때 안전장치가 부른 경우
    @discardableResult
    func park(carID: UUID?, auto: Bool = false) async -> ParkingRecord? {
        let store = ParkingStore.shared
        let s = state
        // 블루투스·CarPlay 자동화가 둘 다 "주차 기록"을 실행해도 기록은 하나만
        if s == nil, !auto,
           let recent = store.records.max(by: { $0.date < $1.date }),
           Date().timeIntervalSince(recent.date) < 3 * 60,
           carID == nil || carID == recent.carID {
            return recent
        }
        guard let id = carID ?? s?.carID ?? store.defaultCar?.id else { return nil }
        let nowPressure = await currentPressure()

        var delta: Double?
        if let ref = s?.refPressure, let now = nowPressure {
            delta = Altitude.meters(kPa: now) - Altitude.meters(kPa: ref)
        }
        // 자동 종료는 주차 후 한참 뒤라 사람이 위층으로 갔을 수 있음 → 입구 이후 가장 낮았던 지점을 씀
        if auto, let ref = s?.refPressure, let peak = s?.peakPressure {
            let lowest = Altitude.meters(kPa: peak) - Altitude.meters(kPa: ref)
            if delta.map({ lowest < $0 }) ?? true { delta = lowest }
        }

        var coordinate: CLLocationCoordinate2D?
        var accuracy: Double?
        var course: Double?
        if let lat = s?.refLat, let lon = s?.refLon {
            coordinate = .init(latitude: lat, longitude: lon); accuracy = s?.refAccuracy; course = s?.refCourse
        } else if let lat = s?.lastLat, let lon = s?.lastLon {
            coordinate = .init(latitude: lat, longitude: lon); accuracy = s?.lastAccuracy
        } else if let loc = locationManager.location {
            coordinate = loc.coordinate; accuracy = loc.horizontalAccuracy
        }

        stopDrive()
        let record = store.recordParking(carID: id, coordinate: coordinate, accuracy: accuracy, delta: delta, course: course)
        // 가족 공유를 쓰면 ntfy가 모두에게(나 포함) 알리므로 로컬 알림은 생략
        if auto {
            Notifier.post(title: "자동으로 주차 기록 · \(record.levelName) · \(store.carName(id))",
                          body: "운전 기록이 오래 멈춰 있어서 GPS를 끄고 주차로 기록했어요. 층이 틀리면 앱에서 보정해 주세요.")
        } else if !store.sync.isConfigured {
            Notifier.post(title: "\(record.levelName) · \(store.carName(id))",
                          body: "주차 위치를 기록했어요. 기둥 번호 사진을 찍어두면 더 찾기 쉬워요.")
        }
        return record
    }

    func stopDrive() {
        state = nil
        UserDefaults.standard.removeObject(forKey: stateKey)
        locationManager.stopUpdatingLocation()
        locationManager.allowsBackgroundLocationUpdates = false
        altimeter.stopRelativeAltitudeUpdates()
        watchdog?.invalidate()
        watchdog = nil
        sensorsRunning = false
        buffer.removeAll()
    }

    // MARK: - 센서

    private func startSensors() {
        guard !sensorsRunning else { return }
        sensorsRunning = true
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.startUpdatingLocation()
        watchdog = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkAutoStop() }
        }

        guard isAltimeterAvailable else { return }
        altimeter.startRelativeAltitudeUpdates(to: .main) { [weak self] data, _ in
            guard let data else { return }
            let sample = PressureSample(date: Date(), kPa: data.pressure.doubleValue)
            MainActor.assumeIsolated { self?.handle(sample: sample) }
        }
    }

    private func handle(sample: PressureSample) {
        latestPressure = sample
        buffer.append(sample)
        let cutoff = Date().addingTimeInterval(-180)
        buffer.removeAll { $0.date < cutoff }

        if var s = state, s.refPressure != nil, sample.kPa > (s.peakPressure ?? -.infinity) {
            s.peakPressure = sample.kPa
            state = s
            persist()
        }
    }

    /// 단축어 "연결 해제될 때"가 실패해도 GPS가 계속 켜져 있지 않게 하는 안전장치
    private func checkAutoStop() {
        guard let s = state, !autoParking else { return }
        let now = Date()
        let idle = now.timeIntervalSince(s.lastMoveAt ?? s.startedAt)
        let total = now.timeIntervalSince(s.startedAt)
        guard idle > Self.idleLimit || total > Self.maxDuration else { return }
        autoParking = true
        Task {
            await park(carID: s.carID, auto: true)
            autoParking = false
        }
    }

    private func handle(location loc: CLLocation) {
        guard var s = state else { return }
        s.lastLat = loc.coordinate.latitude
        s.lastLon = loc.coordinate.longitude
        s.lastAccuracy = loc.horizontalAccuracy

        if loc.horizontalAccuracy > 0, loc.horizontalAccuracy <= 50 {
            let moved = s.moveLat.flatMap { la in s.moveLon.map { lo in
                CLLocation(latitude: la, longitude: lo).distance(from: loc)
            } } ?? .infinity
            if moved >= Self.moveThreshold {
                s.moveLat = loc.coordinate.latitude
                s.moveLon = loc.coordinate.longitude
                s.lastMoveAt = Date()
            }
        }

        let age = -loc.timestamp.timeIntervalSinceNow
        if loc.horizontalAccuracy > 0, loc.horizontalAccuracy <= Self.goodAccuracy, age < 10 {
            s.refLat = loc.coordinate.latitude
            s.refLon = loc.coordinate.longitude
            s.refAccuracy = loc.horizontalAccuracy
            s.refDate = loc.timestamp
            if loc.course >= 0, loc.speed > 1.5 { s.refCourse = loc.course }
            if let p = pressure(near: loc.timestamp.addingTimeInterval(-Self.lookback)) {
                s.refPressure = p
            }
            s.peakPressure = nil // 아직 지상 → 최저점 다시 추적
        }
        state = s
        persist()
        checkAutoStop()
    }

    private func pressure(near date: Date) -> Double? {
        guard !buffer.isEmpty else { return latestPressure?.kPa }
        return buffer.min {
            abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date))
        }?.kPa
    }

    /// 지금 기압 한 번 읽기(앱이 막 깨어나 센서가 꺼져 있어도 동작)
    private func currentPressure(timeout: TimeInterval = 4) async -> Double? {
        if let s = latestPressure, Date().timeIntervalSince(s.date) < 3 { return s.kPa }
        guard isAltimeterAvailable else { return nil }
        return await withCheckedContinuation { cont in
            let oneShot = CMAltimeter()
            let once = OnceFlag()
            oneShot.startRelativeAltitudeUpdates(to: .main) { data, _ in
                guard !once.finished else { return }
                once.finished = true
                oneShot.stopRelativeAltitudeUpdates()
                cont.resume(returning: data?.pressure.doubleValue)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) {
                guard !once.finished else { return }
                once.finished = true
                oneShot.stopRelativeAltitudeUpdates()
                cont.resume(returning: nil)
            }
        }
    }

    private func persist() {
        guard let state, let data = try? JSONEncoder().encode(state) else { return }
        UserDefaults.standard.set(data, forKey: stateKey)
    }
}

/// asyncAfter의 @Sendable 클로저에서 캡처한 var를 바꿀 수 없어서 참조 타입으로 감쌈 (둘 다 main 큐에서만 접근)
private final class OnceFlag { var finished = false }

extension DriveRecorder: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in self.handle(location: loc) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            if status == .authorizedWhenInUse { self.locationManager.requestAlwaysAuthorization() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
