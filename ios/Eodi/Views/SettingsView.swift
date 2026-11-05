import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: ParkingStore
    @State private var testResult: String?
    @State private var testing = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("서버 주소 (예: eodi.hanidayo.kr)", text: $store.sync.serverURL)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    SecureField("가족 코드", text: $store.sync.token)
                    TextField("내 이름 (알림에 표시)", text: $store.sync.member)
                    Button {
                        Task { testing = true; testResult = await store.testConnection(); testing = false }
                    } label: {
                        HStack { Text("연결 확인"); Spacer(); if testing { ProgressView() } }
                    }
                    if let testResult { Text(testResult).font(.footnote).foregroundStyle(.secondary) }
                } header: {
                    Text("가족 공유")
                } footer: {
                    Text("가족 모두 같은 서버 주소와 가족 코드를 넣으면 주차 기록과 차 목록이 공유돼요.")
                }

                Section {
                    ForEach(store.cars) { car in
                        NavigationLink { CarEditView(car: car) } label: {
                            HStack(spacing: 12) {
                                Circle().fill(CarPalette.color(car.color)).frame(width: 14, height: 14)
                                Text(car.name)
                            }
                        }
                    }
                    .onDelete { store.deleteCars(at: $0) }
                    .onMove { store.moveCars(from: $0, to: $1) }
                    Button { store.addCar() } label: { Label("차 추가", systemImage: "plus") }
                } header: {
                    Text("차")
                } footer: {
                    Text("이름을 바꿔도 기록과 단축어가 그대로 이어져요.")
                }

                Section {
                    Stepper(value: $store.defaultFloorHeight, in: 2.5...4.5, step: 0.1) {
                        LabeledContent("기본 층 높이", value: String(format: "%.1fm", store.defaultFloorHeight))
                    }
                } footer: {
                    Text("처음 가는 주차장에서 쓰는 값이에요. 한 번 보정한 주차장은 학습한 값을 써요.")
                }

                Section("장소 · 학습된 주차장") {
                    if store.garages.isEmpty {
                        Text("주차 기록에서 장소 이름을 정하거나 층을 보정하면 여기에 생겨요.").foregroundStyle(.secondary)
                    }
                    ForEach(store.garages) { g in
                        NavigationLink { GarageEditView(garage: g) } label: {
                            let c = FloorModel.coefficients(profile: g, defaultHeight: store.defaultFloorHeight)
                            HStack(spacing: 12) {
                                PlaceBadge(place: PlaceIcon.resolve(icon: g.icon, name: g.name), size: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(g.name ?? String(format: "%.4f, %.4f", g.latitude, g.longitude))
                                    Text(g.samples.isEmpty ? "층 보정 전"
                                         : String(format: "보정 %d회 · 층 높이 %.1fm", g.samples.count, 1 / c.a))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .onDelete { store.deleteGarages(at: $0) }
                }

                Section {
                    NavigationLink("단축어 자동화 연결 방법") { ShortcutGuideView() }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("설정")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { EditButton() }
            }
        }
    }
}

struct CarEditView: View {
    @EnvironmentObject var store: ParkingStore
    @State private var car: Car
    @State private var detected: CarPlayRoute.Info?
    @State private var routeLines: [String] = []

    init(car: Car) { _car = State(initialValue: car) }

    var body: some View {
        Form {
            Section("이름") {
                TextField("차 이름", text: $car.name)
            }
            Section("색") {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 14) {
                    ForEach(0..<CarPalette.count, id: \.self) { i in
                        Button { car.color = i } label: {
                            Circle()
                                .fill(CarPalette.color(i))
                                .frame(width: 40, height: 40)
                                .overlay {
                                    if car.color == i {
                                        Image(systemName: "checkmark").font(.headline).foregroundStyle(.white)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 6)
            }
            Section {
                if let link = store.carPlayLink(for: car.id) {
                    LabeledContent("연결된 CarPlay", value: link.name)
                } else {
                    Text("아직 CarPlay를 지정하지 않았어요").foregroundStyle(.secondary)
                }
                LabeledContent("지금 감지된 CarPlay", value: detected?.name ?? "없음")
                if let detected {
                    Button("지금 연결된 CarPlay를 이 차로 지정") { store.linkCarPlay(detected, to: car.id) }
                        .disabled(store.carPlayLink(for: car.id)?.uid == detected.uid)
                }
                if store.carPlayLink(for: car.id) != nil {
                    Button("CarPlay 지정 해제", role: .destructive) { store.unlinkCarPlay(carID: car.id) }
                }
                Button("다시 감지") { refreshRoute() }
                DisclosureGroup("오디오 출력 진단") {
                    if routeLines.isEmpty {
                        Text("출력 정보 없음").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(routeLines, id: \.self) { Text($0).font(.caption.monospaced()) }
                }
            } header: {
                Text("CarPlay")
            } footer: {
                Text("이 차의 CarPlay에 연결된 상태에서 지정해 두면, 단축어 '운전 시작'에서 차를 비워 둬도 이 차로 기록해요. CarPlay로 못 고르면 지금 위치 근처에 주차된 차가 한 대뿐일 때 그 차로 기록해요.")
            }
            Section {
                HStack {
                    Spacer()
                    FloorTile(level: -3, color: CarPalette.color(car.color), size: 72)
                    Spacer()
                }
            } header: { Text("미리보기") }
        }
        .navigationTitle(car.name.isEmpty ? "차" : car.name)
        .onAppear { refreshRoute() }
        .onDisappear { store.updateCar(car) }
    }
}

extension CarEditView {
    fileprivate func refreshRoute() {
        detected = CarPlayRoute.current()
        routeLines = CarPlayRoute.describeRoute()
    }
}

struct GarageEditView: View {
    @EnvironmentObject var store: ParkingStore
    let garage: GarageProfile
    @State private var name: String
    @State private var icon: String?

    init(garage: GarageProfile) {
        self.garage = garage
        _name = State(initialValue: garage.name ?? "")
        _icon = State(initialValue: garage.icon)
    }

    var body: some View {
        Form {
            Section("이름") { TextField("예: 집 정문, 회사 지하주차장", text: $name) }
            Section("아이콘") { PlaceIconGrid(selected: $icon) }
            Section("보정 기록") {
                ForEach(Array(garage.samples.enumerated()), id: \.offset) { _, s in
                    LabeledContent(Level.name(s.level), value: String(format: "%+.1fm", s.delta))
                }
            }
        }
        .navigationTitle("장소")
        .onDisappear {
            if name != (garage.name ?? "") || icon != garage.icon { store.updateGarage(garage.id, name: name, icon: icon) }
        }
    }
}

struct ShortcutGuideView: View {
    var body: some View {
        List {
            Section("시작: 차마다 블루투스 '연결될 때'") {
                step(1, "아이폰 블루투스는 켜 두세요. 무선 CarPlay도 블루투스로 먼저 연결돼요.")
                step(2, "단축어 앱 › 자동화 › + › 블루투스 › 이 차의 기기 선택 › '연결될 때' › '즉시 실행'")
                step(3, "동작: 어디댔지 › '운전 시작' › 차를 목록에서 선택")
                step(4, "차가 여러 대면 차마다 반복")
            }
            Section("끝: CarPlay 차는 'CarPlay 연결 해제될 때'") {
                step(1, "무선 CarPlay는 연결되자마자 블루투스가 끊겨서, 블루투스 '연결 해제' 자동화는 쓰면 안 돼요.")
                step(2, "단축어 › 자동화 › + › CarPlay › '연결 해제될 때' › '즉시 실행'")
                step(3, "동작: 어디댔지 › '주차 기록' › 차는 비워 두기 (운전 시작 때 고른 차)")
                step(4, "CarPlay 없는 차는 블루투스 '연결 해제될 때' › '주차 기록'을 쓰면 돼요.")
            }
            Section("유선 CarPlay라 블루투스가 안 붙는 차") {
                step(1, "앱 설정 › 차 › 'CarPlay' › 연결된 상태에서 '지금 연결된 CarPlay를 이 차로 지정'")
                step(2, "단축어 › 자동화 › + › CarPlay › '연결될 때' › '운전 시작' (차는 비워 두기)")
                step(3, "'연결 해제될 때' › '주차 기록' (차는 비워 두기)")
                step(4, "블루투스 자동화와 같이 있어도 기록은 한 번만 남아요.")
            }
            Section("확인") {
                Text("처음 몇 번은 주차 후 앱을 열어 층이 맞는지 보고, 틀리면 '층 보정'을 눌러 주세요. 엘리베이터를 타면 운전 기록 중 화면의 숫자가 층마다 약 3m씩 바뀌어요.")
            }
        }
        .navigationTitle("단축어 연결")
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(n)")
                .font(.caption.weight(.bold)).foregroundStyle(.white)
                .frame(width: 22, height: 22).background(Color.accentColor, in: Circle())
            Text(text)
        }
    }
}
