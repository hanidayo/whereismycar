import Foundation
import CoreLocation

/// 차는 고유 ID로 구분합니다. 이름을 바꿔도 기록·단축어·알림이 그대로 이어집니다.
struct Car: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var color: Int = 0
}

struct ParkingRecord: Codable, Identifiable {
    var id = UUID()
    var carID: UUID
    var member: String
    var date: Date
    var latitude: Double?
    var longitude: Double?
    var accuracy: Double?
    /// 입구(마지막 GPS 기준점) 대비 높이 변화. 음수면 내려간 것.
    var deltaMeters: Double?
    /// 층 번호: 0 = 1F, -1 = B1, 1 = 2F
    var level: Int?
    var calibrated = false
    var garageID: UUID?
    var photoFile: String?
    var hasRemotePhoto = false
    var photoPending = false
    var pillarText: String?
    var ocrLevel: Int?
    var synced = false
    /// 입구로 들어갈 때 차가 향하던 방향(도, 0=북). 같은 주차장의 입구 구분용
    var course: Double?

    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var levelName: String { level.map(Level.name) ?? "층 미확인" }
}

struct GarageSample: Codable {
    var recordID: UUID
    var delta: Double
    var level: Int
}

/// 주차장별 학습값. 보정할 때마다 샘플이 쌓이고 가족과 공유됩니다.
struct GarageProfile: Codable, Identifiable {
    var id = UUID()
    var latitude: Double
    var longitude: Double
    var name: String?
    var samples: [GarageSample] = []
    /// 이 입구의 진입 방향(도). 가까운 두 입구도 방향으로 구분
    var course: Double?
    /// 장소 아이콘 키 (PlaceIcon)
    var icon: String?

    var location: CLLocation { CLLocation(latitude: latitude, longitude: longitude) }
}

/// 두 방향(도)의 차이 0~180
func angleDifference(_ a: Double, _ b: Double) -> Double {
    let d = abs(a - b).truncatingRemainder(dividingBy: 360)
    return min(d, 360 - d)
}

enum Level {
    static func name(_ i: Int) -> String { i >= 0 ? "\(i + 1)F" : "B\(-i)" }
    /// "지하 3층" / "1층" / "층 모름"
    static func longName(_ i: Int?) -> String {
        guard let i else { return "층 모름" }
        return i >= 0 ? "\(i + 1)층" : "지하 \(-i)층"
    }
}

enum Altitude {
    /// 기압(kPa) → 표준대기 기준 고도(m). 차이만 쓰므로 절대값 오차는 상관없음.
    static func meters(kPa: Double) -> Double {
        44330.0 * (1.0 - pow(kPa / 101.325, 0.190263))
    }
}

enum FloorModel {
    /// level = a * delta + b
    static func coefficients(profile: GarageProfile?, defaultHeight: Double) -> (a: Double, b: Double) {
        let fallback = (a: 1.0 / defaultHeight, b: 0.0)
        guard let samples = profile?.samples, !samples.isEmpty else { return fallback }

        if samples.count >= 2 {
            let n = Double(samples.count)
            let mx = samples.map(\.delta).reduce(0, +) / n
            let my = samples.map { Double($0.level) }.reduce(0, +) / n
            let sxx = samples.map { ($0.delta - mx) * ($0.delta - mx) }.reduce(0, +)
            let sxy = samples.map { ($0.delta - mx) * (Double($0.level) - my) }.reduce(0, +)
            if sxx > 4 {
                let a = sxy / sxx
                if a > 0, (2.0...6.0).contains(1 / a) { return (a, my - a * mx) }
            }
        }
        // 보정이 한두 번뿐이면 층 높이는 기본값으로 두고 "입구 높이 차이(오프셋)"만 학습.
        // 예: 입구에서 1m 올라가서 B1인 주차장 → delta +1 = B1 로 바로 맞춰짐
        let a = 1.0 / defaultHeight
        let b = samples.map { Double($0.level) - $0.delta * a }.reduce(0, +) / Double(samples.count)
        return (a, b)
    }

    static func predict(delta: Double, profile: GarageProfile?, defaultHeight: Double) -> Int {
        let c = coefficients(profile: profile, defaultHeight: defaultHeight)
        return Int((c.a * delta + c.b).rounded())
    }
}
