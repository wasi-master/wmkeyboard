#!/usr/bin/env python3
"""Export a WAXAL fine-tune of Omnilingual ASR CTC 300M for Offline Omnilingual.

waxal-benchmarking/omniasr-ctc-300m-waxal-<iso3> are Meta's Omnilingual ASR
300M CTC model fine-tuned on one African language each of Google's WAXAL
corpus, kept as fairseq2 checkpoints. The architecture and tokenizer are the
base model's, so the export is sherpa-onnx's own
(scripts/omnilingual-asr/export-onnx.py): the same wrapper over the raw
waveform, opset 14, dynamic batch and length, then dynamic int8 on MatMul.
The result runs on the app's Omnilingual bridge (omnilingual_jni.cpp) like
the base model, with its tokens.txt.

Writes into --out: model.int8.onnx, tokens.txt, LICENSE.md, README.md and
manifest.json (each file's size and SHA-256, the source revision, the
licence, the WER the source card gives).
"""

import argparse
import hashlib
import inspect
import json
import re
import sys
from pathlib import Path

import onnx
import torch
from fairseq2.data.tokenizers.hub import load_tokenizer
from fairseq2.models.wav2vec2.asr.hub import get_wav2vec2_asr_model_hub
from fairseq2.nn.batch_layout import BatchLayout
from huggingface_hub import HfApi, snapshot_download
from onnxruntime.quantization import QuantType, quantize_dynamic

# Weights the app may point people at: open licences only. Some WAXAL MMS
# fine-tunes are CC-BY-NC; none of those come through here.
OPEN = {"cc-by-4.0", "cc-by-sa-4.0", "apache-2.0", "mit"}


class Wrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        layout = BatchLayout(shape=x.shape, seq_lens=[x.shape[1]])
        logits, _ = self.model(x, layout)
        return logits


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


@torch.no_grad()
def main():
    p = argparse.ArgumentParser()
    p.add_argument("--lang", required=True, help="the WAXAL code, tir for Tigrinya")
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args()

    repo = f"waxal-benchmarking/omniasr-ctc-300m-waxal-{args.lang}"
    info = HfApi().model_info(repo)
    card = info.card_data.to_dict() if info.card_data else {}
    licence = card.get("license")
    if licence not in OPEN:
        sys.exit(f"{repo}: licence {licence!r} is not an open one")
    ckpt = Path(snapshot_download(repo, revision=info.sha))
    readme = (ckpt / "README.md").read_text(encoding="utf-8")
    wer = re.search(r"\|\s*WER\s*\|\s*([0-9.]+)%", readme)

    hub = get_wav2vec2_asr_model_hub()
    model = hub.load_custom_model(ckpt / "model", hub.get_arch_config("300m"), device=torch.device("cpu"), dtype=torch.float32)
    model.eval()
    tokenizer = load_tokenizer("omniASR_tokenizer_v1")

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    vocab = tokenizer._model.vocabulary_size
    with open(out / "tokens.txt", "w", encoding="utf-8") as f:
        for i in range(vocab):
            f.write(f"{tokenizer._model.index_to_token(i)} {i}\n")

    fp32 = out / "model.onnx"
    kwargs = {"dynamo": False} if "dynamo" in inspect.signature(torch.onnx.export).parameters else {}
    torch.onnx.export(
        Wrapper(model).eval(),
        torch.rand(1, 16000 * 10),
        str(fp32),
        opset_version=14,
        input_names=["x"],
        output_names=["logits"],
        dynamic_axes={"x": {0: "N", 1: "num_samples"}, "logits": {0: "N", 1: "num_frames"}},
        **kwargs,
    )
    graph = onnx.load(str(fp32))
    del graph.metadata_props[:]
    for key, value in {
        "vocab_size": vocab,
        "model_type": "omnilingual-asr",
        "version": "1",
        "sample_rate": 16000,
        "model_author": "waxal-benchmarking",
        "url": f"https://huggingface.co/{repo}",
        "comment": f"300M-CTC fine-tuned on WAXAL {args.lang}",
    }.items():
        entry = graph.metadata_props.add()
        entry.key, entry.value = key, str(value)
    onnx.save(graph, str(fp32))
    quantize_dynamic(
        model_input=str(fp32),
        model_output=str(out / "model.int8.onnx"),
        op_types_to_quantize=["MatMul"],
        weight_type=QuantType.QUInt8,
    )
    fp32.unlink()

    (out / "LICENSE.md").write_text(
        f"These files are converted from {repo} at {info.sha} under {licence.upper()}.\n"
        "Source: Meta's Omnilingual ASR CTC 300M (Apache-2.0), fine-tuned by waxal-benchmarking\n"
        "on Google's WAXAL corpus.\n"
    )
    (out / "README.md").write_text(
        f"---\nlicense: {licence}\nbase_model: {repo}\nlibrary_name: sherpa-onnx\n"
        "pipeline_tag: automatic-speech-recognition\n---\n\n"
        f"# {repo.split('/')[-1]} for sherpa-onnx\n\n"
        f"[{repo}](https://huggingface.co/{repo}) at `{info.sha}`, exported the way sherpa-onnx exports "
        "Omnilingual ASR (raw waveform in, CTC logits out) and quantised to int8 for WM Keyboard's "
        "Offline Omnilingual. All training by waxal-benchmarking.\n"
    )
    manifest = {
        "source": repo,
        "revision": info.sha,
        "licence": licence,
        "wer": float(wer.group(1)) if wer else None,
        "files": {f.name: {"bytes": f.stat().st_size, "sha256": sha256(f)} for f in sorted(out.iterdir())},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
