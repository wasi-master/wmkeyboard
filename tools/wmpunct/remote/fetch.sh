#!/usr/bin/env bash
# Fetches the corpora remote/languages.tsv names into data/raw/ and writes
# $EXAMPLES (data/examples.tsv). Tatoeba per-language exports are small and taken whole;
# OpenSubtitles (OPUS 2018 mono) is streamed and cut at the line cap, so a
# 3.6 GB English file costs only its first few hundred MB.
#
#   LANGS="en de"  only these keyboard ids (default: every row)
set -euo pipefail
[ -f .env ] && . ./.env
PY=${PY:-python3}
RAW=${RAW:-data/raw}
EXAMPLES=${EXAMPLES:-data/examples.tsv}
LANGS=${LANGS:-}
mkdir -p "$RAW"
: > "$EXAMPLES"
while IFS=$'\t' read -r id iso3 opus cap; do
    [[ -z "$id" || "$id" == \#* ]] && continue
    if [ -n "$LANGS" ] && ! [[ " $LANGS " == *" $id "* ]]; then continue; fi
    specs=()
    if [ "$iso3" != "-" ]; then
        f=$RAW/${iso3}_sentences.tsv.bz2
        [ -s "$f" ] || curl -sfL -o "$f" "https://downloads.tatoeba.org/exports/per_language/$iso3/${iso3}_sentences.tsv.bz2" || { echo "no tatoeba for $id" >&2; rm -f "$f"; }
        [ -s "$f" ] && specs+=("tatoeba:$f")
    fi
    if [ "$opus" != "-" ] && [ "$cap" != "0" ]; then
        f=$RAW/opus_${opus}.txt
        if ! [ -s "$f" ]; then
            # curl exits 23 when head closes the pipe: expected.
            (curl -sL "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/$opus.txt.gz" | gzip -dc 2>/dev/null | head -n "$cap" > "$f") || true
            [ -s "$f" ] || { echo "no subtitles for $id ($opus)" >&2; rm -f "$f"; }
        fi
        [ -s "$f" ] && specs+=("opus:$f")
    fi
    [ ${#specs[@]} -eq 0 ] && continue
    $PY corpus.py "$id" "${specs[@]}" >> "$EXAMPLES"
done < remote/languages.tsv
wc -l "$EXAMPLES"
