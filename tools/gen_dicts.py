#!/usr/bin/env python3
"""Genera app/src/main/assets/dict/{en,es}.tsv a partir de wordfreq 3.1.1 (large_en / large_es).

Uso:
    pip install wordfreq==3.1.1 msgpack spylls
    # diccionarios Hunspell de LibreOffice (solo se usan aquí, para validar; no van en la app):
    #   https://github.com/LibreOffice/dictionaries  ->  en/en_US, en/en_GB, es/es_ES  ({dic,aff})
    python3 tools/gen_dicts.py /ruta/a/hunspell

Formato de salida (ordenado por punto de código, igual que String.compareTo en Kotlin):
    #cand <b>      palabras con banda <= b se pueden proponer como corrección (~120k más frecuentes)
    #d2 <b>        palabras con banda <= b entran en el índice de errores dobles (~30k más frecuentes)
    palabra<TAB>banda          (banda = frecuencia de wordfreq en centibelios, 0 = la más frecuente)
    palabra<TAB>banda<TAB>x    "x" = la palabra existe (no se corrige) pero NO se propone nunca como corrección

Una palabra solo se propone como corrección si Hunspell la reconoce, o si es muy frecuente (banda <= 500) y no
es una palabra pegada (dos palabras válidas sin espacio: "almenos", "alfinal"). Así las corrige a algo real y
no a basura de la web ni a nombres propios raros.

TODAS las palabras cuentan como "existentes": el teclado no corrige nunca una palabra real aunque sea rara.

wordfreq mide texto real (web, redes) y por eso incluye erratas frecuentes como si fueran palabras.
Se quita una entrada solo si Hunspell la rechaza en inglés Y en castellano, y además:
  * es la misma palabra sin tilde/ñ de otra >= 2x más frecuente (tambien -> también), o
  * tiene dos letras intercambiadas respecto a otra >= 100x más frecuente (teh -> the), o
  * es rara (fuera de las 15k primeras) y está a un error (ignorando tildes) de una palabra común (top 20k)
    >= 300x más frecuente, o (castellano) suena igual que ella: c/s/z, b/v, h muda, ll/y, g/j (nesecito -> necesito).
Sin consultar Hunspell se quitan: formas inglesas sin apóstrofo si la forma con apóstrofo es >= 5x más
frecuente (dont -> don't, cant -> can't) y las erratas de tools/typos.txt.
Lo eliminado en un idioma se elimina también del otro (las listas se contaminan: "tambien" sale en inglés).
"""
import gzip, importlib.util, os, re, sys, unicodedata
import msgpack
from spylls.hunspell import Dictionary

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "dict")
DATA = os.path.join(os.path.dirname(importlib.util.find_spec("wordfreq").origin), "data")
HUN = sys.argv[1] if len(sys.argv) > 1 else "hunspell"
CAND_RANK, D2_RANK, RARE_RANK, COMMON_RANK = 120_000, 30_000, 15_000, 20_000
UNCOND_BAND = 500

def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c))

def load(lang, pat):
    d = msgpack.unpack(gzip.open(os.path.join(DATA, f"large_{lang}.msgpack.gz")), raw=False)
    rx = re.compile(pat)
    out = {}
    for band, bucket in enumerate(d[1:]):
        for w in bucket:
            if rx.match(w) and w not in out and len(w) <= 24:
                out[w] = band
    return out  # dict en orden de frecuencia

def phon_es(w):
    """Clave fonética del castellano: igual a la de SpellEngine.phoneticEs en Kotlin."""
    w = fold(w.replace("ñ", "\x00")).replace("\x00", "ñ")
    out = []
    i, n = 0, len(w)
    while i < n:
        c = w[i]; nx = w[i + 1] if i + 1 < n else ""
        if c == "c" and nx == "h": out.append("C"); i += 2; continue
        if c == "q" and nx == "u": out.append("k"); i += 2; continue
        if c == "g" and nx == "u" and i + 2 < n and w[i + 2] in "ei": out.append("g"); i += 2; continue
        if c == "l" and nx == "l": out.append("y"); i += 2; continue
        if c == "c": out.append("s" if nx in ("e", "i") else "k")
        elif c == "g": out.append("j" if nx in ("e", "i") else "g")
        elif c == "z": out.append("s")
        elif c == "v" or c == "w": out.append("b")
        elif c == "h": pass
        elif c == "x" and i == 0: out.append("s")
        else: out.append(c)
        i += 1
    r = []
    for ch in out:
        if not r or r[-1] != ch: r.append(ch)
    return "".join(r)

def dl1(a, b):
    """True si a y b están a exactamente un error (borrado, inserción, sustitución o transposición)."""
    if a == b or abs(len(a) - len(b)) > 1: return False
    if len(a) == len(b):
        diff = [i for i in range(len(a)) if a[i] != b[i]]
        return len(diff) == 1 or (len(diff) == 2 and diff[1] == diff[0] + 1 and a[diff[0]] == b[diff[1]] and a[diff[1]] == b[diff[0]])
    if len(a) > len(b): a, b = b, a
    for i in range(len(b)):
        if b[:i] + b[i + 1:] == a: return True
    return False

def main():
    hun = {l: Dictionary.from_files(os.path.join(HUN, n)) for l, n in (("en", "en_US"), ("es", "es_ES"))}
    hun_gb = Dictionary.from_files(os.path.join(HUN, "en_GB"))  # grafías británicas: colour, mould, centre...
    cache = {}
    def valid(w):
        if w not in cache:
            cache[w] = any(h.lookup(w) for h in hun.values()) or bool(hun_gb.lookup(w))
        return cache[w]

    typos = {}
    for line in open(os.path.join(HERE, "typos.txt"), encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            l, t = line.split(":", 1)
            typos.setdefault(l, set()).add(t)

    loaded, removed, ranks = {}, {}, {}
    for lang, pat in (("en", r"^[a-z]+(?:'[a-z]+)?$"), ("es", r"^[a-zñáéíóúü]+$")):
        words = load(lang, pat)
        loaded[lang] = words
        order = list(words)
        rank = {w: i for i, w in enumerate(order)}
        ranks[lang] = rank
        f = lambda w: 10 ** (-words[w] / 100)
        by_fold = {}
        for w in words:
            k = fold(w)
            if k != w and (k not in by_fold or words[w] < words[by_fold[k]]):
                by_fold[k] = w
        # índice de borrados de las palabras comunes, para encontrar vecinas a un error
        common_del = {}
        for w in order[:COMMON_RANK]:
            for i in range(len(w)):
                common_del.setdefault(w[:i] + w[i + 1:], []).append(w)
            common_del.setdefault(w, []).append(w)
        phon_common = {}
        if lang == "es":
            for w in order[:COMMON_RANK]:
                phon_common.setdefault(phon_es(w), w)
        for w in order[:COMMON_RANK]:
            fw = fold(w)
            if fw != w:
                for i in range(len(fw)):
                    common_del.setdefault(fw[:i] + fw[i + 1:], []).append(w)
                common_del.setdefault(fw, []).append(w)
        remove = {}
        for w in order:
            r = rank[w]
            if r < 300: continue
            reason = None
            if lang == "es" and w in by_fold and f(by_fold[w]) >= 2 * f(w):
                reason = "tilde:" + by_fold[w]
            if not reason:
                for i in range(len(w) - 1):
                    if w[i] != w[i + 1]:
                        t = w[:i] + w[i + 1] + w[i] + w[i + 2:]
                        if t in words and f(t) >= 100 * f(w):
                            reason = "transp:" + t; break
            if not reason and lang == "es" and r > RARE_RANK:
                t = phon_common.get(phon_es(w))
                if t and t != w and f(t) >= 300 * f(w):
                    reason = "fonetica:" + t
            if not reason and r > RARE_RANK:
                fw = fold(w)
                keys = {w, fw} | {w[:i] + w[i + 1:] for i in range(len(w))} | {fw[:i] + fw[i + 1:] for i in range(len(fw))}
                for k in keys:
                    for t in common_del.get(k, ()):
                        if t != w and f(t) >= 300 * f(w) and (dl1(w, t) or dl1(fold(w), fold(t))):
                            reason = "cerca:" + t; break
                    if reason: break
            if reason and not valid(w):
                remove[w] = reason
        if lang == "en":
            for w in words:
                if "'" in w:
                    bare = w.replace("'", "")
                    if bare in words and f(w) >= 5 * f(bare):
                        remove[bare] = "apostrofo:" + w
        for t in typos.get(lang, ()):
            if t in words: remove[t] = "lista"
        removed[lang] = remove
        print(lang, "eliminadas propias", len(remove), file=sys.stderr)

    for lang, words in loaded.items():
        remove = dict(removed[lang])
        for other, rem in removed.items():
            if other != lang:
                for w, why in rem.items():
                    forced = why.startswith("apostrofo") or why == "lista"
                    rare_here = ranks[lang].get(w, 0) > RARE_RANK
                    if w in words and w not in remove and (forced or (rare_here and not valid(w))): remove[w] = "otro:" + why
        kept = [w for w in words if w not in remove]
        hun_own = hun[lang]
        own_cache = {}
        def valid_own(w):
            if w not in own_cache:
                own_cache[w] = bool(hun_own.lookup(w)) or (lang == "en" and bool(hun_gb.lookup(w)))
            return own_cache[w]
        def glued(w):
            for i in range(2, len(w) - 1):
                if valid_own(w[:i]) and valid_own(w[i:]) and len(w[i:]) >= 2:
                    return True
            return False
        noncand = set()
        cand_limit = CAND_RANK
        for w in kept[:cand_limit]:
            if valid_own(w):
                continue
            if words[w] <= UNCOND_BAND and not glued(w):
                continue
            noncand.add(w)
        cand_band = words[kept[min(CAND_RANK, len(kept)) - 1]]
        d2_band = words[kept[min(D2_RANK, len(kept)) - 1]]
        with open(os.path.join(OUT, f"{lang}.tsv"), "w", encoding="utf-8") as fh:
            fh.write(f"#cand {cand_band}\n#d2 {d2_band}\n")
            for w in sorted(kept):
                fh.write(f"{w}\t{words[w]}" + ("\tx\n" if w in noncand else "\n"))
        import random; random.seed(5)
        print(lang, "palabras", len(kept), "eliminadas", len(remove), "cand<=", cand_band, "d2<=", d2_band, "no-candidatas", len(noncand))
        print("  no candidatas (muestra):", random.sample(sorted(noncand), 40) if False else sorted(noncand, key=lambda w: words[w])[:60])
        print("  muestra:", random.sample(sorted(remove.items()), 50))

main()
