"""Exports Moondream's Parakeet Ultra to ExecuTorch for Offline Parakeet.

Offline Parakeet runs nvidia/parakeet-tdt-0.6b-v3 as ExecuTorch's own
example exports it (examples/models/parakeet/export_parakeet_tdt.py): XNNPACK,
8-bit dynamic activations and 4-bit weights in groups of 32. Parakeet Ultra
is a post-trained v3 with the same architecture and tokenizer, but Moondream
publish it only as Hugging Face transformers weights (ParakeetForTDT), and the
export loads a NeMo model. So this script:

1. restores v3 from its pinned .nemo;
2. proves the NeMo-to-transformers key mapping on v3 itself: every NeMo
   weight, renamed, must equal the tensor of that name in NVIDIA's own
   transformers conversion of v3, and every tensor there must be claimed;
3. loads Ultra's tensors into the NeMo model under the same mapping (Ultra's
   extra voice-activity head has no NeMo counterpart and is left out);
4. transcribes the test clips with v3 and Ultra, eager, as a sanity check;
5. exports with ExecuTorch's functions and flags, then transcribes the clips
   again through the ExecuTorch runtime.

With --backend vulkan the same program is lowered to ExecuTorch's Vulkan
delegate instead (libwmexecutorch_vulkan on the phone), same quantization,
into vulkan/int4/; the pip runtime cannot run it, so the manifest lists what
it left to the CPU in place of transcripts. --source v3 skips steps 2 and 3
and exports NVIDIA's v3 itself.

Run with ExecuTorch's export script on disk (the copy at the tag the app's
runtime is built from) and its install requirements; see
.github/workflows/parakeet-ultra.yml.
"""

import argparse
import gc
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sys
import tarfile
import types

import numpy as np
import soundfile
import torch
from huggingface_hub import hf_hub_download
from safetensors.torch import load_file

V3_REPO = "nvidia/parakeet-tdt-0.6b-v3"
V3_REVISION = "541d1f99c6b0c3cd0b11a95167540bb8edefd82b"
ULTRA_REPO = "moondream/parakeet-ultra"
ULTRA_REVISION = "73175eb7aeb0d82f1e2a6b53b3aabc10a90bcd0b"
CLIPS_REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
CLIPS_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
CLIPS = ("en", "de", "fr", "es")

# transformers' NEMO_TO_HF_WEIGHT_MAPPING followed by NEMO_TDT_WEIGHT_MAPPING,
# in order, from src/transformers/models/parakeet/convert_nemo_to_hf.py at
# f36c750069f0aef45179207a680b592f138450ec. Step 2 checks it on v3.
MAPPING = (
    (r"encoder\.pre_encode\.conv\.", r"encoder.subsampling.layers."),
    (r"encoder\.pre_encode\.out\.", r"encoder.subsampling.linear."),
    (r"encoder\.pos_enc\.", r"encoder.encode_positions."),
    (r"encoder\.layers\.(\d+)\.conv\.batch_norm\.", r"encoder.layers.\1.conv.norm."),
    (r"decoder\.decoder_layers\.0\.(weight|bias)", r"ctc_head.\1"),
    (r"linear_([kv])", r"\1_proj"),
    (r"linear_out", r"o_proj"),
    (r"linear_q", r"q_proj"),
    (r"pos_bias_([uv])", r"bias_\1"),
    (r"linear_pos", r"relative_k_proj"),
    (r"decoder\.prediction\.embed\.", r"decoder.embedding."),
    (r"decoder\.prediction\.dec_rnn\.lstm\.", r"decoder.lstm."),
    (r"joint\.enc\.", r"encoder_projector."),
    (r"joint\.pred\.", r"decoder.decoder_projector."),
    (r"joint\.joint_net\.2\.", r"joint.head."),
)

# The converter drops these; the preprocessor's are the same in both models.
SKIPPED = ("featurizer.window", "featurizer.fb")

# Ultra's voice-activity head, which NeMo's TDT model has no place for.
ULTRA_EXTRA = "vad_head."


def hf_name(key: str) -> str:
    for pattern, replacement in MAPPING:
        key = re.sub(pattern, replacement, key)
    return key


def names(model) -> dict[str, str]:
    """NeMo state-dict key to transformers key, for every key the converter keeps."""
    return {k: hf_name(k) for k in model.state_dict() if not k.endswith(SKIPPED)}


def check_mapping(model, mapping: dict[str, str]) -> None:
    """Step 2: NeMo's v3 and NVIDIA's transformers v3 agree, name for name."""
    hf = load_file(hf_hub_download(V3_REPO, "model.safetensors", revision=V3_REVISION))
    sd = model.state_dict()
    problems = []
    if len(set(mapping.values())) != len(mapping):
        problems.append("two NeMo keys map to one name")
    for nemo_key, hf_key in mapping.items():
        theirs = hf.get(hf_key)
        if theirs is None:
            problems.append(f"{nemo_key} -> {hf_key}: no such tensor")
        elif theirs.shape != sd[nemo_key].shape:
            problems.append(f"{nemo_key} -> {hf_key}: {tuple(sd[nemo_key].shape)} vs {tuple(theirs.shape)}")
        else:
            diff = (sd[nemo_key].double() - theirs.double()).abs().max().item() if theirs.numel() else 0.0
            if diff > 1e-4:
                problems.append(f"{nemo_key} -> {hf_key}: differs by {diff}")
    unclaimed = sorted(set(hf) - set(mapping.values()))
    problems += [f"{k}: claimed by no NeMo key" for k in unclaimed]
    if problems:
        sys.exit("Mapping check failed on v3:\n  " + "\n  ".join(problems))
    print(f"Mapping checked on v3: {len(mapping)} tensors equal, none left over.")


def load_ultra(model, mapping: dict[str, str]) -> None:
    """Step 3: Ultra's weights into the NeMo model, cast to its dtypes."""
    ultra = load_file(hf_hub_download(ULTRA_REPO, "model.safetensors", revision=ULTRA_REVISION))
    sd = model.state_dict()
    problems = []
    weights = {}
    for nemo_key, hf_key in mapping.items():
        tensor = ultra.get(hf_key)
        if tensor is None:
            problems.append(f"{hf_key}: missing from Ultra")
        elif tensor.shape != sd[nemo_key].shape:
            problems.append(f"{hf_key}: {tuple(tensor.shape)}, v3 has {tuple(sd[nemo_key].shape)}")
        else:
            weights[nemo_key] = tensor.to(sd[nemo_key].dtype)
    extra = sorted(k for k in set(ultra) - set(mapping.values()) if not k.startswith(ULTRA_EXTRA))
    problems += [f"{k}: Ultra tensor with no NeMo place" for k in extra]
    if problems:
        sys.exit("Ultra does not fit v3:\n  " + "\n  ".join(problems))
    # Counted first: the state dict's tensors are the model's, which the load overwrites.
    changed = sum(not torch.equal(sd[k], weights[k]) for k in weights)
    result = model.load_state_dict(weights, strict=False)
    left = [k for k in result.missing_keys if not k.endswith(SKIPPED)]
    if left or result.unexpected_keys:
        sys.exit(f"Load left {left} missing and {result.unexpected_keys} unexpected")
    print(f"Loaded Ultra: {len(weights)} tensors, {changed} differ from v3.")


def extract_tokenizer(nemo_path: str, out_dir: str) -> None:
    with tarfile.open(nemo_path, "r") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("tokenizer.model"))
        with tar.extractfile(member) as src, open(os.path.join(out_dir, "tokenizer.model"), "wb") as dst:
            shutil.copyfileobj(src, dst)


def load_audio(path: str, sample_rate: int = 16000) -> torch.Tensor:
    """The export script's loader without torchaudio: mono, resampled to [sample_rate]."""
    data, rate = soundfile.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if rate != sample_rate:
        from math import gcd

        from scipy.signal import resample_poly

        g = gcd(rate, sample_rate)
        mono = resample_poly(mono, sample_rate // g, rate // g).astype(np.float32)
    return torch.from_numpy(np.ascontiguousarray(mono)).unsqueeze(0)


def load_export_module(path: str):
    """ExecuTorch's export script, importable whether or not the wheel has its examples.

    It imports torchaudio only for the loader [load_audio] replaces, and no
    torchaudio is built for ExecuTorch's torch pin, so a stand-in is put in
    for the import and taken out after: left in, it looks installed to
    transformers' probes (find_spec fails on a module with no spec).
    """
    stub = None
    try:
        import torchaudio  # noqa: F401
    except ImportError:
        stub = sys.modules["torchaudio"] = types.ModuleType("torchaudio")
    try:
        import executorch.examples.models.parakeet.quantize  # noqa: F401
    except ImportError:
        from executorch.extension.llm.export.quantize import quantize_model_

        for name in ("executorch.examples", "executorch.examples.models", "executorch.examples.models.parakeet"):
            sys.modules.setdefault(name, types.ModuleType(name))
        shim = types.ModuleType("executorch.examples.models.parakeet.quantize")
        shim.quantize_model_ = quantize_model_
        sys.modules[shim.__name__] = shim
    spec = importlib.util.spec_from_file_location("export_parakeet_tdt", path)
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    finally:
        if stub is not None and sys.modules.get("torchaudio") is stub:
            del sys.modules["torchaudio"]
    module.load_audio = load_audio
    return module


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def cpu_ops(et) -> dict[str, list[str]]:
    """Per method, the operators left outside any delegate (they run on the CPU kernels)."""
    from executorch.exir.schema import DelegateCall, KernelCall

    found = {}
    for plan in et.executorch_program.execution_plan:
        names = set()
        for chain in plan.chains:
            for ins in chain.instructions:
                if isinstance(ins.instr_args, KernelCall):
                    op = plan.operators[ins.instr_args.op_index]
                    names.add(op.name + (f".{op.overload}" if op.overload else ""))
        delegates = sum(isinstance(i.instr_args, DelegateCall) for c in plan.chains for i in c.instructions)
        found[plan.name] = sorted(names) + [f"({delegates} delegate calls)"]
    return found


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--export-script", required=True, help="ExecuTorch's export_parakeet_tdt.py")
    parser.add_argument("--output-dir", default="parakeet-ultra-executorch")
    parser.add_argument(
        "--source", choices=("ultra", "v3"), default="ultra",
        help="ultra: Moondream's weights in v3 (the default); v3: NVIDIA's v3 as it is",
    )
    parser.add_argument(
        "--backend", choices=("xnnpack", "vulkan"), default="xnnpack",
        help="vulkan: the phone GPU, for libwmexecutorch_vulkan (same 8da4w quantization)",
    )
    parser.add_argument("--vulkan-fp16", action="store_true", help="Vulkan computes in fp16")
    args = parser.parse_args()
    folder = f"{args.backend}/int4" + ("-fp16" if args.backend == "vulkan" and args.vulkan_fp16 else "")

    import nemo.collections.asr as nemo_asr

    et_export = load_export_module(args.export_script)

    out_dir = os.path.join(args.output_dir, folder)
    os.makedirs(out_dir, exist_ok=True)

    nemo_path = hf_hub_download(V3_REPO, "parakeet-tdt-0.6b-v3.nemo", revision=V3_REVISION)
    extract_tokenizer(nemo_path, out_dir)
    model = nemo_asr.models.ASRModel.restore_from(nemo_path, map_location="cpu")
    model.eval()
    model.cpu()

    mapping = names(model)
    if args.source == "ultra":
        check_mapping(model, mapping)
        gc.collect()

    clips = {c: hf_hub_download(CLIPS_REPO, f"test_wavs/{c}.wav", revision=CLIPS_REVISION) for c in CLIPS}
    report = {"v3": {}, "ultra": {}, "executorch": {}}
    for c, path in clips.items():
        report["v3"][c] = et_export.transcribe_eager(path, model)

    if args.source == "ultra":
        load_ultra(model, mapping)
        gc.collect()
        for c, path in clips.items():
            report["ultra"][c] = et_export.transcribe_eager(path, model)
    else:
        del report["ultra"]

    programs, metadata = et_export.export_all(
        model,
        dtype=torch.float,
        backend=args.backend,
        qlinear_encoder="8da4w",
        qlinear_encoder_group_size=32,
        qlinear="8da4w",
        qlinear_group_size=32,
    )
    et = et_export.lower_to_executorch(
        programs, metadata=metadata, backend=args.backend, vulkan_force_fp16=args.vulkan_fp16,
    )
    pte = os.path.join(out_dir, "model.pte")
    with open(pte, "wb") as f:
        et.write_to_file(f)
    del programs
    gc.collect()

    # The pip runtime has no Vulkan backend: a Vulkan program is checked on
    # a phone instead, and here only for what it left to the CPU.
    if args.backend == "xnnpack":
        for c, path in clips.items():
            report["executorch"][c] = et_export.transcribe_executorch(path, model, et.buffer)
    else:
        del report["executorch"]
        report["cpu_ops"] = cpu_ops(et)

    files = {}
    for name in ("tokenizer.model", "model.pte"):
        path = os.path.join(out_dir, name)
        files[f"{folder}/{name}"] = {"bytes": os.path.getsize(path), "sha256": sha256(path)}
    source = {"repo": ULTRA_REPO, "revision": ULTRA_REVISION} if args.source == "ultra" else {"repo": V3_REPO, "revision": V3_REVISION}
    manifest = {
        "backend": args.backend,
        "source": source,
        "tokenizer_and_config_from": {"repo": V3_REPO, "revision": V3_REVISION},
        "metadata": metadata,
        "files": files,
        "transcripts": report,
    }
    with open(os.path.join(args.output_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(json.dumps(manifest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
