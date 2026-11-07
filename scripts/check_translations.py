#!/usr/bin/env python3
"""Lists Russian UI strings passed to tr("...") that have no English entry in EnglishStrings.kt."""
import glob, re, sys

root = "app/src/main/java"
src = open(f"{root}/com/sboard/app/core/i18n/EnglishStrings.kt", encoding="utf-8").read()
known = set(re.findall(r'^\s*"((?:[^"\\]|\\.)*)" to "', src, re.M))
used = set()
for path in glob.glob(f"{root}/**/*.kt", recursive=True):
    for m in re.finditer(r'tr\(\s*"((?:[^"\\]|\\.)*)"', open(path, encoding="utf-8").read()):
        used.add(m.group(1))
# Strings that are identical in both languages (brand names etc.) need no entry.
missing = sorted(s for s in used if s not in known and re.search(r"[а-яА-ЯёЁ]", s))
for s in missing:
    print("missing:", s)
print(f"{len(used)} strings used, {len(missing)} without translation")
sys.exit(1 if missing else 0)
