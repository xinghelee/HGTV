// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ZhuyinTest {
    private val zhuyin = Zhuyin(
        listOf("../app/src/main/assets/zhuyin.tsv", "app/src/main/assets/zhuyin.tsv")
            .map(::File).first { it.isFile }.readText()
    )

    @Test fun segmentsGreedilyAndReadsTones() {
        assertEquals(listOf("ㄒㄧㄡ", "ㄒㄧㄢ"), zhuyin.segment("ㄒㄧㄡㄒㄧㄢ").map { it.base })
        assertEquals(listOf(4, null), zhuyin.segment("ㄓㄢˋㄕㄣ").map { it.tone })
    }

    @Test fun suggestionQueryUsesPinyinForTheComposingRun() {
        assertEquals("xiuxian", zhuyin.suggestionQuery("ㄒㄧㄡㄒㄧㄢ"))
        assertEquals("修xian", zhuyin.suggestionQuery("修ㄒㄧㄢ"))
        assertEquals("zhanshen", zhuyin.suggestionQuery("ㄓㄢˋㄕㄣˊ"))
        assertEquals("x", zhuyin.suggestionQuery("ㄒ"))
        assertEquals("战神", zhuyin.suggestionQuery("战神"))
    }

    @Test fun candidatesAreSimplifiedAndRespectTheTone() {
        assertEquals("修", zhuyin.candidatesFor("ㄒㄧㄡ").first())
        assert("战" in zhuyin.candidatesFor("ㄓㄢˋ").take(5))
        assertEquals("绿", zhuyin.candidates(Zhuyin.Syllable("ㄌㄩˋ", "ㄌㄩ", 4)).take(2).last())
    }

    @Test fun commitReplacesOnlyTheFirstComposingSyllable() {
        assertEquals("修ㄒㄧㄢ", zhuyin.commit("ㄒㄧㄡㄒㄧㄢ", "修"))
        assertEquals("修仙", zhuyin.commit("修ㄒㄧㄢ", "仙"))
    }

    @Test fun finishLeavesNoZhuyinBehind() {
        assertEquals("战神", zhuyin.finish("ㄓㄢˋㄕㄣˊ", listOf("斩神于巅", "战神归来：猎王的养家路")))
        assertEquals(2, zhuyin.finish("ㄓㄢˋㄕㄣˊ").length)
        assertEquals("修仙", zhuyin.finish("修ㄒㄧㄢ", listOf("修仙系列")))
        val finished = zhuyin.finish("ㄒㄧㄡㄒㄧㄢ")
        assertEquals(2, finished.length)
        assert(finished.none(Zhuyin::isSymbol))
        assertEquals("全部类型", zhuyin.finish("全部类型"))
    }
}
