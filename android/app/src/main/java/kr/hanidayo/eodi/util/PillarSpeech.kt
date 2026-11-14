package kr.hanidayo.eodi.util

/**
 * 음성으로 말한 주차 위치를 층·기둥 번호로 바꿈.
 * 예) "지하 3층 에이 십이" → 층 -3, 기둥 A12 / "B3 C15" → -3, C15 / "기둥 가 7번" → 가7
 */
object PillarSpeech {
    data class Result(val level: Int?, val pillar: String?)

    private val letters = listOf(
        // 잘못 알아듣기 쉬운 발음·군대식 알파벳 먼저
        "폭스트롯" to "F", "에이취" to "H", "알파" to "A", "브라보" to "B", "찰리" to "C", "델타" to "D", "에코" to "E",
        "골프" to "G", "호텔" to "H", "애이" to "A", "에치" to "H", "재이" to "J", "캐이" to "K", "앨" to "L", "앰" to "M",
        "앤" to "N", "쥐" to "G", "뒤" to "D", "시" to "C",
        "더블유" to "W", "에이치" to "H", "에이" to "A", "에프" to "F", "에스" to "S", "엑스" to "X", "와이" to "Y",
        "브이" to "V", "제트" to "Z", "지트" to "Z", "제이" to "J", "케이" to "K", "엘" to "L", "엠" to "M", "엔" to "N",
        "큐" to "Q", "알" to "R", "티" to "T", "유" to "U", "피" to "P", "디" to "D", "씨" to "C", "비" to "B",
        "지" to "G", "아이" to "I",
    )
    private val noise = listOf(
        "기둥", "구역", "번째", "번", "쪽", "입니다", "이에요", "예요", "에요", "이야", "야",
        "주차", "했어", "했음", "위치", "근처", "앞", "옆",
    )
    private val digits = mapOf(
        '영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5,
        '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )

    private fun korNum(w: String): Int {
        var total = 0
        var cur = 0
        for (ch in w) {
            when {
                ch in digits -> cur = digits.getValue(ch)
                ch == '십' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                ch == '백' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
            }
        }
        return total + cur
    }

    private fun num(s: String) = s.toIntOrNull() ?: korNum(s)

    fun parse(text: String): Result {
        var s = text.uppercase().replace("-", " ").replace(".", " ").replace(",", " ")
        var level: Int? = null

        val under = Regex("(지하|B|비)\\s*([0-9]+|[일이삼사오육칠팔구십]+)\\s*층").find(s)
            ?: Regex("지하\\s*([0-9]+|[일이삼사오육칠팔구십]+)").find(s)
        if (under != null) {
            level = -num(under.groupValues.last())
            s = s.removeRange(under.range).let { " $it " }
        } else {
            val above = Regex("(?<![A-Z0-9가-힣])([0-9]+|[일이삼사오육칠팔구십]+)\\s*층").find(s)
            if (above != null) {
                val n = num(above.groupValues[1])
                if (n > 0) { level = n - 1; s = s.removeRange(above.range).let { " $it " } }
            }
        }
        noise.forEach { s = s.replace(it, " ") }
        letters.forEach { (k, v) -> s = s.replace(k, " $v ") }
        s = Regex("[일이삼사오육칠팔구십백]+(?![가-힣])|(?<![가-힣])[일이삼사오육칠팔구십백]+")
            .replace(s) { korNum(it.value).toString() }
        s = s.replace(Regex("\\s+"), " ").trim()

        var tokens = Regex("([A-Z가-힣])\\s*([0-9]{1,3})").findAll(s).map { it.groupValues[1] to it.groupValues[2] }.toList()
        // "B3 C15"처럼 층을 B숫자로만 말했을 때
        if (level == null && tokens.size >= 2 && tokens[0].first == "B" && tokens[0].second.toInt() <= 9) {
            level = -tokens[0].second.toInt()
            tokens = tokens.drop(1)
        }
        val pillar = tokens.firstOrNull()?.let { it.first + it.second }
            ?: Regex("[0-9]{1,3}").find(s)?.value
            ?: s.takeIf { it.isNotBlank() && it.length <= 12 }
        return Result(level, pillar)
    }
}
