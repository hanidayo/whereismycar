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

    var location: CLLocation { CLLocation(latitude: latitude, longitude: longitude) }
}

enum Level {
    static func name(_ i: Int) -> String { i >= 0 ? "\(i + 1)F" : "B\(-i)" }
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
        guard let samples = profile?.samples, let last = samples.last else { return fallback }

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
        if last.level != 0 {
            let h = last.delta / Double(last.level)
            if (2.0...6.0).contains(h) { return (1 / h, 0) }
        }
        return (1.0 / defaultHeight, Double(last.level) - last.delta / defaultHeight)
    }

    static func predict(delta: Double, profile: GarageProfile?, defaultHeight: Double) -> Int {
        let c = coefficients(profile: profile, defaultHeight: defaultHeight)
        return Int((c.a * delta + c.b).rounded())
    }
}
