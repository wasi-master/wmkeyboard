#!/usr/bin/env bash
# fetch.sh over the language table in N parallel groups (one language's
# download and filter at a time per group), then the runs queued in RUNS.
# Run from the bundle's wmpunct/ directory after setup.sh; nohup it.
#
#   NGROUPS  6       parallel fetch groups
#   RUNS     "out|;out_big|E=96,D=256,LAYERS=6,FF=768;out_ex2|EXCLAIM=2"
#            name|VAR=value,VAR=value specs separated by ";", one run.sh
#            each, serial, all over the same prep'd arrays
set -uo pipefail
[ -f .env ] && . ./.env
NGROUPS=${NGROUPS:-6}
RUNS=${RUNS:-"out|;out_big|E=96,D=256,LAYERS=6,FF=768;out_ex2|EXCLAIM=2"}
mkdir -p out data
if ! [ -s data/examples.tsv ]; then
    mapfile -t ids < <(grep -v '^#' remote/languages.tsv | cut -f1 | grep -v '^$')
    declare -a grp
    for i in "${!ids[@]}"; do g=$((i % NGROUPS)); grp[$g]="${grp[$g]:-} ${ids[$i]}"; done
    for g in $(seq 0 $((NGROUPS - 1))); do
        LANGS="${grp[$g]}" EXAMPLES="data/examples_$g.tsv" bash remote/fetch.sh > "out/fetch_$g.log" 2>&1 &
    done
    wait
    cat data/examples_*.tsv > data/examples.tsv
    wc -l data/examples.tsv
    cut -f1 data/examples.tsv | sort | uniq -c | sort -rn > out/examples_by_lang.txt
fi
IFS=';' read -ra specs <<< "$RUNS"
for spec in "${specs[@]}"; do
    name=${spec%%|*}; vars=${spec#*|}
    echo "== run $name ($vars)"
    env OUT="$name" ${vars//,/ } bash remote/run.sh > "out/run_$name.log" 2>&1
    tail -3 "out/run_$name.log"
done
touch out/DONE
