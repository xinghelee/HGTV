// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * Zhuyin (Bopomofo) input for the TV search box. Typed symbols stay in the text as a trailing run;
 * that run is turned into pinyin for title suggestions (the search API accepts full pinyin and
 * initials), offers simplified characters per syllable, and is replaced by simplified characters
 * before anything is searched.
 *
 * The table (assets/zhuyin.tsv, from tools/gen_zhuyin_table.py) has one line per syllable:
 * bopomofo TAB pinyin TAB candidates for tones 1..5 separated by '|', most frequent first.
 */
class Zhuyin(table: String) {
    data class Syllable(val text: String, val base: String, val tone: Int?)

    private class Entry(val pinyin: String, val tones: List<String>)

    private val entries: Map<String, Entry> =
        table.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || parts[0].isEmpty()) null
            else parts[0] to Entry(parts[1], parts[2].split('|'))
        }.toMap()
    private val longest = entries.keys.maxOfOrNull { it.length } ?: 1

    /** The trailing run of Zhuyin symbols in [text], i.e. what is still being composed. */
    fun composing(text: String): String = text.takeLastWhile(::isSymbol)

    /** Splits a run of symbols into syllables, longest match first; a tone mark closes a syllable. */
    fun segment(run: String): List<Syllable> {
        val out = mutableListOf<Syllable>()
        var i = 0
        while (i < run.length) {
            if (run[i] in TONES) { i++; continue }
            var length = minOf(longest, run.length - i)
            while (length > 1 && run.substring(i, i + length) !in entries) length--
            val base = run.substring(i, i + length)
            var end = i + length
            val tone = run.getOrNull(end)?.let { TONES.indexOf(it) + 1 }?.takeIf { it > 0 }
            if (tone != null) end++
            out += Syllable(run.substring(i, end), base, tone)
            i = end
        }
        return out
    }

    /** Pinyin for a run: full syllables by the table, a lone initial as its pinyin letter(s). */
    fun pinyin(run: String): String =
        segment(run).joinToString("") { entries[it.base]?.pinyin ?: INITIALS[it.base] ?: "" }

    /** Replaces the Zhuyin run at the end of [text] with pinyin, for the suggestion lookup. */
    fun suggestionQuery(text: String): String {
        val run = composing(text)
        return if (run.isEmpty()) text else text.dropLast(run.length) + pinyin(run)
    }

    /** Simplified candidates for one syllable; without a tone the five tone lists are interleaved. */
    fun candidates(syllable: Syllable): List<String> {
        val entry = entries[syllable.base]
        val lists = when {
            entry == null -> entries.filterKeys { it.startsWith(syllable.base) }.values.map { it.tones.joinToString("") }
            syllable.tone != null -> listOf(entry.tones.getOrElse(syllable.tone - 1) { "" })
            else -> entry.tones
        }
        val seen = LinkedHashSet<String>()
        val width = lists.maxOfOrNull { it.length } ?: 0
        for (column in 0 until width) for (list in lists) list.getOrNull(column)?.let { seen += it.toString() }
        return seen.toList()
    }

    /** Candidates for the first syllable still being composed at the end of [text]. */
    fun candidatesFor(text: String): List<String> =
        segment(composing(text)).firstOrNull()?.let(::candidates).orEmpty()

    /** Commits [character] in place of the first composing syllable at the end of [text]. */
    fun commit(text: String, character: String): String {
        val run = composing(text)
        val first = segment(run).firstOrNull() ?: return text
        val start = text.length - run.length
        return text.substring(0, start) + character + text.substring(start + first.text.length)
    }

    /**
     * Text ready to search, with no Zhuyin left. A [hints] title (the current suggestions) whose next
     * characters fit every remaining syllable wins, so ㄓㄢˋㄕㄣˊ with 战神归来 suggested becomes 战神;
     * otherwise each syllable takes its most common simplified character.
     */
    fun finish(text: String, hints: List<String> = emptyList()): String {
        val run = composing(text)
        if (run.isEmpty()) return text
        val committed = text.dropLast(run.length)
        val syllables = segment(run)
        val options = syllables.map { candidates(it).toSet() }
        val fromHint = hints.firstNotNullOfOrNull { hint ->
            if (!hint.startsWith(committed)) return@firstNotNullOfOrNull null
            val next = hint.drop(committed.length).take(syllables.size)
            next.takeIf { it.length == syllables.size && it.withIndex().all { (i, c) -> c.toString() in options[i] } }
        }
        return committed + (fromHint ?: syllables.joinToString("") { candidates(it).firstOrNull().orEmpty() })
    }

    companion object {
        /** Keyboard order: initials, medials, finals, then the five tone keys (ˉ marks tone 1). */
        const val KEYS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"
        private const val TONES = "ˉˊˇˋ˙"
        private val INITIALS = mapOf(
            "ㄅ" to "b", "ㄆ" to "p", "ㄇ" to "m", "ㄈ" to "f", "ㄉ" to "d", "ㄊ" to "t", "ㄋ" to "n",
            "ㄌ" to "l", "ㄍ" to "g", "ㄎ" to "k", "ㄏ" to "h", "ㄐ" to "j", "ㄑ" to "q", "ㄒ" to "x",
            "ㄓ" to "zh", "ㄔ" to "ch", "ㄕ" to "sh", "ㄖ" to "r", "ㄗ" to "z", "ㄘ" to "c", "ㄙ" to "s",
        )

        fun isSymbol(c: Char) = c in KEYS
    }
}
