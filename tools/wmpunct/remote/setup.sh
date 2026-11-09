#!/usr/bin/env bash
# One-time setup on a rented CUDA box. Run from the unpacked bundle's
# `wmpunct/` directory (with `../wmlm/common.py` beside it). An image that
# already ships a CUDA torch keeps it.
set -euo pipefail
PY=${PY:-python3}
if $PY - <<'EOF2' 2>/dev/null
import torch, sys
sys.exit(0 if torch.cuda.is_available() else 1)
EOF2
then
    echo "using the image's torch"
    $PY -m pip install --quiet numpy
    echo "PY=$PY" > .env
else
    $PY -m venv .venv
    .venv/bin/pip install --upgrade pip >/dev/null
    .venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cu128
    .venv/bin/pip install numpy
    echo "PY=.venv/bin/python" > .env
fi
. ./.env
$PY - <<'EOF2'
import torch
print("torch", torch.__version__, "cuda", torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else "")
EOF2
