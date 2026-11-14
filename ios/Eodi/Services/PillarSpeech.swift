import Foundation

/// 음성으로 말한 주차 위치를 층·기둥 번호로 바꿈.
/// 예) "지하 3층 에이 십이" → 층 -3, 기둥 A12 / "B3 C15" → -3, C15 / "기둥 가 7번" → 가7
enum PillarSpeech {
    struct Result: Equatable {
        var level: Int?
        var pillar: String?
    }

    private static let letters: [(String, String)] = [
        // 잘못 알아듣기 쉬운 발음·군대식 알파벳 먼저
        ("폭스트롯", "F"), ("에이취", "H"), ("알파", "A"), ("브라보", "B"), ("찰리", "C"), ("델타", "D"), ("에코", "E"),
        ("골프", "G"), ("호텔", "H"), ("애이", "A"), ("에치", "H"), ("재이", "J"), ("캐이", "K"), ("앨", "L"), ("앰", "M"),
        ("앤", "N"), ("쥐", "G"), ("뒤", "D"), ("시", "C"),
        ("더블유", "W"), ("에이치", "H"), ("에이", "A"), ("에프", "F"), ("에스", "S"), ("엑스", "X"), ("와이", "Y"),
        ("브이", "V"), ("제트", "Z"), ("지트", "Z"), ("제이", "J"), ("케이", "K"), ("엘", "L"), ("엠", "M"), ("엔", "N"),
        ("큐", "Q"), ("알", "R"), ("티", "T"), ("유", "U"), ("피", "P"), ("디", "D"), ("씨", "C"), ("비", "B"),
        ("지", "G"), ("아이", "I"),
    ]
    private static let noise = ["기둥", "구역", "번째", "번", "쪽", "입니다", "이에요", "예요", "에요", "이야", "야",
                                "주차", "했어", "했음", "위치", "근처", "앞", "옆"]
    private static let digits: [Character: Int] = ["영": 0, "공": 0, "일": 1, "이": 2, "삼": 3, "사": 4, "오": 5,
                                                   "육": 6, "륙": 6, "칠": 7, "팔": 8, "구": 9]

    private static func korNum(_ w: String) -> Int {
        var total = 0, cur = 0
        for ch in w {
            if let d = digits[ch] { cur = d }
            else if ch == "십" { total += (cur == 0 ? 1 : cur) * 10; cur = 0 }
            else if ch == "백" { total += (cur == 0 ? 1 : cur) * 100; cur = 0 }
        }
        return total + cur
    }

    private static func num(_ s: String) -> Int { Int(s) ?? korNum(s) }

    private static func firstMatch(_ pattern: String, in s: String) -> (range: Range<String.Index>, groups: [String])? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range, in: s) else { return nil }
        let groups = (1..<m.numberOfRanges).compactMap { i -> String? in
            Range(m.range(at: i), in: s).map { String(s[$0]) }
        }
        return (r, groups)
    }

    private static func replaceAll(_ pattern: String, in s: String, with f: (String) -> String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        var out = s
        for m in re.matches(in: s, range: NSRange(s.startIndex..., in: s)).reversed() {
            guard let r = Range(m.range, in: out) else { continue }
            out.replaceSubrange(r, with: f(String(out[r])))
        }
        return out
    }

    static func parse(_ text: String) -> Result {
        var s = text.uppercased()
            .replacingOccurrences(of: "-", with: " ")
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: ",", with: " ")
        var level: Int?

        if let m = firstMatch("(지하|B|비)\\s*([0-9]+|[일이삼사오육칠팔구십]+)\\s*층", in: s)
            ?? firstMatch("지하\\s*([0-9]+|[일이삼사오육칠팔구십]+)", in: s) {
            level = -num(m.groups.last ?? "")
            s.replaceSubrange(m.range, with: " ")
        } else if let m = firstMatch("(?<![A-Z0-9가-힣])([0-9]+|[일이삼사오육칠팔구십]+)\\s*층", in: s) {
            let n = num(m.groups.first ?? "")
            if n > 0 { level = n - 1; s.replaceSubrange(m.range, with: " ") }
        }
        for w in noise { s = s.replacingOccurrences(of: w, with: " ") }
        for (k, v) in letters { s = s.replacingOccurrences(of: k, with: " \(v) ") }
        s = replaceAll("[일이삼사오육칠팔구십백]+(?![가-힣])|(?<![가-힣])[일이삼사오육칠팔구십백]+", in: s) { String(korNum($0)) }
        s = s.split(whereSeparator: \.isWhitespace).joined(separator: " ")

        var tokens: [(String, String)] = []
        if let re = try? NSRegularExpression(pattern: "([A-Z가-힣])\\s*([0-9]{1,3})") {
            for m in re.matches(in: s, range: NSRange(s.startIndex..., in: s)) {
                if let a = Range(m.range(at: 1), in: s), let b = Range(m.range(at: 2), in: s) {
                    tokens.append((String(s[a]), String(s[b])))
                }
            }
        }
        // "B3 C15"처럼 층을 B숫자로만 말했을 때
        if level == nil, tokens.count >= 2, tokens[0].0 == "B", let n = Int(tokens[0].1), n <= 9 {
            level = -n
            tokens.removeFirst()
        }
        let pillar = tokens.first.map { $0.0 + $0.1 }
            ?? firstMatch("[0-9]{1,3}", in: s).map { String(s[$0.range]) }
            ?? (s.isEmpty || s.count > 12 ? nil : s)
        return Result(level: level, pillar: pillar)
    }
}
