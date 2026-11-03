import SwiftUI

enum CarPalette {
    static let colors: [Color] = [
        Color(red: 0.10, green: 0.62, blue: 0.56),  // 청록
        Color(red: 0.33, green: 0.32, blue: 0.80),  // 남보라
        Color(red: 0.93, green: 0.45, blue: 0.20),  // 주황
        Color(red: 0.85, green: 0.30, blue: 0.48),  // 분홍
        Color(red: 0.30, green: 0.60, blue: 0.20),  // 초록
        Color(red: 0.16, green: 0.49, blue: 0.86),  // 파랑
        Color(red: 0.55, green: 0.42, blue: 0.30),  // 갈색
        Color(red: 0.40, green: 0.42, blue: 0.47),  // 회색
    ]
    static var count: Int { colors.count }
    static func color(_ i: Int) -> Color { colors[((i % count) + count) % count] }
}

/// 층 표시 타일: 차 색으로 칠한 둥근 사각형 안에 B3 같은 층 이름
struct FloorTile: View {
    let level: Int?
    let color: Color
    var size: CGFloat = 72

    var body: some View {
        Text(level.map(Level.name) ?? "?")
            .font(.system(size: size * 0.4, weight: .heavy, design: .rounded))
            .minimumScaleFactor(0.5)
            .lineLimit(1)
            .foregroundStyle(.white)
            .padding(size * 0.08)
            .frame(width: size, height: size)
            .background(color.gradient, in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
    }
}

struct CardStyle: ViewModifier {
    var tint: Color? = nil
    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(tint.map { $0.opacity(0.10) } ?? Color(.secondarySystemGroupedBackground))
            }
    }
}

extension View {
    func card(tint: Color? = nil) -> some View { modifier(CardStyle(tint: tint)) }
}

/// 작은 캡슐 라벨 (기둥 번호, 보정됨 등)
struct Chip: View {
    let text: String
    var systemImage: String? = nil
    var color: Color = .secondary

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage { Image(systemName: systemImage) }
            Text(text)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(color.opacity(0.12), in: Capsule())
    }
}

/// 깜빡이는 빨간 점 (기록 중 표시)
struct PulsingDot: View {
    @State private var on = false
    var body: some View {
        Circle()
            .fill(.red)
            .frame(width: 10, height: 10)
            .opacity(on ? 0.35 : 1)
            .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: on)
            .onAppear { on = true }
    }
}
