#!/usr/bin/env python3
"""Generate app/src/main/assets/zhuyin.tsv for the search page's Zhuyin keyboard.

Each line: <bopomofo syllable without tone> TAB <toneless pinyin> TAB <tone 1..5 candidates, '|' separated>
Candidates are simplified characters (GB2312 levels 1 and 2), most frequent first, so whatever the
user picks is already simplified.

Requires (in a throwaway venv): pip install pypinyin wordfreq jieba
  pypinyin (MIT) supplies readings and Bopomofo; wordfreq (Apache-2.0 code, CC BY-SA 4.0 data) supplies
  the character frequencies used only for ordering.
Usage: python3 tools/gen_zhuyin_table.py > app/src/main/assets/zhuyin.tsv
"""
import sys
from collections import defaultdict

from pypinyin import Style, pinyin
from wordfreq import word_frequency

PER_TONE = 60

def gb2312_chars():
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
            except UnicodeDecodeError:
                continue
            if len(ch) == 1:
                yield ch

def tone_of(bopomofo):
    marks = {"ˊ": 2, "ˇ": 3, "ˋ": 4, "˙": 5}
    for mark, tone in marks.items():
        if mark in bopomofo:
            return bopomofo.replace(mark, ""), tone
    return bopomofo, 1

chars = list(gb2312_chars())

# Bopomofo readings are the source of truth: pypinyin's toneless pinyin collapses readings that differ
# only by tone (神 has ㄕㄣˊ and ㄕㄣ but one "shen"), so pair syllables from unambiguous characters first.
bopo_to_pinyin = {}
for ch in chars:
    plain = pinyin(ch, style=Style.NORMAL, heteronym=True)[0]
    zhuyin = pinyin(ch, style=Style.BOPOMOFO, heteronym=True)[0]
    if len(plain) == len(zhuyin):
        for py, zy in zip(plain, zhuyin):
            if py.isascii() and py.isalpha():
                bopo_to_pinyin.setdefault(tone_of(zy)[0], py.replace("ü", "v"))

table = defaultdict(lambda: defaultdict(list))  # (bopomofo, pinyin) -> tone -> [(freq, char)]
for ch in chars:
    freq = word_frequency(ch, "zh")
    for index, zy in enumerate(pinyin(ch, style=Style.BOPOMOFO, heteronym=True)[0]):
        base, tone = tone_of(zy)
        py = bopo_to_pinyin.get(base)
        if py is None:
            continue
        # A heteronym's secondary readings are rare: keep 氏 (zhi) or 信 (shen) out of the top slots.
        weight = 1.0 if index == 0 else 0.02
        table[(base, py)][tone].append((freq * weight, ch))

for (base, py) in sorted(table, key=lambda k: k[1]):
    tones = []
    for tone in range(1, 6):
        ranked = sorted(table[(base, py)][tone], key=lambda fc: -fc[0])
        seen = []
        for _, ch in ranked:
            if ch not in seen:
                seen.append(ch)
        tones.append("".join(seen[:PER_TONE]))
    sys.stdout.write(f"{base}\t{py}\t{'|'.join(tones)}\n")
