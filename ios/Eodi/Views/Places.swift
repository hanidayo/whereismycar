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

/// 말하거나 직접 써서 기둥 번호(·층) 저장. 알아들은 결과는 저장 전에 고칠 수 있음
struct VoicePillarSheet: View {
    @EnvironmentObject var store: ParkingStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var listener = SpeechListener()
    let recordID: UUID
    var listenOnOpen = true
    @State private var pillar = ""
    @State private var level: Int?  // nil = 층은 그대로
    @FocusState private var typing: Bool

    var body: some View {
        VStack(spacing: 18) {
            Text(listener.listening ? "듣고 있어요…" : "기둥 위치")
                .font(.system(.title3, design: .rounded).weight(.bold))
                .foregroundStyle(Palette.ink)
                .padding(.top, 24)
            Text("예) \"지하 3층 에이 십이\", \"알파 3\", \"B2 C 15\"")
                .font(.footnote).foregroundStyle(Palette.sub)

            Button {
                typing = false
                if listener.listening { listener.stop() } else { Task { await listener.start() } }
            } label: {
                ZStack {
                    Circle().fill(Palette.primary.opacity(listener.listening ? 0.18 : 0.10))
                        .frame(width: 112, height: 112)
                        .scaleEffect(listener.listening ? 1.12 : 1)
                        .animation(listener.listening ? .easeInOut(duration: 0.8).repeatForever(autoreverses: true) : .default,
                                   value: listener.listening)
                    Circle().fill(LinearGradient(colors: [Palette.primary, Palette.primaryDeep], startPoint: .top, endPoint: .bottom))
                        .frame(width: 78, height: 78)
                    Image(systemName: listener.listening ? "stop.fill" : "mic.fill")
                        .font(.system(size: 30, weight: .bold)).foregroundStyle(.white)
                }
            }
            .buttonStyle(.plain)

            if !listener.text.isEmpty {
                Text("“\(listener.text)”").font(.subheadline).foregroundStyle(Palette.sub).multilineTextAlignment(.center)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("기둥 번호 · 틀렸으면 직접 고쳐 주세요").font(.caption).foregroundStyle(Palette.sub)
                TextField("예: A12", text: $pillar)
                    .font(.system(.title2, design: .rounded).weight(.heavy))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .focused($typing)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Palette.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                HStack {
                    Text("층").foregroundStyle(Palette.ink)
                    Spacer()
                    Menu {
                        Button("그대로 두기") { level = nil }
                        ForEach(Array((-6...4).reversed()), id: \.self) { l in
                            Button(Level.longName(l)) { level = l }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(level.map { Level.longName($0) } ?? "그대로")
                            Image(systemName: "chevron.up.chevron.down").font(.caption)
                        }
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Palette.primary)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Palette.soft, in: Capsule())
                    }
                }
            }

            if let e = listener.error { Text(e).font(.footnote).foregroundStyle(.orange) }
            Spacer(minLength: 0)
            let empty = pillar.trimmingCharacters(in: .whitespaces).isEmpty && level == nil
            Button {
                let p = pillar.trimmingCharacters(in: .whitespaces).uppercased()
                store.applyVoice(recordID, PillarSpeech.Result(level: level, pillar: p.isEmpty ? nil : p))
                dismiss()
            } label: {
                Text("저장").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 15)
                    .foregroundStyle(.white)
                    .background(Palette.primary, in: Capsule())
            }
            .disabled(listener.listening || empty)
            .opacity(listener.listening || empty ? 0.4 : 1)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 20)
        .background(Palette.background.ignoresSafeArea())
        .presentationDetents([.large])
        .onChange(of: listener.alternatives) { _, alts in
            // 인식 후보 중 기둥 번호를 알아들은 첫 문장
            let all = alts.isEmpty ? [listener.text] : alts
            let best = all.map(PillarSpeech.parse).first { $0.pillar != nil } ?? PillarSpeech.parse(listener.text)
            if let p = best.pillar { pillar = p }
            if let l = best.level { level = l }
        }
        .task {
            if pillar.isEmpty { pillar = store.record(recordID)?.pillarText ?? "" }
            if listenOnOpen { await listener.start() } else { typing = true }
        }
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
