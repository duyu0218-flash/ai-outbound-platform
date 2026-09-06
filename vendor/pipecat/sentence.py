"""Deterministic sentence boundaries for outbound speech, with no model or I/O.

This is a conservative, rule-based English/CJK segmenter, not a linguistic
replacement for Punkt. Ambiguous abbreviations are held until a later boundary
or the aggregator's end-of-stream flush. Offsets refer to the original text.
"""

import re


CLOSERS = frozenset('\"\'”’»）)]}」』】')
ENDINGS = frozenset(".!?;…！？。；．｡।॥؟؛۔؏၊။។៕໌།༎։՜՞።፧፨")
_ABBREVIATIONS = frozenset(
    "mr mrs ms dr prof sr jr st vs etc e.g i.e no fig inc ltd dept approx a.m p.m".split()
)
_TOKEN = re.compile(r"[A-Za-z]+(?:\.[A-Za-z]+)*$")


def match_endofsentence(text: str) -> int:
    """Return the first completed sentence's end offset, or zero for a fragment.

    Streaming callers must supply lexical lookahead: a terminal dot cannot by
    itself distinguish a decimal from a sentence. SimpleTextAggregator handles
    that delay and flushes an unfinished final fragment at end of stream.
    """
    size = len(text.rstrip())
    for index, char in enumerate(text[:size]):
        if char not in ENDINGS:
            continue
        if char in ".．":
            before = text[index - 1] if index else ""
            after = text[index + 1] if index + 1 < size else ""
            if before.isdigit() and after.isdigit():
                continue
            # Domains, emails, version numbers and the interior of U.S./e.g.
            if before.isascii() and before.isalnum() and after.isascii() and after.isalnum():
                continue
            # Bound look-behind so many abbreviations cannot cause quadratic work.
            token = _TOKEN.search(text[max(0, index - 80):index])
            if token:
                word = token.group()
                if word.lower() in _ABBREVIATIONS:
                    continue
                if len(word) == 1 and word.isupper():
                    continue
                if "." in word and all(len(part) == 1 for part in word.split(".")):
                    continue
            # Do not read a numbered-list prefix as a one-character sentence.
            if index < 8 and text[:index].strip().isdigit():
                continue
        end = index + 1
        while end < size and text[end] in ENDINGS | CLOSERS:
            end += 1
        return end
    # Preserve the public helper's complete-input contract. Streaming callers
    # never use this terminal case until lexical lookahead has arrived.
    end = size
    while end and text[end - 1] in CLOSERS:
        end -= 1
    return size if end and text[end - 1] in ENDINGS else 0
