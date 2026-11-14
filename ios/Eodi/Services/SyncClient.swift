import Foundation

struct SyncSettings: Codable, Equatable {
    var serverURL = ""
    var token = ""
    var member = ""

    var isConfigured: Bool {
        !serverURL.trimmingCharacters(in: .whitespaces).isEmpty && !token.isEmpty
    }
}

enum SyncStatus: Equatable {
    case off, syncing, ok(Date), failed(String)
}

// 서버와 주고받는 형식(snake_case)

struct RemoteCar: Codable { var id: String; var name: String; var color: Int }

struct RemoteParking: Codable {
    var id: String
    var carId: String
    var member: String
    var date: Date
    var latitude: Double?
    var longitude: Double?
    var accuracy: Double?
    var deltaMeters: Double?
    var level: Int?
    var calibrated: Bool
    var pillarText: String?
    var ocrLevel: Int?
    var hasPhoto: Bool?
    var course: Double?
}

struct RemoteSample: Codable { var recordId: String; var delta: Double; var level: Int }

struct RemoteGarage: Codable {
    var id: String
    var latitude: Double
    var longitude: Double
    var name: String?
    var course: Double?
    var icon: String?
    var samples: [RemoteSample]
}

struct RemoteState: Codable {
    var cars: [RemoteCar]
    var parkings: [RemoteParking]
    var garages: [RemoteGarage]
    /// 지우거나(다른 폰 포함) 같은 차 중복이라 합쳐진 기록
    var deleted: [String]?
}

/// POST 결과: 같은 차를 다른 폰이 먼저 기록했으면 그 기록으로 합쳐짐
struct PostResult: Codable {
    var created: Bool?
    var mergedInto: String?
    var deleted: Bool?
}

private struct ParkingPatchBody: Encodable {
    var carId: String
    var level: Int?
    var calibrated: Bool
    var pillarText: String?
    var ocrLevel: Int?
}

enum SyncError: LocalizedError {
    case http(Int)
    var errorDescription: String? {
        switch self {
        case .http(401): return "가족 코드가 맞지 않아요."
        case .http(let c): return "서버 응답 오류 (\(c))"
        }
    }
}

struct SyncClient {
    let base: URL
    let token: String

    init?(settings: SyncSettings) {
        var s = settings.serverURL.trimmingCharacters(in: .whitespaces)
        if !s.hasPrefix("http") { s = "https://" + s }
        guard settings.isConfigured, let url = URL(string: s) else { return nil }
        base = url
        token = settings.token
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    private func send(_ path: String, method: String = "GET", json: (any Encodable)? = nil,
                      body: Data? = nil, contentType: String = "application/json") async throws -> Data {
        var req = URLRequest(url: base.appendingPathComponent(path))
        req.httpMethod = method
        req.timeoutInterval = 15
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let json {
            req.httpBody = try Self.encoder.encode(json)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if let body {
            req.httpBody = body
            req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw SyncError.http(code) }
        return data
    }

    private static func key(_ id: UUID) -> String { id.uuidString.lowercased() }

    func state() async throws -> RemoteState {
        try Self.decoder.decode(RemoteState.self, from: try await send("api/state"))
    }

    func putCar(_ car: Car) async throws {
        _ = try await send("api/cars/\(Self.key(car.id))", method: "PUT",
                           json: RemoteCar(id: Self.key(car.id), name: car.name, color: car.color))
    }

    func deleteCar(_ id: UUID) async throws {
        _ = try await send("api/cars/\(Self.key(id))", method: "DELETE")
    }

    @discardableResult
    func postParking(_ r: ParkingRecord) async throws -> PostResult {
        let body = RemoteParking(id: Self.key(r.id), carId: Self.key(r.carID), member: r.member,
                                 date: r.date, latitude: r.latitude, longitude: r.longitude,
                                 accuracy: r.accuracy, deltaMeters: r.deltaMeters, level: r.level,
                                 calibrated: r.calibrated, pillarText: r.pillarText,
                                 ocrLevel: r.ocrLevel, hasPhoto: nil, course: r.course)
        let data = try await send("api/parkings", method: "POST", json: body)
        return (try? Self.decoder.decode(PostResult.self, from: data)) ?? PostResult()
    }

    func deleteParking(_ id: UUID) async throws {
        _ = try await send("api/parkings/\(Self.key(id))", method: "DELETE")
    }

    func patchParking(_ r: ParkingRecord) async throws {
        let body = ParkingPatchBody(carId: Self.key(r.carID), level: r.level, calibrated: r.calibrated,
                                    pillarText: r.pillarText, ocrLevel: r.ocrLevel)
        _ = try await send("api/parkings/\(Self.key(r.id))", method: "PATCH", json: body)
    }

    func uploadPhoto(_ id: UUID, jpeg: Data) async throws {
        _ = try await send("api/parkings/\(Self.key(id))/photo", method: "PUT",
                           body: jpeg, contentType: "image/jpeg")
    }

    func photo(_ id: UUID) async throws -> Data {
        try await send("api/parkings/\(Self.key(id))/photo")
    }

    func putGarage(_ g: GarageProfile) async throws {
        let body = RemoteGarage(id: Self.key(g.id), latitude: g.latitude, longitude: g.longitude,
                                name: g.name, course: g.course, icon: g.icon,
                                samples: g.samples.map {
                                    RemoteSample(recordId: Self.key($0.recordID), delta: $0.delta, level: $0.level)
                                })
        _ = try await send("api/garages/\(Self.key(g.id))", method: "PUT", json: body)
    }

    func deleteGarage(_ id: UUID) async throws {
        _ = try await send("api/garages/\(Self.key(id))", method: "DELETE")
    }
}

extension ParkingRecord {
    init?(remote r: RemoteParking) {
        guard let id = UUID(uuidString: r.id), let car = UUID(uuidString: r.carId) else { return nil }
        self.init(id: id, carID: car, member: r.member, date: r.date)
        apply(remote: r)
        latitude = r.latitude
        longitude = r.longitude
        accuracy = r.accuracy
        deltaMeters = r.deltaMeters
        course = r.course
        synced = true
    }

    mutating func apply(remote r: RemoteParking) {
        level = r.level
        calibrated = r.calibrated
        pillarText = r.pillarText
        ocrLevel = r.ocrLevel
        hasRemotePhoto = r.hasPhoto ?? false
        if let car = UUID(uuidString: r.carId) { carID = car }
    }
}

extension GarageProfile {
    init?(remote g: RemoteGarage) {
        guard let id = UUID(uuidString: g.id) else { return nil }
        self.init(id: id, latitude: g.latitude, longitude: g.longitude, name: g.name,
                  samples: g.samples.compactMap { s in
                      UUID(uuidString: s.recordId).map { GarageSample(recordID: $0, delta: s.delta, level: s.level) }
                  }, course: g.course, icon: g.icon)
    }
}
