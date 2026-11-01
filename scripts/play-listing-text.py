#!/usr/bin/env python3
"""Print the Play Console store-listing text from the fastlane metadata.

fastlane/metadata/android/en-US/full_description.txt is hard-wrapped at ~80
columns for readable diffs. Play Console renders every newline literally, so
pasting the file as-is produces ragged mid-sentence breaks. This joins wrapped
lines back into paragraphs while keeping blank lines, bullet lines (•) and
<b>headings</b> on their own lines, then checks Play's length limits.

Usage: python3 scripts/play-listing-text.py [title|short|full]   (default: all)
"""
from __future__ import annotations

import sys
from pathlib import Path

META = Path(__file__).resolve().parent.parent / "fastlane/metadata/android/en-US"

#: Play Console field limits (characters)
LIMITS = {"title": 30, "short": 80, "full": 4000}
FILES = {"title": "title.txt", "short": "short_description.txt", "full": "full_description.txt"}


def starts_block(line: str) -> bool:
    """True when a line must start a new output line rather than continue the previous one."""
    s = line.lstrip()
    return s == "" or s.startswith(("•", "<b>", "-", "*"))


def unwrap(text: str) -> str:
    """Join hard-wrapped continuation lines; keep paragraph, bullet and heading breaks."""
    out: list[str] = []
    for line in text.rstrip("\n").split("\n"):
        line = line.rstrip()
        prev = out[-1] if out else ""
        # A heading line stands alone, so whatever follows it starts a new line too.
        if out and not starts_block(line) and prev and not prev.lstrip().startswith("<b>"):
            out[-1] = f"{prev} {line.strip()}"
        else:
            out.append(line)
    return "\n".join(out)


def field(name: str) -> str:
    raw = (META / FILES[name]).read_text(encoding="utf-8")
    return unwrap(raw) if name == "full" else raw.strip()


def main(argv: list[str]) -> int:
    names = argv[1:] or list(FILES)
    status = 0
    for name in names:
        if name not in FILES:
            print(f"unknown field {name!r}; expected one of {', '.join(FILES)}", file=sys.stderr)
            return 2
        text = field(name)
        over = len(text) > LIMITS[name]
        status |= over
        if len(names) > 1:
            print(f"===== {name} ({len(text)}/{LIMITS[name]} chars){' OVER LIMIT' if over else ''} =====")
        print(text)
        if len(names) > 1:
            print()
        elif over:
            print(f"{name}: {len(text)} chars exceeds Play's {LIMITS[name]}", file=sys.stderr)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
