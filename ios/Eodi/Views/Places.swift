import SwiftUI

/// 장소 아이콘 (안드로이드와 같은 키)
struct PlaceIcon: Identifiable, Hashable {
    let key: String
    let label: String
    let symbol: String
    let color: Color
    var id: String { key }

    static let all: [PlaceIcon] = [
        .init(key: "home", label: "집", symbol: "house.fill", color: Color(hex: 0x7C6CF2)),
        .init(key: "office", label: "회사", symbol: "building.2.fill", color: Color(hex: 0x4A7BE0)),
        .init(key: "school", label: "학교", symbol: "graduationcap.fill", color: Color(hex: 0xF5A623)),
        .init(key: "department", label: "백화점", symbol: "bag.fill", color: Color(hex: 0xF06CA8)),
        .init(key: "mart", label: "마트", symbol: "cart.fill", color: Color(hex: 0xFF7A45)),
        .init(key: "airport", label: "공항", symbol: "airplane", color: Color(hex: 0x3BA3F0)),
        .init(key: "train", label: "기차역", symbol: "tram.fill", color: Color(hex: 0x2E9E6E)),
        .init(key: "hospital", label: "병원", symbol: "cross.case.fill", color: Color(hex: 0xEF5350)),
        .init(key: "restaurant", label: "식당", symbol: "fork.knife", color: Color(hex: 0xE58A2E)),
        .init(key: "cafe", label: "카페", symbol: "cup.and.saucer.fill", color: Color(hex: 0x9C6B4E)),
        .init(key: "hotel", label: "호텔", symbol: "bed.double.fill", color: Color(hex: 0x8E6CD8)),
        .init(key: "park", label: "공원", symbol: "tree.fill", color: Color(hex: 0x5BB04B)),
        .init(key: "gym", label: "운동", symbol: "figure.run", color: Color(hex: 0x14A3A3)),
        .init(key: "cinema", label: "영화관", symbol: "film.fill", color: Color(hex: 0x5C6BC0)),
        .init(key: "gas", label: "주유소", symbol: "fuelpump.fill", color: Color(hex: 0x607D8B)),
        .init(key: "church", label: "교회", symbol: "building.columns.fill", color: Color(hex: 0xA1887F)),
        .init(key: "family", label: "가족·친척", symbol: "heart.fill", color: Color(hex: 0xFF6B8B)),
        .init(key: "parking", label: "주차장", symbol: "parkingsign", color: Color(hex: 0x8E94AD)),
    ]

    static func find(_ key: String?) -> PlaceIcon? { all.first { $0.key == key } }

    /// 아이콘을 안 고른 곳: 이름에 "집"이 있으면 집, 아니면 주차장
    static func resolve(icon: String?, name: String?) -> PlaceIcon {
        if let p = find(icon) { return p }
        if let name, name.contains("집") { return all[0] }
        return find("parking")!
    }
}

/// 둥근 사각형 안의 장소 아이콘
struct PlaceBadge: View {
    let place: PlaceIcon
    var size: CGFloat = 52

    var body: some View {
        Image(systemName: place.symbol)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundStyle(place.color)
            .frame(width: size, height: size)
            .background(place.color.opacity(0.14), in: RoundedRectangle(cornerRadius: size * 0.31, style: .continuous))
    }
}

/// 아이콘 고르기 격자
struct PlaceIconGrid: View {
    @Binding var selected: String?

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 12) {
            ForEach(PlaceIcon.all) { p in
                Button { selected = p.key } label: {
                    VStack(spacing: 4) {
                        PlaceBadge(place: p, size: 48)
                            .overlay {
                                RoundedRectangle(cornerRadius: 15, style: .continuous)
                                    .strokeBorder(p.color, lineWidth: selected == p.key ? 2.5 : 0)
                            }
                        Text(p.label).font(.caption2).foregroundStyle(Palette.ink).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
    }
}

/// 주차 기록에서 장소 이름·아이콘 정하기
struct PlaceEditSheet: View {
    @EnvironmentObject var store: ParkingStore
    @Environment(\.dismiss) private var dismiss
    let recordID: UUID
    @State private var name: String
    @State private var icon: String?

    init(recordID: UUID, name: String?, icon: String?) {
        self.recordID = recordID
        _name = State(initialValue: name ?? "")
        _icon = State(initialValue: icon)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("예: 집 정문, 회사, 롯데백화점", text: $name)
                } header: {
                    Text("장소 이름")
                } footer: {
                    Text("이 위치(같은 입구)에 다음에 주차하면 자동으로 이 이름과 아이콘이 붙어요.")
                }
                Section("아이콘") { PlaceIconGrid(selected: $icon) }
            }
            .navigationTitle("장소")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        store.setPlace(for: recordID, name: name, icon: icon)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.large])
    }
}

// MARK: - 음성으로 기둥 위치

/// 말하면 기둥 번호(·층)를 알아듣고 저장
struct VoicePillarSheet: View {
    @EnvironmentObject var store: ParkingStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var listener = SpeechListener()
    let recordID: UUID

    var body: some View {
        let parsed = PillarSpeech.parse(listener.text)
        VStack(spacing: 22) {
            Text(listener.listening ? "듣고 있어요…" : (listener.text.isEmpty ? "기둥 위치를 말해 주세요" : "이렇게 들었어요"))
                .font(.system(.title3, design: .rounded).weight(.bold))
                .foregroundStyle(Palette.ink)
                .padding(.top, 28)
            Text("예) \"지하 3층 에이 십이\", \"B2 C 15\", \"가 7번\"")
                .font(.footnote).foregroundStyle(Palette.sub)

            Button {
                if listener.listening { listener.stop() } else { Task { await listener.start() } }
            } label: {
                ZStack {
                    Circle().fill(Palette.primary.opacity(listener.listening ? 0.18 : 0.10))
                        .frame(width: 128, height: 128)
                        .scaleEffect(listener.listening ? 1.12 : 1)
                        .animation(listener.listening ? .easeInOut(duration: 0.8).repeatForever(autoreverses: true) : .default,
                                   value: listener.listening)
                    Circle().fill(LinearGradient(colors: [Palette.primary, Palette.primaryDeep], startPoint: .top, endPoint: .bottom))
                        .frame(width: 88, height: 88)
                    Image(systemName: listener.listening ? "stop.fill" : "mic.fill")
                        .font(.system(size: 34, weight: .bold)).foregroundStyle(.white)
                }
            }
            .buttonStyle(.plain)

            if !listener.text.isEmpty {
                Text("“\(listener.text)”").font(.body).foregroundStyle(Palette.ink).multilineTextAlignment(.center)
                HStack(spacing: 10) {
                    if let l = parsed.level { Chip(text: Level.longName(l), systemImage: "stairs", color: Palette.primary) }
                    if let p = parsed.pillar { Chip(text: "기둥 \(p)", systemImage: "number", color: Color(hex: 0x2E9E6E)) }
                }
            }
            if let e = listener.error { Text(e).font(.footnote).foregroundStyle(.orange) }
            Spacer()
            Button {
                store.applyVoice(recordID, parsed)
                dismiss()
            } label: {
                Text("저장").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 15)
                    .foregroundStyle(.white)
                    .background(Palette.primary, in: Capsule())
            }
            .disabled(listener.listening || (parsed.pillar == nil && parsed.level == nil))
            .opacity(listener.listening || (parsed.pillar == nil && parsed.level == nil) ? 0.4 : 1)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 20)
        .background(Palette.background.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .task { await listener.start() }
        .onDisappear { listener.stop() }
    }
}

/// 홈·기록 화면의 "기둥 위치 말하기" 카드
struct VoicePillarCard: View {
    let record: ParkingRecord
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(record.pillarText.map { "기둥 \($0)" } ?? "기둥 위치를 말해 보세요")
                        .font(.system(.headline, design: .rounded)).foregroundStyle(Palette.ink)
                    Text(record.pillarText == nil ? "눌러서 말하기 · 층도 같이 말하면 층도 고쳐져요" : "눌러서 다시 말하기")
                        .font(.caption).foregroundStyle(Palette.sub)
                }
                Spacer()
                Image(systemName: "mic.fill")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Palette.primary)
                    .frame(width: 52, height: 52)
                    .background(Palette.soft, in: Circle())
            }
            .card(padding: 16)
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $open) { VoicePillarSheet(recordID: record.id) }
    }
}
