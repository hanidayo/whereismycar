import Foundation
import CoreLocation
import UIKit
import SwiftUI // remove(atOffsets:), move(fromOffsets:toOffset:)

private struct StoreData: Codable {
    var cars: [Car]
    var records: [ParkingRecord]
    var garages: [GarageProfile]
    var defaultFloorHeight: Double
    var sync: SyncSettings
    /// 이 아이폰에서만 쓰는 CarPlay 장치 ↔ 차 연결 (예전 파일엔 없음)
    var carPlayLinks: [CarPlayLink]?
}

/// CarPlay 오디오 장치(UID)를 어느 차로 볼지
struct CarPlayLink: Codable, Equatable {
    var uid: String
    var name: String
    var carID: UUID
}

@MainActor
final class ParkingStore: ObservableObject {
    static let shared = ParkingStore()

    @Published private(set) var cars: [Car] { didSet { save() } }
    @Published private(set) var records: [ParkingRecord] { didSet { save() } }
    @Published private(set) var garages: [GarageProfile] { didSet { save() } }
    @Published var defaultFloorHeight: Double { didSet { save() } }
    @Published var sync: SyncSettings { didSet { save() } }
    @Published private(set) var syncStatus: SyncStatus = .off
    @Published private(set) var carPlayLinks: [CarPlayLink] = [] { didSet { save() } }

    /// 같은 주차장으로 볼 반경(m)
    /// 입구(=GPS가 끊기기 직전 위치) 기준. 경사지처럼 입구마다 높이가 다른 곳은 입구별로 따로 학습되게 좁게 잡음
    static let garageRadius: CLLocationDistance = 60

    private static let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    private static let fileURL = docs.appendingPathComponent("eodi-store.json")
    private static let photoDir = docs.appendingPathComponent("photos", isDirectory: true)

    init() {
        if let data = try? Data(contentsOf: Self.fileURL),
           let d = try? JSONDecoder().decode(StoreData.self, from: data) {
            cars = d.cars; records = d.records; garages = d.garages
            defaultFloorHeight = d.defaultFloorHeight; sync = d.sync
            carPlayLinks = d.carPlayLinks ?? []
        } else {
            cars = [Car(name: "CarPlay 차", color: 0), Car(name: "블루투스 차", color: 1)]
            records = []; garages = []; defaultFloorHeight = 3.3; sync = SyncSettings()
        }
        try? FileManager.default.createDirectory(at: Self.photoDir, withIntermediateDirectories: true)
    }

    var memberName: String { sync.member.isEmpty ? "나" : sync.member }
    var defaultCar: Car? { cars.first }

    func car(_ id: UUID) -> Car? { cars.first { $0.id == id } }
    func carName(_ id: UUID) -> String { car(id)?.name ?? "삭제된 차" }
    func carColor(_ id: UUID) -> Int { car(id)?.color ?? 0 }

    // MARK: - CarPlay로 차 구분

    func carPlayLink(for carID: UUID) -> CarPlayLink? { carPlayLinks.first { $0.carID == carID } }

    /// 지금 연결된 CarPlay를 이 차로 지정 (한 장치는 한 차에만)
    func linkCarPlay(_ info: CarPlayRoute.Info, to carID: UUID) {
        carPlayLinks.removeAll { $0.uid == info.uid || $0.carID == carID }
        carPlayLinks.append(CarPlayLink(uid: info.uid, name: info.name, carID: carID))
    }

    func unlinkCarPlay(carID: UUID) { carPlayLinks.removeAll { $0.carID == carID } }

    /// 연결된 CarPlay에 맞는 차 (UID가 같거나, 없으면 이름이 같은 것)
    func car(for info: CarPlayRoute.Info?) -> Car? {
        guard let info else { return nil }
        let link = carPlayLinks.first { $0.uid == info.uid }
            ?? carPlayLinks.first { $0.name == info.name }
        return link.flatMap { car($0.carID) }
    }

    /// CarPlay로 못 고를 때: 지금 위치 근처(300m)에 마지막으로 주차된 차가 딱 한 대면 그 차
    func carParkedNear(_ location: CLLocation?) -> Car? {
        guard let location else { return nil }
        let near = cars.filter { car in
            guard let c = current(for: car.id)?.coordinate else { return false }
            return CLLocation(latitude: c.latitude, longitude: c.longitude).distance(from: location) <= 300
        }
        return near.count == 1 ? near[0] : nil
    }

    func current(for carID: UUID) -> ParkingRecord? {
        records.filter { $0.carID == carID }.max { $0.date < $1.date }
    }

    func record(_ id: UUID) -> ParkingRecord? { records.first { $0.id == id } }

    func summary(_ r: ParkingRecord) -> String {
        var s = "\(carName(r.carID)): \(r.levelName)"
        if let p = r.pillarText { s += " · \(p)" }
        if r.coordinate == nil { s += " (위치 없음)" }
        return s
    }

    /// 진입 방향이 이만큼 넘게 다르면 가까워도 다른 입구로 봄
    static let entranceCourseTolerance: Double = 70

    /// 같은 입구(가까운 위치 + 비슷한 진입 방향)로 학습된 주차장
    func garage(near c: CLLocationCoordinate2D, course: Double?) -> GarageProfile? {
        let here = CLLocation(latitude: c.latitude, longitude: c.longitude)
        return garages
            .map { ($0, $0.location.distance(from: here)) }
            .filter { $0.1 <= Self.garageRadius }
            .filter { g, _ in
                guard let course, let gc = g.course else { return true }
                return angleDifference(course, gc) <= Self.entranceCourseTolerance
            }
            .min { $0.1 < $1.1 }?.0
    }

    // MARK: - 차

    @discardableResult
    func addCar() -> Car {
        let used = Set(cars.map(\.color))
        let color = (0..<CarPalette.count).first { !used.contains($0) } ?? cars.count % CarPalette.count
        let car = Car(name: "새 차", color: color)
        cars.append(car)
        push { try await $0.putCar(car) }
        return car
    }

    func updateCar(_ car: Car) {
        guard let i = cars.firstIndex(where: { $0.id == car.id }) else { return }
        var c = car
        c.name = c.name.trimmingCharacters(in: .whitespaces)
        if c.name.isEmpty { c.name = cars[i].name }
        guard cars[i] != c else { return }
        cars[i] = c
        push { try await $0.putCar(c) }
    }

    func deleteCars(at offsets: IndexSet) {
        let ids = offsets.map { cars[$0].id }
        cars.remove(atOffsets: offsets)
        for id in ids { push { try await $0.deleteCar(id) } }
    }

    func moveCars(from: IndexSet, to: Int) { cars.move(fromOffsets: from, toOffset: to) }

    // MARK: - 주차 기록

    func recordParking(carID: UUID, coordinate: CLLocationCoordinate2D?,
                       accuracy: Double?, delta: Double?, course: Double? = nil) -> ParkingRecord {
        var r = ParkingRecord(carID: carID, member: memberName, date: Date())
        r.latitude = coordinate?.latitude
        r.longitude = coordinate?.longitude
        r.accuracy = accuracy
        r.deltaMeters = delta
        r.course = course
        let profile = coordinate.flatMap { garage(near: $0, course: course) }
        r.garageID = profile?.id
        if let delta {
            r.level = FloorModel.predict(delta: delta, profile: profile, defaultHeight: defaultFloorHeight)
        }
        records.append(r)
        if records.count > 300 { records.removeFirst(records.count - 300) }
        Task { await uploadPending() }
        return r
    }

    /// 실제 층으로 고치고, 그 주차장 학습값에 반영
    func correct(_ id: UUID, to level: Int) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        var r = records[i]
        r.level = level
        r.calibrated = true

        var changedGarage: GarageProfile?
        if let delta = r.deltaMeters, let c = r.coordinate {
            let gi = garages.firstIndex { $0.id == r.garageID }
                ?? garage(near: c, course: r.course).flatMap { g in garages.firstIndex { $0.id == g.id } }
            var g = gi.map { garages[$0] } ?? GarageProfile(latitude: c.latitude, longitude: c.longitude, course: r.course)
            if g.course == nil { g.course = r.course }
            g.samples.removeAll { $0.recordID == id }
            g.samples.append(GarageSample(recordID: id, delta: delta, level: level))
            if g.samples.count > 10 { g.samples.removeFirst(g.samples.count - 10) }
            if let gi { garages[gi] = g } else { garages.append(g) }
            r.garageID = g.id
            changedGarage = g
        }
        records[i] = r
        if r.synced { push { try await $0.patchParking(r) } }
        if let g = changedGarage { push { try await $0.putGarage(g) } }
    }

    /// 다른 차로 잘못 기록됐을 때
    func changeCar(_ id: UUID, to carID: UUID) {
        guard let i = records.firstIndex(where: { $0.id == id }), records[i].carID != carID else { return }
        records[i].carID = carID
        let r = records[i]
        if r.synced { push { try await $0.patchParking(r) } }
    }

    func renameGarage(_ id: UUID, to name: String) {
        guard let i = garages.firstIndex(where: { $0.id == id }) else { return }
        garages[i].name = name.isEmpty ? nil : name
        let g = garages[i]
        push { try await $0.putGarage(g) }
    }

    func deleteGarages(at offsets: IndexSet) {
        let ids = offsets.map { garages[$0].id }
        garages.remove(atOffsets: offsets)
        for id in ids { push { try await $0.deleteGarage(id) } }
    }

    // MARK: - 사진 + OCR

    func attachPhoto(_ image: UIImage, to id: UUID) async {
        guard let i = records.firstIndex(where: { $0.id == id }),
              let jpeg = Self.resized(image).jpegData(compressionQuality: 0.7) else { return }
        if let old = records[i].photoFile {
            try? FileManager.default.removeItem(at: Self.photoDir.appendingPathComponent(old))
        }
        let name = "\(id.uuidString)-\(Int(Date().timeIntervalSince1970)).jpg"
        try? jpeg.write(to: Self.photoDir.appendingPathComponent(name))
        records[i].photoFile = name
        records[i].photoPending = true

        let result = await PillarOCR.recognize(image)
        guard let j = records.firstIndex(where: { $0.id == id }) else { return }
        if let p = result.pillar { records[j].pillarText = p }
        records[j].ocrLevel = result.level
        let r = records[j]
        if r.synced { push { try await $0.patchParking(r) } }
        await uploadPending()
    }

    func photo(for r: ParkingRecord) -> UIImage? {
        guard let f = r.photoFile else { return nil }
        return UIImage(contentsOfFile: Self.photoDir.appendingPathComponent(f).path)
    }

    /// 가족이 올린 사진 내려받기
    func fetchRemotePhoto(_ id: UUID) async {
        guard let client = SyncClient(settings: sync),
              let r = record(id), r.hasRemotePhoto, r.photoFile == nil,
              let data = try? await client.photo(id) else { return }
        let name = "\(id.uuidString)-remote.jpg"
        try? data.write(to: Self.photoDir.appendingPathComponent(name))
        if let i = records.firstIndex(where: { $0.id == id }) { records[i].photoFile = name }
    }

    private static func resized(_ image: UIImage, maxSide: CGFloat = 1600) -> UIImage {
        let side = max(image.size.width, image.size.height)
        guard side > maxSide else { return image }
        let scale = maxSide / side
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    // MARK: - 동기화

    private func push(_ op: @escaping (SyncClient) async throws -> Void) {
        guard let client = SyncClient(settings: sync) else { return }
        Task {
            do { try await op(client); syncStatus = .ok(Date()) }
            catch { syncStatus = .failed(error.localizedDescription) }
        }
    }

    /// 서버 상태를 받아 합치고, 아직 못 올린 기록을 올림
    func refresh() async {
        guard let client = SyncClient(settings: sync) else { syncStatus = .off; return }
        syncStatus = .syncing
        do {
            let remote = try await client.state()

            if remote.cars.isEmpty {
                for c in cars { try await client.putCar(c) } // 첫 기기: 내 차 목록을 올림
            } else {
                cars = remote.cars.compactMap { rc in
                    UUID(uuidString: rc.id).map { Car(id: $0, name: rc.name, color: rc.color) }
                }
            }

            var merged = records
            for rp in remote.parkings {
                if let i = merged.firstIndex(where: { $0.id.uuidString.lowercased() == rp.id }) {
                    merged[i].apply(remote: rp)
                    merged[i].synced = true
                } else if let r = ParkingRecord(remote: rp) {
                    merged.append(r)
                }
            }
            records = merged

            let remoteGarages = remote.garages.compactMap(GarageProfile.init(remote:))
            let localOnly = garages.filter { g in !remoteGarages.contains { $0.id == g.id } }
            garages = remoteGarages + localOnly
            for g in localOnly { try await client.putGarage(g) }

            await uploadPending()
            if case .failed = syncStatus {} else { syncStatus = .ok(Date()) }
        } catch {
            syncStatus = .failed(error.localizedDescription)
        }
    }

    func uploadPending() async {
        guard let client = SyncClient(settings: sync) else { return }
        do {
            for r in records where !r.synced {
                try await client.postParking(r)
                if let i = records.firstIndex(where: { $0.id == r.id }) { records[i].synced = true }
            }
            for r in records where r.synced && r.photoPending {
                guard let img = photo(for: r), let jpeg = img.jpegData(compressionQuality: 0.7) else { continue }
                try await client.uploadPhoto(r.id, jpeg: jpeg)
                if let i = records.firstIndex(where: { $0.id == r.id }) {
                    records[i].photoPending = false
                    records[i].hasRemotePhoto = true
                }
            }
            syncStatus = .ok(Date())
        } catch {
            syncStatus = .failed(error.localizedDescription)
        }
    }

    func testConnection() async -> String {
        guard let client = SyncClient(settings: sync) else { return "서버 주소와 가족 코드를 입력하세요." }
        do {
            let s = try await client.state()
            await refresh()
            return "연결됨 · 차 \(s.cars.count)대"
        } catch {
            return error.localizedDescription
        }
    }

    // MARK: - 저장

    private func save() {
        let d = StoreData(cars: cars, records: records, garages: garages,
                          defaultFloorHeight: defaultFloorHeight, sync: sync, carPlayLinks: carPlayLinks)
        if let data = try? JSONEncoder().encode(d) { try? data.write(to: Self.fileURL, options: .atomic) }
    }
}
