# Avisos de terceros

## Diccionarios (`app/src/main/assets/dict/*.tsv`)

Derivados de las listas de frecuencias de [wordfreq](https://github.com/rspeer/wordfreq) 3.1.1
(Robyn Speer), generados con `tools/gen_dicts.py`.

Según el README de wordfreq, el paquete se redistribuye bajo Apache-2.0 y sus ficheros de datos
bajo [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
Por tanto, `en.tsv` y `es.tsv` se distribuyen bajo **CC BY-SA 4.0**.

Los datos de wordfreq incluyen, entre otras fuentes citadas por su autora: Google Books Ngrams,
Leeds Internet Corpus, Wikipedia, ParaCrawl, OPUS OpenSubtitles 2018 (OpenSubtitles) y listas
SUBTLEX (Marc Brysbaert et al.; se debe acreditar a los autores de SUBTLEX), además de datos de Twitter y
Reddit agregados. Detalle completo en el README de wordfreq.

## Validación con Hunspell (solo al generar)

`tools/gen_dicts.py` consulta los diccionarios Hunspell en_US y es_ES del proyecto
[LibreOffice/dictionaries](https://github.com/LibreOffice/dictionaries), mediante la biblioteca `spylls`, para no eliminar
palabras válidas al limpiar erratas. Esos diccionarios no se incluyen en la app ni en este repositorio.
