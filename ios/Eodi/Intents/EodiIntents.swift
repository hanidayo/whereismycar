import AppIntents

/// 단축어에서 차를 목록으로 고르게 해 주는 항목. 이름을 바꿔도 ID로 연결돼서 깨지지 않습니다.
struct CarEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "차"
    static var defaultQuery = CarQuery()

    var id: UUID
    var name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

extension CarEntity {
    init(_ car: Car) { self.init(id: car.id, name: car.name) }
}

struct CarQuery: EntityQuery {
    func entities(for identifiers: [CarEntity.ID]) async throws -> [CarEntity] {
        await MainActor.run {
            ParkingStore.shared.cars.filter { identifiers.contains($0.id) }.map { CarEntity($0) }
        }
    }

    func suggestedEntities() async throws -> [CarEntity] {
        await MainActor.run { ParkingStore.shared.cars.map { CarEntity($0) } }
    }
}

/// 단축어 자동화: CarPlay / 블루투스 "연결될 때"
struct StartDriveIntent: AppIntent {
    static var title: LocalizedStringResource = "운전 시작"
    static var description = IntentDescription("주차 층 계산을 위해 위치와 기압 기록을 시작합니다.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "차", description: "비워 두면 연결된 CarPlay로 자동 선택")
    var car: CarEntity?

    @MainActor
    func perform() async throws -> some IntentResult {
        let store = ParkingStore.shared
        var id = car?.id
        // 이미 다른 자동화(블루투스 등)가 차를 정해서 기록 중이면, 차를 비운 자동화는 차를 바꾸지 않음
        if id == nil, let s = DriveRecorder.shared.state, Date().timeIntervalSince(s.startedAt) < 15 * 60 {
            return .result()
        }
        if id == nil {
            id = store.car(for: await CarPlayRoute.waitForCurrent())?.id
                ?? store.carParkedNear(DriveRecorder.shared.lastKnownLocation)?.id
        }
        if let id = id ?? DriveRecorder.shared.state?.carID ?? store.defaultCar?.id {
            DriveRecorder.shared.startDrive(carID: id)
        }
        return .result()
    }
}

/// 단축어 자동화: CarPlay / 블루투스 "연결 해제될 때"
struct ParkIntent: AppIntent {
    static var title: LocalizedStringResource = "주차 기록"
    static var description = IntentDescription("지금 위치와 층을 주차 위치로 저장하고 가족에게 알립니다.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "차", description: "비워 두면 운전 시작 때 고른 차")
    var car: CarEntity?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        // 무선 CarPlay는 블루투스로 연결한 뒤 Wi-Fi로 넘어가면서 블루투스가 끊김 →
        // '블루투스 연결 해제될 때' 자동화가 운전 시작 직후에 실행됨. 그 경우는 무시
        if let s = DriveRecorder.shared.state, Date().timeIntervalSince(s.startedAt) < 2 * 60 {
            return .result(dialog: "운전을 막 시작해서 주차 기록은 건너뛰었어요 (CarPlay 연결 전환)")
        }
        guard let record = await DriveRecorder.shared.park(carID: car?.id) else {
            return .result(dialog: "등록된 차가 없어요.")
        }
        let text = ParkingStore.shared.summary(record)
        return .result(dialog: "\(text)")
    }
}

/// Siri: "어디댔지 확인"
struct WhereIsMyCarIntent: AppIntent {
    static var title: LocalizedStringResource = "주차 위치 확인"
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = ParkingStore.shared
        await store.refresh()
        let lines = store.cars.compactMap { store.current(for: $0.id).map { store.summary($0) } }
        let text = lines.isEmpty ? "저장된 주차 기록이 없어요." : lines.joined(separator: "\n")
        return .result(dialog: "\(text)")
    }
}

struct EodiShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: WhereIsMyCarIntent(),
                    phrases: ["\(.applicationName) 확인", "\(.applicationName) 어디야"],
                    shortTitle: "주차 위치 확인", systemImageName: "parkingsign.circle")
        AppShortcut(intent: ParkIntent(),
                    phrases: ["\(.applicationName) 주차 기록"],
                    shortTitle: "주차 기록", systemImageName: "mappin.and.ellipse")
        AppShortcut(intent: StartDriveIntent(),
                    phrases: ["\(.applicationName) 운전 시작"],
                    shortTitle: "운전 시작", systemImageName: "car.fill")
    }
}
