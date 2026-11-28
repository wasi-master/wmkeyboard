#!/usr/bin/env python3
"""Export one of Addis AI's Nemotron 3.5 fine-tunes to sherpa-onnx for Offline Sherpa.

Addis AI publishes its Tigrinya, Afaan Oromo and Swahili streaming models as
NeMo checkpoints only (its Amharic one also comes as a sherpa-onnx export,
which the app pins as it is). This runs sherpa-onnx's own Nemotron 3.5
exporter (scripts/nemo/nemotron-3.5-asr-streaming-0.6b/export_onnx.py, passed
in --sherpa-script) on the checkpoint instead of NVIDIA's base model, with one
change: the prompt the model was trained under (am-ET, index 49, for
Tigrinya) becomes the encoder's `auto_prompt_id`, so sherpa-onnx uses it for
every stream and the app has nothing to tell the model.

Writes into --out: encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx,
tokens.txt, LICENSE.md, README.md and manifest.json (each file's size and
SHA-256, the source revision, the prompt).
"""

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import sys
from pathlib import Path

from huggingface_hub import HfApi, hf_hub_download


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--repo", required=True, help="addisai/addis-scribe-tigrinya-streaming")
    p.add_argument("--prompt", required=True, type=int, help="the prompt index the model was trained under")
    p.add_argument("--chunk-ms", type=int, default=320, help="320 or 1120, the chunks Addis AI trained")
    p.add_argument("--sherpa-script", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args()

    info = HfApi().model_info(args.repo, files_metadata=True)
    card = info.card_data.to_dict() if info.card_data else {}
    if card.get("license_name") != "openmdw-1.1":
        sys.exit(f"{args.repo}: licence {card.get('license_name')!r}, expected openmdw-1.1")
    nemo = next(s for s in info.siblings if s.rfilename.endswith(".nemo"))
    path = Path(hf_hub_download(args.repo, nemo.rfilename, revision=info.sha))
    if nemo.lfs and sha256(path) != nemo.lfs.sha256:
        sys.exit(f"{nemo.rfilename}: SHA-256 does not match the listing")

    spec = importlib.util.spec_from_file_location("sherpa_export", args.sherpa_script)
    exporter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(exporter)

    from omegaconf import open_dict

    model = exporter.nemo_asr.models.ASRModel.restore_from(str(path), map_location="cpu")
    if not 0 <= args.prompt < int(model.num_prompts):
        sys.exit(f"prompt {args.prompt} is not one of the model's {model.num_prompts}")
    # Every language string sherpa-onnx is handed then lands on the one
    # prompt this model knows; "auto" is the default it uses when told none.
    with open_dict(model.cfg):
        model.cfg.model_defaults.prompt_dictionary = {"auto": args.prompt}
    exporter.nemo_asr.models.ASRModel.from_pretrained = staticmethod(lambda *a, **k: model)

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    work = Path("work-" + args.repo.replace("/", "-")).resolve()
    work.mkdir(exist_ok=True)
    os.chdir(work)
    sys.argv = [str(args.sherpa_script), "--chunk-size-ms", str(args.chunk_ms)]
    exporter.main()

    chunk = Path(str(args.chunk_ms))
    for name in ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"]:
        shutil.move(str(chunk / name), out / name)
    shutil.move("tokens.txt", out / "tokens.txt")
    os.chdir("..")
    shutil.rmtree(work)

    (out / "LICENSE.md").write_text(
        f"These files are converted from {args.repo} at {info.sha}, Addis AI's model,\n"
        "under the OpenMDW License Agreement, version 1.1: https://openmdw.ai/license/1-1/\n"
        "It is a fine-tune of nvidia/nemotron-3.5-asr-streaming-0.6b, also under OpenMDW-1.1.\n"
    )
    (out / "README.md").write_text(
        "---\nlicense: other\nlicense_name: openmdw-1.1\nlicense_link: https://openmdw.ai/license/1-1/\n"
        f"base_model: {args.repo}\nlibrary_name: sherpa-onnx\npipeline_tag: automatic-speech-recognition\n---\n\n"
        f"# {args.repo.split('/')[-1]} for sherpa-onnx\n\n"
        f"[{args.repo}](https://huggingface.co/{args.repo}) at `{info.sha}`, exported with sherpa-onnx's "
        f"Nemotron 3.5 exporter at the {args.chunk_ms} ms chunk, int8, for WM Keyboard's Offline Sherpa. "
        f"The model's own prompt (index {args.prompt}) is the encoder's `auto_prompt_id`, so no language "
        "needs to be set on a stream. All training by Addis AI.\n"
    )
    manifest = {
        "source": args.repo,
        "revision": info.sha,
        "prompt": args.prompt,
        "chunk_ms": args.chunk_ms,
        "files": {f.name: {"bytes": f.stat().st_size, "sha256": sha256(f)} for f in sorted(out.iterdir())},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
