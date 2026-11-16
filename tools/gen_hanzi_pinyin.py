#!/usr/bin/env python3
"""Regenerates app/src/main/assets/hanzi/pinyin.tsv from the pinyin plugin.

Usage:
    python tools/gen_hanzi_pinyin.py <pinyin.dict>

<pinyin.dict> lives in the plugin source tree:
    pime-public-plugin/plugins/translate/pime-pinyin-translate/assets/pinyin.dict

Format of the input is ``<space separated syllables>\t<word>\t<frequency>``.
Only entries whose syllable count equals the word's character count are used;
each character accumulates the syllable it takes in that word, weighted by the
word's frequency, and the chart keeps the heaviest reading. The output is
``<char>\t<syllable>`` sorted by code point.

The dictionary is CC BY 4.0 (rime-wanxiang); see assets/hanzi/NOTICE.txt.
"""
import sys
from collections import Counter, defaultdict

OUT = "app/src/main/assets/hanzi/pinyin.tsv"


def main(path):
    totals = defaultdict(Counter)
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        syllables, word, frequency = parts
        try:
            frequency = int(frequency)
        except ValueError:
            continue
        syllables = syllables.split(" ")
        characters = list(word)
        if len(syllables) != len(characters):
            continue
        for character, syllable in zip(characters, syllables):
            totals[character][syllable] += frequency

    rows = sorted((ord(ch), ch, counts.most_common(1)[0][0])
                  for ch, counts in totals.items())
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        for _, character, syllable in rows:
            out.write(f"{character}\t{syllable}\n")
    print(f"{len(rows)} characters -> {OUT}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
