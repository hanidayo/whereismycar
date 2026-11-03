import SwiftUI

struct ContentView: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    SyncLine()
                    PermissionWarning()
                    RecordingBanner()
                    ForEach(store.cars) { car in
                        if let r = store.current(for: car.id) {
                            NavigationLink(value: r.id) { ParkingCard(record: r, car: car) }
                                .buttonStyle(.plain)
                        } else {
                            EmptyCard(car: car)
                        }
                    }
                    ManualControls()
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("어디댔지")
            .navigationDestination(for: UUID.self) { ParkingDetailView(recordID: $0) }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .refreshable { await store.refresh() }
            .sheet(isPresented: $showSettings) { SettingsView() }
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
            Spacer()
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 4)
    }
}

struct ParkingCard: View {
    let record: ParkingRecord
    let car: Car

    var body: some View {
        let color = CarPalette.color(car.color)
        HStack(spacing: 14) {
            FloorTile(level: record.level, color: color, size: 76)
            VStack(alignment: .leading, spacing: 5) {
                Text(car.name).font(.headline)
                Text("\(record.member) · \(record.date.formatted(.relative(presentation: .named)))")
                    .font(.subheadline).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    if let p = record.pillarText { Chip(text: p, systemImage: "square.grid.2x2", color: color) }
                    if let d = record.deltaMeters {
                        Text(String(format: "%+.1fm", d))
                            .font(.caption.monospacedDigit()).foregroundStyle(.tertiary)
                    }
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
        }
        .card()
    }
}

struct EmptyCard: View {
    let car: Car
    var body: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 21, style: .continuous)
                .strokeBorder(CarPalette.color(car.color).opacity(0.5), style: StrokeStyle(lineWidth: 2, dash: [6, 5]))
                .frame(width: 76, height: 76)
                .overlay(Image(systemName: "car").font(.title2).foregroundStyle(CarPalette.color(car.color)))
            VStack(alignment: .leading, spacing: 4) {
                Text(car.name).font(.headline)
                Text("아직 주차 기록이 없어요").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .card()
    }
}

struct RecordingBanner: View {
    @EnvironmentObject var store: ParkingStore
    @EnvironmentObject var recorder: DriveRecorder

    var body: some View {
        if let s = recorder.state {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        PulsingDot()
                        Text("운전 기록 중").font(.headline)
                    }
                    Text(store.carName(s.carID)).font(.subheadline)
                    Text(s.refDate.map { "GPS 기준점 \($0.formatted(date: .omitted, time: .shortened))" }
                         ?? "GPS 기준점 찾는 중…")
                        .font(.caption).foregroundStyle(.secondary)
                    if !recorder.isAltimeterAvailable {
                        Text("이 기기는 기압계를 쓸 수 없어요").font(.caption).foregroundStyle(.orange)
                    }
                }
                Spacer()
                if let d = recorder.liveDelta {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(String(format: "%+.1f", d))
                            .font(.system(size: 34, weight: .bold, design: .rounded).monospacedDigit())
                        Text("m").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .card(tint: .red)
        }
    }
}

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
    @State private var selected: UUID?
    @State private var parking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("수동 기록").font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(store.cars) { car in
                        let on = selected == car.id
                        let color = CarPalette.color(car.color)
                        Button { selected = car.id } label: {
                            Text(car.name)
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .foregroundStyle(on ? .white : color)
                                .background(on ? color : color.opacity(0.12), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            HStack(spacing: 10) {
                Button {
                    if let id = selected { recorder.startDrive(carID: id) }
                } label: {
                    Label("운전 시작", systemImage: "car.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Button {
                    Task {
                        parking = true
                        await recorder.park(carID: selected)
                        parking = false
                    }
                } label: {
                    Label("지금 주차", systemImage: "parkingsign").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(parking)
            }
            .controlSize(.large)
            Text("평소에는 단축어 자동화가 대신 눌러 줘요.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .card()
        .onAppear { if selected == nil { selected = recorder.state?.carID ?? store.defaultCar?.id } }
    }
}
