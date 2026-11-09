#!/usr/bin/env bash
# The whole job on a rented CUDA box: corpora, examples, arrays, training,
# export, a sanity probe. Run from the unpacked bundle's `wmpunct/` directory
# after setup.sh. Everything is tunable through the environment:
#
#   LANGS      (all rows of remote/languages.tsv)
#   VOCAB      32768   words + pieces + 4 specials
#   PIECES     8192
#   CTX        48
#   STEPS      40000
#   BATCH      512
#   E/D/LAYERS/HEADS/FF   64/192/4/4/512
#   EXCLAIM    1.0     weight of the exclamation class in the loss
#   OUT        out
#
# Bring back OUT/smart_punct.wmpt (a few MB) and OUT/best.pt.
set -euo pipefail
[ -f .env ] && . ./.env
PY=${PY:-python3}
VOCAB=${VOCAB:-32768}; PIECES=${PIECES:-8192}; CTX=${CTX:-48}
STEPS=${STEPS:-40000}; BATCH=${BATCH:-512}
E=${E:-64}; D=${D:-192}; LAYERS=${LAYERS:-4}; HEADS=${HEADS:-4}; FF=${FF:-512}
EXCLAIM=${EXCLAIM:-1.0}
OUT=${OUT:-out}
DATA=${DATA:-data/punct}
mkdir -p "$OUT"
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True

if [ -s data/examples.tsv ]; then
    echo "== examples: data/examples.tsv (kept)"
else
    echo "== corpora"
    bash remote/fetch.sh 2>&1 | tee "$OUT/fetch.log"
fi
if [ -s "$DATA/train.npz" ]; then
    echo "== arrays: $DATA (kept)"
else
    echo "== prep"
    $PY prep.py data/examples.tsv "$DATA" vocab="$VOCAB" pieces="$PIECES" ctx="$CTX" 2>&1 | tee "$OUT/prep.log"
fi
echo "== train e$E d$D L$LAYERS ff$FF, $STEPS steps, batch $BATCH"
$PY train.py "$DATA" "$OUT" steps="$STEPS" batch="$BATCH" e="$E" d="$D" layers="$LAYERS" heads="$HEADS" ff="$FF" exclaim_weight="$EXCLAIM" eval_every=2000 2>&1 | tee "$OUT/train.log"
echo "== export"
$PY export.py "$OUT/best.pt" "$DATA" "$OUT/smart_punct.wmpt"
$PY check.py "$OUT/best.pt" "$DATA" "how are you" "i am fine" "what a day" "if you want" "wie geht es dir" "je ne sais pas"
ls -la "$OUT/smart_punct.wmpt" "$OUT/best.pt"
echo "done: copy $OUT/smart_punct.wmpt and $OUT/best.pt back"
