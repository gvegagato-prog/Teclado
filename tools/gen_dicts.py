#!/usr/bin/env python3
"""Genera app/src/main/assets/dict/{en,es}.tsv a partir de wordfreq 3.1.1 (large_en / large_es).

Uso:  pip install wordfreq==3.1.1 msgpack && python3 tools/gen_dicts.py

wordfreq mide frecuencia en texto real (web, redes), así que incluye erratas muy comunes
(dont, teh, tambien...). Si las dejamos, el corrector las daría por buenas. Aquí se eliminan
las palabras que son, con mucha probabilidad, una versión errónea de otra mucho más frecuente:
  * ES: misma palabra sin tilde/ñ (tambien -> también)          [frecuencia del correcto >= 5,5x]
  * EN: misma palabra sin apóstrofo (dont -> don't)             [>= 5x]
  * EN/ES: erratas conocidas de tools/typos.txt
  * EN/ES: dos letras adyacentes intercambiadas (teh -> the)    [>= 100x y frecuencia propia baja]
"""
import gzip, os, re, sys, unicodedata
import importlib.util
import msgpack

N = 50000
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dict")
data_dir = os.path.join(os.path.dirname(importlib.util.find_spec("wordfreq").origin), "data")

def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c))

def load(lang, pat):
    d = msgpack.unpack(gzip.open(os.path.join(data_dir, f"large_{lang}.msgpack.gz")), raw=False)
    rx = re.compile(pat)
    out = {}
    for i, bucket in enumerate(d[1:]):
        score = max(1, int(1e9 * 10 ** (-i / 100)))
        for w in bucket:
            if rx.match(w) and w not in out and len(w) <= 24:
                out[w] = score
    return out

def main():
    for lang, pat in (("en", r"^[a-z]+(?:'[a-z]+)?$"), ("es", r"^[a-zñáéíóúü]+$")):
        words = load(lang, pat)
        words = dict(list(words.items())[:N])
        by_fold = {}
        for w, f in words.items():
            k = fold(w)
            if k != w and (k not in by_fold or f > words[by_fold[k]]):
                by_fold[k] = w
        remove = {}
        for w, f in words.items():
            if lang == "es" and w in by_fold and words[by_fold[w]] >= 5.5 * f:
                remove[w] = "tilde:" + by_fold[w]; continue
            # transposición
            if f < 20000:
                for i in range(len(w) - 1):
                    if w[i] != w[i + 1]:
                        t = w[:i] + w[i + 1] + w[i] + w[i + 2:]
                        if words.get(t, 0) >= 100 * f:
                            remove[w] = "transp:" + t; break
        if lang == "en":
            for w, f in words.items():
                if "'" in w:
                    bare = w.replace("'", "")
                    if bare in words and f >= 5 * words[bare]:
                        remove[bare] = "apostrofo:" + w
        for line in open(os.path.join(os.path.dirname(__file__), "typos.txt"), encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and line.startswith(lang + ":"):
                t = line.split(":", 1)[1]
                if t in words: remove[t] = "lista"
        kept = [(w, f) for w, f in words.items() if w not in remove]
        with open(os.path.join(OUT, f"{lang}.tsv"), "w", encoding="utf-8") as fh:
            for w, f in kept:
                fh.write(f"{w}\t{f}\n")
        print(lang, "total", len(words), "eliminadas", len(remove), "ejemplos", list(remove.items())[:25])

main()
