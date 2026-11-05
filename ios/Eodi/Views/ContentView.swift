import SwiftUI
import MapKit

// MARK: - 하단 탭

struct RootView: View {
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            ContentView(openHistory: { tab = 1 })
                .tabItem { Label("홈", systemImage: "house.fill") }
                .tag(0)
            HistoryView()
                .tabItem { Label("기록", systemImage: "clock.fill") }
                .tag(1)
            CarMapView()
                .tabItem { Label("위치", systemImage: "map.fill") }
                .tag(2)
            SettingsView()
                .tabItem { Label("설정", systemImage: "gearshape.fill") }
                .tag(3)
        }
        .tint(Palette.primary)
    }
}

// MARK: - 홈

struct ContentView: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder
    var openHistory: () -> Void = {}
    @State private var selectedCar: UUID?

    private var car: Car? {
        selectedCar.flatMap { store.car($0) } ?? recorder.state.flatMap { store.car($0.carID) } ?? store.cars.first
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    PermissionWarning()
                    if let car {
                        HStack {
                            Label("오늘 주차 위치", systemImage: "mappin.and.ellipse")
                                .font(.system(.title3, design: .rounded).weight(.bold))
                                .foregroundStyle(Palette.ink)
                            Spacer()
                            CarPicker(selected: car) { selectedCar = $0 }
                        }
                        .padding(.horizontal, 4)
                        HeroCard(car: car)
                        SectionHeader(title: "감지 상태")
                        HStack(spacing: 12) {
                            DriveStatusTile(car: car)
                            DistanceTile(car: car)
                        }
                    }
                    SectionHeader(title: "최근 기록", trailing: "전체 보기", action: openHistory)
                    RecentList(limit: 4)
                    ManualControls(car: car)
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 24)
            }
            .background(Palette.background.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: UUID.self) { ParkingDetailView(recordID: $0) }
            .refreshable { await store.refresh() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("어디댔지")
                .font(.system(size: 32, weight: .heavy, design: .rounded))
                .foregroundStyle(LinearGradient(colors: [Palette.primary, Palette.primaryDeep],
                                                startPoint: .leading, endPoint: .trailing))
            SyncLine()
        }
        .padding(.top, 8)
        .padding(.horizontal, 4)
    }
}

/// 차 고르기 (오른쪽 위 둥근 버튼)
struct CarPicker: View {
    @EnvironmentObject var store: ParkingStore
    let selected: Car
    let onPick: (UUID) -> Void

    var body: some View {
        Menu {
            ForEach(store.cars) { c in
                Button { onPick(c.id) } label: {
                    if c.id == selected.id { Label(c.name, systemImage: "checkmark") } else { Text(c.name) }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(selected.name)
                if store.cars.count > 1 { Image(systemName: "chevron.down").font(.caption2.weight(.bold)) }
            }
            .font(.subheadline.weight(.bold))
            .foregroundStyle(Palette.primary)
            .padding(.horizontal, 16).padding(.vertical, 9)
            .background(Palette.soft, in: Capsule())
        }
    }
}

/// 제목 아래 한 줄: 가족 공유 상태
struct SyncLine: View {
    @EnvironmentObject var store: ParkingStore

    var body: some View {
        HStack(spacing: 6) {
            switch store.syncStatus {
            case .off:
                Image(systemName: "person.2.slash"); Text("가족 공유 꺼짐")
            case .syncing:
                ProgressView().controlSize(.mini); Text("동기화 중")
            case .ok(let d):
                Image(systemName: "person.2.fill")
                Text("가족과 공유 중 · \(d.formatted(date: .omitted, time: .shortened))")
            case .failed(let msg):
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                Text(msg).lineLimit(1)
            }
        }
        .font(.footnote)
        .foregroundStyle(Palette.sub)
    }
}

/// 보라 그라데이션 큰 카드
struct HeroCard: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder
    let car: Car

    var body: some View {
        let record = store.current(for: car.id)
        let driving = recorder.state?.carID == car.id
        let content = HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 12) {
                Label(driving ? "운전 기록 중" : "현재 위치 감지", systemImage: driving ? "dot.radiowaves.left.and.right" : "location.circle")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.85))
                Group {
                    if driving {
                        Text("주차하면\n자동으로 기록해요")
                    } else if let r = record {
                        Text("\(Level.longName(r.level))에\n주차했어요")
                    } else {
                        Text("아직 주차\n기록이 없어요")
                    }
                }
                .font(.system(size: 28, weight: .heavy, design: .rounded))
                .foregroundStyle(.white)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)

                if driving, let d = recorder.liveDelta {
                    pill(Text("입구 대비 ") + Text(String(format: "%+.1fm", d)).foregroundColor(Color(hex: 0xFFE08A)))
                } else if let r = record {
                    pill(Text("기록 완료 · ") + Text(Level.longName(r.level)).foregroundColor(Color(hex: 0xFFE08A)))
                }
            }
            Spacer(minLength: 0)
            CarIllustration(level: driving ? nil : record?.level, color: Palette.primary, size: 124)
        }
        .padding(22)
        .frame(maxWidth: .infinity, minHeight: 210)
        .background(HeroBackground(color: Palette.primary))

        if let r = record, !driving {
            NavigationLink(value: r.id) { content }.buttonStyle(.plain)
        } else {
            content
        }
    }

    private func pill(_ t: Text) -> some View {
        t.font(.subheadline.weight(.bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(.white.opacity(0.16), in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.35)))
    }
}

/// 감지 상태 카드 공통 모양
struct StatusTile<Icon: View>: View {
    let title: String
    let value: String
    var detail: String? = nil
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        HStack(spacing: 10) {
            icon()
                .frame(width: 46, height: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(Palette.sub)
                Text(value).font(.system(.subheadline, design: .rounded).weight(.bold)).foregroundStyle(Palette.ink)
                    .lineLimit(1).minimumScaleFactor(0.8)
                if let detail { Text(detail).font(.caption).foregroundStyle(Palette.sub).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .card(padding: 14)
    }
}

struct DriveStatusTile: View {
    @EnvironmentObject var recorder: DriveRecorder
    let car: Car

    var body: some View {
        let on = recorder.state?.carID == car.id
        StatusTile(title: "차량 상태", value: on ? "운전 중" : "시동 꺼짐", detail: on ? "기록 중" : nil) {
            ZStack {
                Circle().fill(on ? Color(hex: 0xFF5A6E).opacity(0.14) : Palette.soft)
                if on { PulsingDot() } else {
                    Image(systemName: "power").font(.title3.weight(.semibold)).foregroundStyle(Palette.sub)
                }
            }
        }
    }
}

struct DistanceTile: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder
    let car: Car

    var body: some View {
        let meters: Double? = {
            guard let c = store.current(for: car.id)?.coordinate, let here = recorder.lastKnownLocation else { return nil }
            return CLLocation(latitude: c.latitude, longitude: c.longitude).distance(from: here)
        }()
        StatusTile(title: "위치 상태", value: "차와의 거리", detail: meters.map(Self.format) ?? "알 수 없음") {
            ZStack {
                Circle().fill(Color(hex: 0x5AA9F5).opacity(0.15))
                Image(systemName: "mappin.and.ellipse").font(.title3.weight(.semibold)).foregroundStyle(Color(hex: 0x3F8EE8))
            }
        }
    }

    static func format(_ m: Double) -> String {
        m < 1000 ? String(format: "%.0fm", m) : String(format: "%.1fkm", m / 1000)
    }
}

// MARK: - 기록 목록 (홈 · 기록 탭 공용)

struct RecordRow: View {
    @EnvironmentObject var store: ParkingStore
    let record: ParkingRecord

    var body: some View {
        let garage = record.garageID.flatMap { id in store.garages.first { $0.id == id } }
        let place = garage?.name ?? (record.coordinate == nil ? "위치 없음" : "주차장")
        let home = place.contains("집")
        let color = home ? Palette.primary : Color(hex: 0x2E9E6E)
        HStack(spacing: 14) {
            Image(systemName: home ? "house.fill" : "building.2.fill")
                .font(.title3.weight(.semibold))
                .foregroundStyle(color)
                .frame(width: 52, height: 52)
                .background(color.opacity(0.13), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text("\(place) · \(store.carName(record.carID))")
                    .font(.footnote.weight(.semibold)).foregroundStyle(Palette.sub).lineLimit(1)
                Text(Level.longName(record.level))
                    .font(.system(.title3, design: .rounded).weight(.heavy))
                    .foregroundStyle(color)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 3) {
                Text(friendlyDate(record.date)).font(.footnote).foregroundStyle(Palette.sub)
                Text(record.member).font(.caption).foregroundStyle(Palette.sub.opacity(0.8))
            }
            Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(Palette.sub.opacity(0.6))
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

struct RecentList: View {
    @EnvironmentObject var store: ParkingStore
    var limit: Int

    var body: some View {
        let items = store.records.sorted { $0.date > $1.date }.prefix(limit)
        VStack(spacing: 0) {
            if items.isEmpty {
                Text("아직 주차 기록이 없어요").font(.subheadline).foregroundStyle(Palette.sub)
                    .frame(maxWidth: .infinity).padding(.vertical, 20)
            }
            ForEach(Array(items.enumerated()), id: \.element.id) { i, r in
                NavigationLink(value: r.id) { RecordRow(record: r) }.buttonStyle(.plain)
                if i < items.count - 1 { Divider().padding(.leading, 66) }
            }
        }
        .card(padding: 14)
    }
}

// MARK: - 기록 탭

struct HistoryView: View {
    @EnvironmentObject var store: ParkingStore
    @State private var carFilter: UUID?

    var body: some View {
        NavigationStack {
            let records = store.records
                .filter { carFilter == nil || $0.carID == carFilter }
                .sorted { $0.date > $1.date }
            let days = Dictionary(grouping: records) { Calendar.current.startOfDay(for: $0.date) }
                .sorted { $0.key > $1.key }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            filterChip("전체", on: carFilter == nil) { carFilter = nil }
                            ForEach(store.cars) { c in
                                filterChip(c.name, on: carFilter == c.id) { carFilter = c.id }
                            }
                        }
                        .padding(.horizontal, 4)
                    }
                    if records.isEmpty {
                        Text("기록이 없어요").foregroundStyle(Palette.sub).frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    ForEach(days, id: \.key) { day, items in
                        Text(dayTitle(day)).font(.subheadline.weight(.bold)).foregroundStyle(Palette.sub)
                            .padding(.horizontal, 6)
                        VStack(spacing: 0) {
                            ForEach(Array(items.enumerated()), id: \.element.id) { i, r in
                                NavigationLink(value: r.id) { RecordRow(record: r) }.buttonStyle(.plain)
                                if i < items.count - 1 { Divider().padding(.leading, 66) }
                            }
                        }
                        .card(padding: 14)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 24)
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("주차 기록")
            .navigationDestination(for: UUID.self) { ParkingDetailView(recordID: $0) }
            .refreshable { await store.refresh() }
        }
    }

    private func dayTitle(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return "오늘" }
        if cal.isDateInYesterday(d) { return "어제" }
        return d.formatted(.dateTime.month().day().weekday(.abbreviated))
    }

    private func filterChip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(on ? .white : Palette.primary)
                .padding(.horizontal, 16).padding(.vertical, 9)
                .background(on ? AnyShapeStyle(Palette.primary) : AnyShapeStyle(Palette.soft), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 위치 탭

struct CarMapView: View {
    @EnvironmentObject var store: ParkingStore

    var body: some View {
        NavigationStack {
            let pins: [(Car, ParkingRecord, CLLocationCoordinate2D)] = store.cars.compactMap { car in
                guard let r = store.current(for: car.id), let c = r.coordinate else { return nil }
                return (car, r, c)
            }
            ZStack(alignment: .bottom) {
                Map(initialPosition: .automatic) {
                    UserAnnotation()
                    ForEach(pins, id: \.0.id) { car, r, c in
                        Annotation(car.name, coordinate: c) {
                            PinBadge(text: r.level.map(Level.name) ?? "?", color: CarPalette.color(car.color), size: 38)
                        }
                    }
                }
                .mapControls { MapUserLocationButton(); MapCompass() }

                if !pins.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(pins, id: \.0.id) { car, r, _ in
                                NavigationLink(value: r.id) {
                                    HStack(spacing: 10) {
                                        FloorTile(level: r.level, color: CarPalette.color(car.color), size: 44)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(car.name).font(.subheadline.weight(.bold)).foregroundStyle(Palette.ink)
                                            Text(friendlyDate(r.date)).font(.caption).foregroundStyle(Palette.sub)
                                        }
                                    }
                                    .card(padding: 12)
                                    .frame(width: 210)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .navigationTitle("위치")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: UUID.self) { ParkingDetailView(recordID: $0) }
        }
    }
}

// MARK: - 경고 · 수동 기록

struct PermissionWarning: View {
    @EnvironmentObject var recorder: DriveRecorder
    @Environment(\.openURL) private var openURL

    var body: some View {
        if recorder.authorizationStatus != .authorizedAlways {
            VStack(alignment: .leading, spacing: 8) {
                Label("위치 권한을 '항상'으로 바꿔 주세요", systemImage: "location.slash.fill")
                    .font(.headline).foregroundStyle(.orange)
                Text("화면이 꺼진 채 운전하는 동안에도 기록하려면 필요해요. '정확한 위치'도 켜 주세요.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("설정 열기") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(.bordered).tint(.orange)
            }
            .card(tint: .orange)
        }
    }
}

struct ManualControls: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder
    let car: Car?
    @State private var parking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("수동 기록\(car.map { " · \($0.name)" } ?? "")")
                .font(.system(.headline, design: .rounded)).foregroundStyle(Palette.ink)
            HStack(spacing: 10) {
                Button {
                    if let id = car?.id { recorder.startDrive(carID: id) }
                } label: {
                    Label("운전 시작", systemImage: "car.fill")
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                        .foregroundStyle(Palette.primary)
                        .background(Palette.soft, in: Capsule())
                }
                Button {
                    Task {
                        parking = true
                        await recorder.park(carID: car?.id)
                        parking = false
                    }
                } label: {
                    Label("지금 주차", systemImage: "parkingsign")
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(LinearGradient(colors: [Palette.primary, Palette.primaryDeep],
                                                   startPoint: .leading, endPoint: .trailing), in: Capsule())
                }
                .disabled(parking)
            }
            .buttonStyle(.plain)
            Text("평소에는 단축어 자동화가 대신 눌러 줘요. 위에서 차를 바꾸면 그 차로 기록해요.")
                .font(.caption).foregroundStyle(Palette.sub)
        }
        .card()
    }
}
