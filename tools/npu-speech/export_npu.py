"""Exports Offline Parakeet's and Offline Moonshine's encoders for a phone NPU.

The app ships two ExecuTorch builds that carry an NPU delegate beside XNNPACK
(native/executorch-jni/build.sh): libwmexecutorch_qnn (Qualcomm's QNN, for a
Snapdragon 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5) and
libwmexecutorch_neuron (MediaTek's NeuroPilot, for a Dimensity 9300 / 9400).
This script makes the programs that feed them. Nobody publishes QNN or
NeuroPilot .pte exports of these models (checked on Hugging Face 2026-11-26),
so they are made here, with the ExecuTorch release the app's runtime is built
from (v1.5.1).

An NPU wants fixed shapes, so the encoder is compiled for one clip length
(--seconds) and the phone pads a shorter clip with silence to it, the way
sherpa-onnx's QNN builds already do; a longer clip is cut into pieces. The
small, step-by-step parts stay on the CPU:

  parakeet-aux   Parakeet TDT 0.6B v3's preprocessor, decoder_step and joint
                 for XNNPACK (8da4w, as the CPU build), plus its constants.
                 One file for every chip.
  parakeet       Parakeet's encoder (with the joint's encoder projection) for
                 one chip and clip length: input mel [1, 128, T], output
                 [1, T_enc, 640]. 16-bit activations, 8-bit weights by default.
  moonshine      Moonshine Tiny's encoder for one chip and clip length: input
                 audio [1, samples], output [1, frames, 288]. fp16 by default.
                 The phone runs it with Software Mansion's XNNPACK decoder, the
                 one the CPU build already downloads.

Every run writes the .pte and a manifest (bytes, SHA-256, what was left to the
CPU, and test transcripts: the eager model on the clip as it is, the same
padded to the bucket, and with the quantization simulated), and fails when
the program calls an operator the app's selective runtime lacks
(native/executorch-jni/ops.txt) unless --allow-missing-ops is given.

Nothing here runs a delegate: the pip runtime has no QNN or NeuroPilot
kernels for the host, so the NPU's own output is checked on a phone.

QNN needs Linux x86_64 and Qualcomm's QAIRT SDK (QNN_SDK_ROOT); NeuroPilot
needs Linux x86_64, Python 3.10 and MediaTek's mtk_converter and mtk_neuron
wheels from the NeuroPilot Express SDK. See README.md and
.github/workflows/npu-speech.yml.
"""

import argparse
import hashlib
import json
import os
import re
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "tools", "parakeet-ultra"))

SAMPLE_RATE = 16_000

V3_REPO = "nvidia/parakeet-tdt-0.6b-v3"
V3_REVISION = "541d1f99c6b0c3cd0b11a95167540bb8edefd82b"
CLIPS_REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
CLIPS_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
PARAKEET_CLIPS = ("en", "de", "fr", "es")

MOONSHINE_REPO = "UsefulSensors/moonshine-tiny"
MOONSHINE_REVISION = "390624ed33d594443aa4aa221f5b9f283b545b5a"
# The CPU build the app downloads; its decoder and tokenizer run beside the NPU encoder.
SM_REPO = "software-mansion/react-native-executorch-moonshine-tiny"
SM_REVISION = "6af58e7ab467f655d034eedc7acc756c1ff9b2c2"
SM_ENCODER = "xnnpack/moonshine_tiny_xnnpack_encoder.pte"
SM_DECODER = "xnnpack/moonshine_tiny_xnnpack_decoder.pte"
SM_TOKENIZER = "moonshine_tiny_tokenizer.json"

QNN_SOCS = ("SM8550", "SM8650", "SM8750", "SM8850")
NEURON_SOCS = ("MT6989", "MT6991")

# Built into the runtime, never in ops.txt (README of native/executorch-jni).
BUILTIN_OPS = re.compile(r"^(executorch_prim::|aten::sym_size\.int$)")


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def cpu_ops(et) -> dict:
    """Per method, the operators left outside any delegate, and how many delegate calls."""
    from executorch.exir.schema import DelegateCall, KernelCall

    found = {}
    for plan in et.executorch_program.execution_plan:
        names = set()
        delegates = 0
        for chain in plan.chains:
            for ins in chain.instructions:
                if isinstance(ins.instr_args, KernelCall):
                    op = plan.operators[ins.instr_args.op_index]
                    names.add(op.name + (f".{op.overload}" if op.overload else ""))
                elif isinstance(ins.instr_args, DelegateCall):
                    delegates += 1
        found[plan.name] = {"cpu_ops": sorted(names), "delegate_calls": delegates}
    return found


def missing_ops(report: dict, ops_txt: str) -> list:
    """Operators a program calls that the app's selective build (ops.txt) does not register."""
    with open(ops_txt) as f:
        have = {line.strip() for line in f if line.strip()}
    used = {op for method in report.values() for op in method["cpu_ops"]}
    return sorted(op for op in used if op not in have and not BUILTIN_OPS.match(op))


def pad(audio: torch.Tensor, samples: int) -> torch.Tensor:
    """[audio] cut or padded with silence to [samples], as the phone does."""
    out = torch.zeros(samples, dtype=torch.float32)
    n = min(samples, audio.shape[-1])
    out[:n] = audio.reshape(-1)[:n]
    return out


def write_program(et, out_dir: str, name: str) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, name)
    with open(path, "wb") as f:
        et.write_to_file(f)
    return {"name": name, "bytes": os.path.getsize(path), "sha256": sha256(path)}


# --- lowering ----------------------------------------------------------------


def lower(module: torch.nn.Module, example: tuple, args, calibration: list):
    """[module] for args.delegate on args.soc: quantized per args.precision, then lowered, as an
    ExecuTorch program with one method, `forward`. Returns (program, quantized module or None)."""
    from executorch.exir import ExecutorchBackendConfig
    from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e

    # Exported non-strict first, as ExecuTorch's own Parakeet export does: NeMo's code does not
    # all trace under strict export, and the flat graph that comes out re-exports strictly,
    # which the QNN lowering insists on.
    with torch.no_grad():
        graph = torch.export.export(module, example, strict=False).module()
    quantized = None

    if args.delegate == "qnn":
        from executorch.backends.qualcomm.quantizer.quantizer import QnnQuantizer, QuantDtype
        from executorch.backends.qualcomm.serialization.qc_schema import QnnExecuTorchBackendType
        from executorch.backends.qualcomm.utils.utils import (
            generate_htp_compiler_spec,
            generate_qnn_executorch_compiler_spec,
            get_soc_to_chipset_map,
            to_edge_transform_and_lower_to_qnn,
        )
        from torchao.quantization.pt2e import MinMaxObserver

        chip = get_soc_to_chipset_map()[args.soc]
        fp16 = args.precision == "fp16"
        if not fp16:
            quantizer = QnnQuantizer(backend=QnnExecuTorchBackendType.kHtpBackend, soc_model=chip)
            quantizer.set_default_quant_config(
                {"16a8w": QuantDtype.use_16a8w, "16a16w": QuantDtype.use_16a16w}[args.precision],
                is_conv_per_channel=True,
                is_linear_per_channel=True,
                act_observer=MinMaxObserver,
            )
            with torch.no_grad():
                graph = prepare_pt2e(graph, quantizer)
                for inputs in calibration:
                    graph(*inputs)
                graph = convert_pt2e(graph)
            quantized = graph
        specs = generate_qnn_executorch_compiler_spec(
            soc_model=chip, backend_options=generate_htp_compiler_spec(use_fp16=fp16),
        )
        edge = to_edge_transform_and_lower_to_qnn({"forward": graph}, {"forward": example}, specs)
    else:
        from executorch.backends.mediatek import NeuropilotPartitioner, NeuropilotQuantizer, Precision
        from executorch.exir import EdgeCompileConfig, to_edge_transform_and_lower
        from executorch.exir.backend.backend_details import CompileSpec

        if args.precision != "fp16":
            quantizer = NeuropilotQuantizer()
            quantizer.setup_precision({"a16w8": Precision.A16W8, "a16w16": Precision.A16W16}[args.precision])
            with torch.no_grad():
                graph = prepare_pt2e(graph, quantizer)
                for inputs in calibration:
                    graph(*inputs)
                graph = convert_pt2e(graph, fold_quantize=False)
            quantized = graph
        # Without a quantizer the converter compiles the float graph relaxed to fp16
        # (NeuropilotBackend always passes --relax-fp32).
        with torch.no_grad():
            program = torch.export.export(graph, example, strict=True)
        edge = to_edge_transform_and_lower(
            program,
            compile_config=EdgeCompileConfig(_check_ir_validity=False),
            partitioner=[NeuropilotPartitioner([CompileSpec("platform-config", args.soc.lower().encode())])],
        )
    return edge.to_executorch(config=ExecutorchBackendConfig(extract_delegate_segments=True)), quantized


# --- Parakeet ----------------------------------------------------------------


def load_parakeet(export_script: str):
    import export_ultra
    import nemo.collections.asr as nemo_asr
    from huggingface_hub import hf_hub_download

    et_export = export_ultra.load_export_module(export_script)
    nemo_path = hf_hub_download(V3_REPO, "parakeet-tdt-0.6b-v3.nemo", revision=V3_REVISION)
    model = nemo_asr.models.ASRModel.restore_from(nemo_path, map_location="cpu")
    model.eval()
    model.cpu()
    clips = {
        c: export_ultra.load_audio(hf_hub_download(CLIPS_REPO, f"test_wavs/{c}.wav", revision=CLIPS_REVISION)).reshape(-1)
        for c in PARAKEET_CLIPS
    }
    return export_ultra, et_export, model, nemo_path, clips


def tdt_walk(f_proj: torch.Tensor, decoder_step, joint, blank: int, layers: int, hidden: int) -> list:
    """Greedy TDT over every encoder frame, as ParakeetEngine.walk does on the phone."""
    durations = (0, 1, 2, 3, 4)
    h = torch.zeros(layers, 1, hidden)
    c = torch.zeros(layers, 1, hidden)
    g, h, c = decoder_step(torch.tensor([[blank]]), h, c)
    out, t, same_frame = [], 0, 0
    frames = f_proj.shape[1]
    while t < frames:
        token, duration = joint(f_proj[:, t : t + 1, :], g)
        token, duration = int(token), durations[int(duration)] if int(duration) < 5 else 1
        if token == blank:
            t += max(duration, 1)
            same_frame = 0
            continue
        out.append(token)
        g, h, c = decoder_step(torch.tensor([[token]]), h, c)
        t += duration
        if duration == 0:
            same_frame += 1
            if same_frame >= 10:
                t, same_frame = t + 1, 0
        else:
            same_frame = 0
    return out


def parakeet_aux(args) -> dict:
    """The CPU parts every NPU encoder runs with, for XNNPACK, as the CPU build exports them."""
    export_ultra, et_export, model, nemo_path, clips = load_parakeet(args.export_script)
    programs, metadata = et_export.export_all(
        model, dtype=torch.float, backend="xnnpack", qlinear="8da4w", qlinear_group_size=32,
    )
    # The encoder runs on the NPU, from a program of its own.
    del programs["encoder"]
    et = et_export.lower_to_executorch(programs, metadata=metadata, backend="xnnpack")
    name = "parakeet-tdt-0.6b-v3-aux-xnnpack.pte"
    entry = write_program(et, args.output_dir, name)
    export_ultra.extract_tokenizer(nemo_path, args.output_dir)

    # The aux program through the pip runtime, with the eager encoder between its methods.
    from executorch.runtime import Runtime

    program = Runtime.get().load_program(et.buffer)
    pre, step, joint = (program.load_method(m) for m in ("preprocessor", "decoder_step", "joint"))
    transcripts = {}
    with torch.no_grad():
        for c, audio in clips.items():
            mel, mel_len = pre.execute([audio, torch.tensor([audio.shape[0]], dtype=torch.int64)])
            f_proj, _ = et_export.EncoderWithProjection(model.encoder, model.joint)(mel, mel_len)
            tokens = tdt_walk(
                f_proj,
                lambda t, h, c_: tuple(step.execute([t, h, c_])),
                lambda f, g: tuple(joint.execute([f.contiguous(), g])),
                metadata["blank_id"], metadata["num_rnn_layers"], metadata["pred_hidden"],
            )
            transcripts[c] = model.tokenizer.ids_to_text(tokens)
    report = cpu_ops(et)
    return {
        "files": [entry],
        "metadata": metadata,
        "transcripts": {"aux_runtime": transcripts},
        "methods": report,
        "missing_ops": missing_ops(report, args.ops_txt),
    }


def parakeet_encoder(args) -> dict:
    _, et_export, model, _, clips = load_parakeet(args.export_script)
    samples = args.seconds * SAMPLE_RATE
    pre = et_export.PreprocessorWrapper(model.preprocessor).eval()
    length = torch.tensor([samples], dtype=torch.int64)

    def mel_of(audio: torch.Tensor) -> torch.Tensor:
        with torch.no_grad():
            return pre(pad(audio, samples), length)[0]

    frames = mel_of(torch.zeros(samples)).shape[2]

    class StaticEncoder(torch.nn.Module):
        """The encoder for exactly [frames] mel frames, its length fixed, so the NPU sees no
        dynamic shape and no padding mask: the clip is padded with real silence instead."""

        def __init__(self):
            super().__init__()
            self.inner = et_export.EncoderWithProjection(model.encoder, model.joint)
            self.register_buffer("length", torch.tensor([frames], dtype=torch.int64), persistent=False)

        def forward(self, mel: torch.Tensor) -> torch.Tensor:
            return self.inner(audio_signal=mel, length=self.length)[0]

    encoder = StaticEncoder().eval()
    mels = {c: mel_of(a) for c, a in clips.items()}
    # Calibration: every clip, and the clips run together, so the ranges see long speech too.
    joined = mel_of(torch.cat(list(clips.values())))
    calibration = [(m,) for m in mels.values()] + [(joined,)]
    et, quantized = lower(encoder, (mels["en"],), args, calibration)

    step = et_export.DecoderStep(model.decoder, model.joint).eval()
    joint = et_export.JointWithArgmax(model.joint, model.tokenizer.vocab_size + 1).eval()
    blank = model.tokenizer.vocab_size
    layers, hidden = model.decoder.pred_rnn_layers, model.decoder.pred_hidden

    def text(f_proj):
        return model.tokenizer.ids_to_text(tdt_walk(f_proj, step, joint, blank, layers, hidden))

    transcripts = {"eager": {}, "padded": {}, "quantized": {}}
    with torch.no_grad():
        for c, audio in clips.items():
            mel, mel_len = model.preprocessor(input_signal=audio.unsqueeze(0), length=torch.tensor([audio.shape[0]]))
            transcripts["eager"][c] = text(et_export.EncoderWithProjection(model.encoder, model.joint)(mel, mel_len)[0])
            transcripts["padded"][c] = text(encoder(mels[c]))
            if quantized is not None:
                transcripts["quantized"][c] = text(quantized(mels[c]))
    if quantized is None:
        del transcripts["quantized"]

    name = f"parakeet-tdt-0.6b-v3-encoder-{args.delegate}-{args.soc.lower()}-{args.seconds}s.pte"
    entry = write_program(et, args.output_dir, name)
    report = cpu_ops(et)
    return {
        "files": [entry],
        "mel_frames": frames,
        "samples": samples,
        "transcripts": transcripts,
        "methods": report,
        "missing_ops": missing_ops(report, args.ops_txt),
    }


# --- Moonshine ---------------------------------------------------------------


def moonshine_encoder(args) -> dict:
    from huggingface_hub import hf_hub_download
    from transformers import AutoProcessor, MoonshineForConditionalGeneration

    import export_ultra
    from executorch.runtime import Runtime

    hf = MoonshineForConditionalGeneration.from_pretrained(MOONSHINE_REPO, revision=MOONSHINE_REVISION)
    hf.eval()
    processor = AutoProcessor.from_pretrained(MOONSHINE_REPO, revision=MOONSHINE_REVISION)
    samples = args.seconds * SAMPLE_RATE

    class Encoder(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = hf.model.encoder

        def forward(self, audio: torch.Tensor) -> torch.Tensor:
            return self.encoder(audio).last_hidden_state

    encoder = Encoder().eval()
    # Moonshine Tiny's own clips are Parakeet's test clips' English one plus the rest, which
    # it hears as accented English; they are only for calibration and the sanity transcripts.
    clips = {
        c: export_ultra.load_audio(hf_hub_download(CLIPS_REPO, f"test_wavs/{c}.wav", revision=CLIPS_REVISION)).reshape(-1)
        for c in PARAKEET_CLIPS
    }
    padded = {c: pad(a, samples).unsqueeze(0) for c, a in clips.items()}
    calibration = [(p,) for p in padded.values()]
    et, quantized = lower(encoder, (padded["en"],), args, calibration)

    # The app's decoder (Software Mansion's XNNPACK program) over the hidden states, as on the phone.
    runtime = Runtime.get()
    sm_encoder = runtime.load_program(hf_hub_download(SM_REPO, SM_ENCODER, revision=SM_REVISION)).load_method("forward")
    sm_decoder = runtime.load_program(hf_hub_download(SM_REPO, SM_DECODER, revision=SM_REVISION)).load_method("forward_cached")

    def decode(hidden: torch.Tensor, n: int) -> str:
        budget = min(178, int(np.ceil(n / SAMPLE_RATE * 6.5)) + 2)
        tokens = [1]
        while len(tokens) < budget:
            out = sm_decoder.execute([torch.tensor([tokens], dtype=torch.long), hidden.contiguous()])[0]
            nxt = int(out.reshape(-1)[-1])
            if nxt == 2:
                break
            tokens.append(nxt)
        return processor.tokenizer.decode(tokens[1:], skip_special_tokens=True).strip()

    transcripts = {"app_cpu": {}, "padded": {}, "quantized": {}}
    agreement = {}
    with torch.no_grad():
        for c, audio in clips.items():
            n = min(audio.shape[0], samples)
            theirs = sm_encoder.execute([audio[:n].unsqueeze(0).contiguous()])[0]
            ours = encoder(audio[:n].unsqueeze(0))
            # The decoder was exported against the transformers encoder; this says it still is.
            agreement[c] = float((theirs - ours).abs().max())
            transcripts["app_cpu"][c] = decode(theirs, n)
            transcripts["padded"][c] = decode(encoder(padded[c]), n)
            if quantized is not None:
                transcripts["quantized"][c] = decode(quantized(padded[c]), n)
    if quantized is None:
        del transcripts["quantized"]
    if max(agreement.values()) > 1e-2 and not args.force:
        sys.exit(f"transformers' Moonshine encoder differs from the app's by {agreement}; --force to export anyway")

    name = f"moonshine-tiny-encoder-{args.delegate}-{args.soc.lower()}-{args.seconds}s.pte"
    entry = write_program(et, args.output_dir, name)
    report = cpu_ops(et)
    return {
        "files": [entry],
        "samples": samples,
        "encoder_agreement_max_abs": agreement,
        "transcripts": transcripts,
        "methods": report,
        "missing_ops": missing_ops(report, args.ops_txt),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", choices=("parakeet-aux", "parakeet", "moonshine"), required=True)
    parser.add_argument("--delegate", choices=("qnn", "neuron"))
    parser.add_argument("--soc", help="SM8550, SM8650, SM8750, SM8850 (qnn); MT6989, MT6991 (neuron)")
    parser.add_argument("--seconds", type=int, default=10, help="the clip length the encoder is compiled for")
    parser.add_argument(
        "--precision", default=None,
        help="qnn: 16a8w (default for Parakeet), 16a16w or fp16 (default for Moonshine); "
        "neuron: a16w8 (default for Parakeet), a16w16 or fp16 (default for Moonshine)",
    )
    parser.add_argument("--export-script", help="ExecuTorch v1.5.1's examples/models/parakeet/export_parakeet_tdt.py")
    parser.add_argument("--output-dir", default="out")
    parser.add_argument("--ops-txt", default=os.path.join(REPO, "native", "executorch-jni", "ops.txt"))
    parser.add_argument("--allow-missing-ops", action="store_true")
    parser.add_argument("--force", action="store_true", help="export even when a sanity check fails")
    args = parser.parse_args()

    if args.model != "parakeet-aux":
        if args.delegate is None or args.soc is None:
            parser.error("--delegate and --soc are needed for an encoder")
        args.soc = args.soc.upper()
        if args.soc not in (QNN_SOCS if args.delegate == "qnn" else NEURON_SOCS):
            parser.error(f"{args.soc} is not a {args.delegate} chip the app serves")
        if args.precision is None:
            fp16 = args.model == "moonshine"
            args.precision = "fp16" if fp16 else ("16a8w" if args.delegate == "qnn" else "a16w8")
        allowed = ("16a8w", "16a16w", "fp16") if args.delegate == "qnn" else ("a16w8", "a16w16", "fp16")
        if args.precision not in allowed:
            parser.error(f"--precision for {args.delegate} is one of {allowed}")
    if args.model.startswith("parakeet") and not args.export_script:
        parser.error("--export-script is needed for Parakeet")

    import executorch

    version = getattr(executorch, "__version__", "")
    if not version.startswith("1.5.1"):
        print(f"warning: ExecuTorch {version}, not the app runtime's 1.5.1", file=sys.stderr)

    run = {"parakeet-aux": parakeet_aux, "parakeet": parakeet_encoder, "moonshine": moonshine_encoder}[args.model]
    manifest = run(args)
    manifest.update({
        "model": args.model,
        "delegate": args.delegate,
        "soc": args.soc,
        "seconds": None if args.model == "parakeet-aux" else args.seconds,
        "precision": args.precision,
        "executorch": version,
        "qnn_sdk": os.environ.get("QNN_SDK_ROOT"),
    })
    tag = args.model if args.model == "parakeet-aux" else f"{args.model}-{args.delegate}-{args.soc.lower()}-{args.seconds}s"
    with open(os.path.join(args.output_dir, f"manifest-{tag}.json"), "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(json.dumps(manifest, indent=2, ensure_ascii=False))
    if manifest["missing_ops"] and not args.allow_missing_ops:
        sys.exit(
            "The program calls operators the app's runtime does not register: "
            f"{manifest['missing_ops']}. Add them to native/executorch-jni/ops.txt and rebuild the "
            "libraries, or keep the program; --allow-missing-ops writes the manifest without failing."
        )


if __name__ == "__main__":
    main()
