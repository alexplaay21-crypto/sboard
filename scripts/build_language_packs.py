#!/usr/bin/env python3
"""Builds the bundled interface language packs (app/src/main/assets/i18n/<lang>.json).

Source strings are the English translations in EnglishStrings.kt (keyed by the Russian source).
Each pack maps Russian source -> translated text. Strings whose {n} placeholders do not survive
translation are left out (the app falls back to English for them). Re-run after adding strings:

    python3 scripts/build_language_packs.py [lang ...]
"""
import json, os, re, sys, time, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/sboard/app/core/i18n/EnglishStrings.kt")
OUT = os.path.join(ROOT, "app/src/main/assets/i18n")
LANGS = ["de", "fr", "es", "it", "pt", "pl", "uk", "be", "kk", "uz", "tg", "az", "hy", "ka",
         "tr", "ar", "fa", "he", "hi", "bn", "ur", "ta", "th", "vi", "id", "zh-CN", "ja", "ko",
         "bg", "cs", "sk", "sl", "hr", "sr", "ro", "hu", "el", "lt", "lv", "et", "fi", "sv", "no", "da",
         "nl", "is", "ca", "eo"]

def kotlin_unescape(s):
    return s.replace('\\"', '"').replace('\\$', '$').replace('\\\\', '\\')

def load_strings():
    text = open(SRC, encoding="utf-8").read()
    pairs = re.findall(r'^\s*"((?:[^"\\]|\\.)*)" to "((?:[^"\\]|\\.)*)",?\s*$', text, re.M)
    return [(kotlin_unescape(a), kotlin_unescape(b)) for a, b in pairs]

def google(text, lang):
    data = urllib.parse.urlencode({"client": "gtx", "sl": "en", "tl": lang, "dt": "t", "q": text}).encode()
    req = urllib.request.Request("https://translate.googleapis.com/translate_a/single", data=data,
                                 headers={"User-Agent": "Mozilla/5.0"})
    r = json.loads(urllib.request.urlopen(req, timeout=30).read())
    return "".join(seg[0] for seg in r[0] if seg and seg[0])

MARK = re.compile(r"\[\[\s*(\d+)\s*\]\]")

def parse(batch, translated):
    out = {}
    ms = list(MARK.finditer(translated))
    for i, m in enumerate(ms):
        idx = int(m.group(1))
        if idx >= len(batch): continue
        end = ms[i + 1].start() if i + 1 < len(ms) else len(translated)
        t = translated[m.end():end].strip()
        src_ru, src_en = batch[idx]
        ph = set(re.findall(r"\{\d\}", src_en))
        if t and all(p in t for p in ph):
            out[src_ru] = t
    return out

def build(lang, strings):
    path = os.path.join(OUT, lang + ".json")
    done = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    todo = [(ru, en) for ru, en in strings if ru not in done]
    batches, cur, size = [], [], 0
    for item in todo:
        if size + len(item[1]) > 1500 and cur:
            batches.append(cur); cur, size = [], 0
        cur.append(item); size += len(item[1]) + 8
    if cur: batches.append(cur)
    for b in batches:
        joined = "\n".join("[[%d]] %s" % (i, en.replace("\n", " ")) for i, (_, en) in enumerate(b))
        for attempt in range(6):
            try:
                done.update(parse(b, google(joined, lang)))
                break
            except Exception as e:
                time.sleep(5 * (attempt + 1))
        time.sleep(1.2)
        json.dump(done, open(path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(lang, len(done), "/", len(strings), flush=True)

if __name__ == "__main__":
    strings = load_strings()
    os.makedirs(OUT, exist_ok=True)
    for lang in (sys.argv[1:] or LANGS):
        build(lang, strings)
