import UIKit
import Vision
import ImageIO

/// 기둥 사진에서 층(B2, 지하2층 등)과 구역 번호(A-12 등)를 읽음
enum PillarOCR {
    struct Result {
        var lines: [String] = []
        var level: Int?
        var pillar: String?
    }

    static func recognize(_ image: UIImage) async -> Result {
        guard let cg = image.cgImage else { return Result() }
        let orientation = CGImagePropertyOrientation(image.imageOrientation)

        let lines: [String] = await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.recognitionLanguages = ["ko-KR", "en-US"]
                request.usesLanguageCorrection = false
                let handler = VNImageRequestHandler(cgImage: cg, orientation: orientation)
                try? handler.perform([request])
                let texts = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
                cont.resume(returning: texts)
            }
        }
        return parse(lines)
    }

    static func parse(_ lines: [String]) -> Result {
        var result = Result(lines: lines)
        let all = lines.joined(separator: " ")

        if let n = firstInt(#"지하\s*([1-9])\s*층"#, in: all) {
            result.level = -n
        } else if let n = firstInt(#"(?<![A-Za-z0-9])[Bb]\s*([1-9])(?![0-9])"#, in: all) {
            result.level = -n
        } else if let n = firstInt(#"(?<![0-9])([1-9])\s*(?:F|층)(?![0-9])"#, in: all) {
            result.level = n - 1
        }

        // 구역/기둥 번호: A-12, C3, 가-5 같은 짧은 코드
        let codes = matches(#"(?<![A-Za-z0-9])([A-Z]{1,2}|[가-힣])\s*-?\s*[0-9]{1,3}(?![0-9])"#, in: all)
            .map { $0.replacingOccurrences(of: " ", with: "") }
            .filter { $0.range(of: #"^[Bb]-?[1-9]$"#, options: .regularExpression) == nil }
        var unique: [String] = []
        for c in codes where !unique.contains(c) { unique.append(c) }

        if !unique.isEmpty {
            result.pillar = unique.prefix(3).joined(separator: " · ")
        } else {
            let short = lines.filter { (1...10).contains($0.count) }.prefix(2)
            if !short.isEmpty { result.pillar = short.joined(separator: " · ") }
        }
        return result
    }

    private static func firstInt(_ pattern: String, in text: String) -> Int? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: text) else { return nil }
        return Int(text[r])
    }

    private static func matches(_ pattern: String, in text: String) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        return re.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap {
            Range($0.range, in: text).map { String(text[$0]) }
        }
    }
}

extension CGImagePropertyOrientation {
    init(_ o: UIImage.Orientation) {
        switch o {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
