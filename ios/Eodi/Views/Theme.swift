import SwiftUI
import UIKit

// MARK: - 색

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }

    /// 라이트·다크 모드에서 다른 색
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { t in
            let hex = t.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255,
                           green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

/// 파스텔 보라 톤의 앱 색
enum Palette {
    static let primary = Color(light: 0x6C5CE7, dark: 0x8F82FF)
    static let primaryDeep = Color(light: 0x5243D6, dark: 0x6E60F0)
    static let background = Color(light: 0xF6F4FF, dark: 0x121022)
    static let card = Color(light: 0xFFFFFF, dark: 0x1E1B33)
    static let soft = Color(light: 0xEFEBFF, dark: 0x2A2650)
    static let ink = Color(light: 0x1F1B3D, dark: 0xF2F0FF)
    static let sub = Color(light: 0x8A86A6, dark: 0x9C98BA)
    static let shadow = Color(light: 0x5B4BDB, dark: 0x000000)
}

/// 차 색 (안드로이드와 같은 순서). 파스텔보다 조금 진하게 — 흰 글씨가 잘 보이게
enum CarPalette {
    static let colors: [Color] = [
        Color(hex: 0x7C6CF2),  // 보라
        Color(hex: 0x36B9A0),  // 민트
        Color(hex: 0xFF8A65),  // 코랄
        Color(hex: 0xF06CA8),  // 핑크
        Color(hex: 0x5AA9F5),  // 하늘
        Color(hex: 0xF5B53F),  // 노랑
        Color(hex: 0x8BC34A),  // 연두
        Color(hex: 0x8E94AD),  // 회색
    ]
    static var count: Int { colors.count }
    static func color(_ i: Int) -> Color { colors[((i % count) + count) % count] }
}

// MARK: - 기본 부품

/// 층 표시 타일: 차 색으로 칠한 둥근 사각형 안에 B3 같은 층 이름
struct FloorTile: View {
    let level: Int?
    let color: Color
    var size: CGFloat = 72

    var body: some View {
        Text(level.map(Level.name) ?? "?")
            .font(.system(size: size * 0.38, weight: .heavy, design: .rounded))
            .minimumScaleFactor(0.5)
            .lineLimit(1)
            .foregroundStyle(.white)
            .padding(size * 0.08)
            .frame(width: size, height: size)
            .background(
                LinearGradient(colors: [color.opacity(0.78), color], startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: size * 0.32, style: .continuous)
            )
    }
}

struct CardStyle: ViewModifier {
    var tint: Color? = nil
    var padding: CGFloat = 16
    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(tint.map { $0.opacity(0.12) } ?? Palette.card)
                    .shadow(color: Palette.shadow.opacity(tint == nil ? 0.07 : 0), radius: 14, y: 6)
            }
    }
}

extension View {
    func card(tint: Color? = nil, padding: CGFloat = 16) -> some View { modifier(CardStyle(tint: tint, padding: padding)) }
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
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(color.opacity(0.13), in: Capsule())
    }
}

/// 깜빡이는 점 (기록 중 표시)
struct PulsingDot: View {
    var color: Color = Color(hex: 0xFF5A6E)
    @State private var on = false
    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 10, height: 10)
            .opacity(on ? 0.35 : 1)
            .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: on)
            .onAppear { on = true }
    }
}

/// 섹션 제목 ("최근 기록" 등)
struct SectionHeader: View {
    let title: String
    var trailing: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title).font(.system(.title3, design: .rounded).weight(.bold)).foregroundStyle(Palette.ink)
            Spacer()
            if let trailing, let action {
                Button(action: action) {
                    HStack(spacing: 2) { Text(trailing); Image(systemName: "chevron.right") }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Palette.primary)
                }
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 6)
    }
}

// MARK: - 귀여운 차 일러스트

/// 핀 모양 뱃지 (B3)
struct PinBadge: View {
    let text: String
    var color: Color
    var size: CGFloat = 46

    var body: some View {
        VStack(spacing: -size * 0.12) {
            Text(text)
                .font(.system(size: size * 0.38, weight: .heavy, design: .rounded))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .foregroundStyle(color)
                .frame(width: size, height: size)
                .background(Circle().fill(.white))
            Triangle().fill(.white).frame(width: size * 0.34, height: size * 0.26)
        }
        .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
    }
}

struct Triangle: Shape {
    func path(in r: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: r.minX, y: r.minY))
            p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
            p.addLine(to: CGPoint(x: r.midX, y: r.maxY))
            p.closeSubpath()
        }
    }
}

/// 둥근 배경 위 차 + 층 핀
struct CarIllustration: View {
    let level: Int?
    let color: Color
    var size: CGFloat = 130

    var body: some View {
        ZStack {
            Circle().fill(.white.opacity(0.16)).frame(width: size, height: size)
            Circle().fill(.white.opacity(0.14)).frame(width: size * 0.72, height: size * 0.72).offset(y: size * 0.1)
            Image(systemName: "car.fill")
                .font(.system(size: size * 0.42, weight: .bold))
                .foregroundStyle(.white, .white.opacity(0.9))
                .shadow(color: .black.opacity(0.15), radius: 6, y: 4)
                .offset(y: size * 0.16)
            PinBadge(text: level.map(Level.name) ?? "?", color: color, size: size * 0.34)
                .offset(y: -size * 0.22)
        }
        .frame(width: size, height: size)
    }
}

/// 큰 그라데이션 카드 배경 (원 두 개로 장식)
struct HeroBackground: View {
    let color: Color
    var body: some View {
        ZStack {
            LinearGradient(colors: [color.opacity(0.82), color], startPoint: .topLeading, endPoint: .bottomTrailing)
            Circle().fill(.white.opacity(0.10)).frame(width: 260).offset(x: 140, y: 90)
            Circle().fill(.white.opacity(0.08)).frame(width: 180).offset(x: -150, y: 110)
        }
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        .shadow(color: color.opacity(0.35), radius: 18, y: 10)
    }
}

/// 날짜를 "오늘 오후 4:05" / "어제 오후 10:52" / "11월 3일 오후 4:05" 로
func friendlyDate(_ d: Date) -> String {
    let cal = Calendar.current
    let time = d.formatted(date: .omitted, time: .shortened)
    if cal.isDateInToday(d) { return "오늘 \(time)" }
    if cal.isDateInYesterday(d) { return "어제 \(time)" }
    return "\(d.formatted(.dateTime.month().day())) \(time)"
}
