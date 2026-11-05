import Foundation

/// 네이버 지도 앱으로 차까지 도보 길찾기
enum NaverMap {
    static let appStore = URL(string: "https://apps.apple.com/kr/app/id311867728")!

    static func walkURL(lat: Double, lon: Double, name: String) -> URL? {
        var c = URLComponents()
        c.scheme = "nmap"
        c.host = "route"
        c.path = "/walk"
        c.queryItems = [
            URLQueryItem(name: "dlat", value: String(lat)),
            URLQueryItem(name: "dlng", value: String(lon)),
            URLQueryItem(name: "dname", value: name),
            URLQueryItem(name: "appname", value: Bundle.main.bundleIdentifier ?? "kr.hanidayo.eodi"),
        ]
        return c.url
    }
}
