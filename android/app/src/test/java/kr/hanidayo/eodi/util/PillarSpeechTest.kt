package kr.hanidayo.eodi.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PillarSpeechTest {
    private fun check(text: String, level: Int?, pillar: String?) =
        assertEquals(text, PillarSpeech.Result(level, pillar), PillarSpeech.parse(text))

    @Test fun letters() {
        check("에이 십이", null, "A12")
        check("A12", null, "A12")
        check("씨 십오 구역", null, "C15")
        check("지 칠", null, "G7")
        check("에이 2", null, "A2")
        check("B 12", null, "B12")
        check("기둥 가 7번", null, "가7")
        check("애이 십이", null, "A12")
        check("알파 3", null, "A3")
        check("찰리 15", null, "C15")
        check("시 15", null, "C15")
        check("에이취 23", null, "H23")
        check("쥐 칠", null, "G7")
        check("지하 2층 브라보 7", -2, "B7")
    }

    @Test fun withLevel() {
        check("지하 3층 A 12", -3, "A12")
        check("B3 C15", -3, "C15")
        check("지하 삼층 디 칠번", -3, "D7")
        check("지하2층 E-23", -2, "E23")
        check("2층 F 5", 1, "F5")
        check("b2층 a-3", -2, "A3")
        check("지하 3층 에이치 이십삼", -3, "H23")
        check("지하 1층 나 12번 기둥", -1, "나12")
    }
}
