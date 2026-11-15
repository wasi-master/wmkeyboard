"""Exports Parakeet Ultra's voice-activity head to ExecuTorch, on its own.

Ultra's weights carry a small head on the encoder's subsampler (#1173):
`vad_head.proj` (1x1 conv, 1024 to 128), `vad_head.ctx` (a 5-wide conv over
neighbouring frames) and `vad_head.out` (1x1 conv to one logit), one per
80 ms encoder frame. Moondream's Photon cuts long recordings at the pauses it
finds. Our first export (export_ultra.py) left it out, since NeMo's TDT model
has no place for it.

This writes it as a program of its own, `ultra_vad.pte`, with one method,
`vad`: the mel features the Parakeet program's own `preprocessor` method
makes ([1, 128, frames], frames a multiple of 8) in, one logit per encoder
frame out. It holds a copy
of the subsampler (about 4.5 M parameters), so the big program is not
changed, and the app fetches this one only for long dictation.

What Moondream did not publish, and this script had to settle:

- The activations between the convolutions. ReLU, GELU and SiLU give logits
  within 0.04 of one another on speech and on noise (checked on the test
  clips), so the choice does not move a decision; ReLU is used.
- The context convolution's padding: two frames each side. A causal one
  (four on the left) reads the silence after speech as speech at up to
  0.78 on the test clips, where the centred one stays under 0.02.

How it is meant to be used, and how not: the features are normalised over
the whole clip (NeMo's `per_feature`), and the head reads silence as silence
only next to speech. A few seconds of room noise on their own come out as
speech at 0.7, so it is no live phrase-end detector; it finds the pauses in
a recording that has speech in it, which is what Photon uses it for.

Run with ExecuTorch v1.5.1 and torch 2.14.0 (the app runtime's pins):

    python tools/parakeet-ultra/export_vad.py --output-dir out

`--weights` takes a safetensors file holding at least the `encoder.subsampling.*`
and `vad_head.*` tensors instead of downloading all of Ultra.
"""

import argparse
import hashlib
import json
import math
import os

import numpy as np
import torch
import torch.nn.functional as F
from torch import nn

ULTRA_REPO = "moondream/parakeet-ultra"
ULTRA_REVISION = "73175eb7aeb0d82f1e2a6b53b3aabc10a90bcd0b"
CLIPS_REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
CLIPS_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
CLIPS = ("en", "de", "fr", "es")

# 50 s at 100 frames a second, the most the Parakeet preprocessor makes.
MAX_FRAMES = 5008


class UltraVad(nn.Module):
    """NeMo's dw_striding subsampler (factor 8), then Ultra's head."""

    def __init__(self, w: dict[str, torch.Tensor]):
        super().__init__()
        self.conv0 = nn.Conv2d(1, 256, 3, stride=2, padding=1)
        self.dw1 = nn.Conv2d(256, 256, 3, stride=2, padding=1, groups=256)
        self.pw1 = nn.Conv2d(256, 256, 1)
        self.dw2 = nn.Conv2d(256, 256, 3, stride=2, padding=1, groups=256)
        self.pw2 = nn.Conv2d(256, 256, 1)
        self.linear = nn.Linear(4096, 1024)
        self.proj = nn.Conv1d(1024, 128, 1)
        self.ctx = nn.Conv1d(128, 128, 5, padding=2)
        self.out = nn.Conv1d(128, 1, 1)
        names = {
            "conv0": "encoder.subsampling.layers.0",
            "dw1": "encoder.subsampling.layers.2",
            "pw1": "encoder.subsampling.layers.3",
            "dw2": "encoder.subsampling.layers.5",
            "pw2": "encoder.subsampling.layers.6",
            "linear": "encoder.subsampling.linear",
            "proj": "vad_head.proj",
            "ctx": "vad_head.ctx",
            "out": "vad_head.out",
        }
        with torch.no_grad():
            for attr, key in names.items():
                layer = getattr(self, attr)
                layer.weight.copy_(w[f"{key}.weight"].float())
                layer.bias.copy_(w[f"{key}.bias"].float())

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        x = mel.transpose(1, 2).unsqueeze(1)  # [1, 1, frames, 128]
        x = F.relu(self.conv0(x))
        x = F.relu(self.pw1(self.dw1(x)))
        x = F.relu(self.pw2(self.dw2(x)))
        b, c, t, f = x.shape
        x = self.linear(x.transpose(1, 2).reshape(b, t, c * f))  # [1, t, 1024]
        x = F.relu(self.proj(x.transpose(1, 2)))
        x = F.relu(self.ctx(x))
        return self.out(x)[:, 0, :]  # [1, t]


# ---- the check's own features ---------------------------------------------------
# NeMo's preprocessor for v3, written out so the check needs no NeMo: pre-emphasis
# 0.97, a 25 ms Hann window every 10 ms, 512-point power spectrum, 128 Slaney mel
# bands, log with 2^-24 added, each band normalised over the clip. The app feeds
# the head the Parakeet program's own preprocessor output instead.

SR, NFFT, WIN, HOP, NMEL = 16000, 512, 400, 160, 128


def _hz_to_mel(f):
    f = np.asanyarray(f, dtype=float)
    lin, minlog, step = 1000.0, 1000.0 / (200.0 / 3), math.log(6.4) / 27.0
    return np.where(f >= lin, minlog + np.log(np.maximum(f, 1e-10) / lin) / step, f / (200.0 / 3))


def _mel_to_hz(m):
    m = np.asanyarray(m, dtype=float)
    lin, minlog, step = 1000.0, 1000.0 / (200.0 / 3), math.log(6.4) / 27.0
    return np.where(m >= minlog, lin * np.exp(step * (m - minlog)), (200.0 / 3) * m)


def _melbank() -> torch.Tensor:
    fft = np.linspace(0, SR / 2, NFFT // 2 + 1)
    pts = _mel_to_hz(np.linspace(_hz_to_mel(0), _hz_to_mel(SR / 2), NMEL + 2))
    fd, ramps = np.diff(pts), pts[:, None] - fft[None, :]
    w = np.zeros((NMEL, len(fft)))
    for i in range(NMEL):
        w[i] = np.maximum(0, np.minimum(-ramps[i] / fd[i], ramps[i + 2] / fd[i + 1]))
    w *= (2.0 / (pts[2:NMEL + 2] - pts[:NMEL]))[:, None]
    return torch.tensor(w, dtype=torch.float32)


def mel(audio: np.ndarray) -> torch.Tensor:
    x = torch.tensor(audio, dtype=torch.float32)
    x = torch.cat([x[:1], x[1:] - 0.97 * x[:-1]])
    spec = torch.stft(x, NFFT, HOP, WIN, torch.hann_window(WIN, periodic=False), center=True,
                      pad_mode="constant", return_complex=True)
    m = torch.log(_melbank() @ spec.abs() ** 2 + 2 ** -24)
    m = (m - m.mean(1, keepdim=True)) / (m.std(1, keepdim=True) + 1e-5)
    return m.unsqueeze(0)  # [1, 128, frames]


def load_clip(path: str) -> np.ndarray:
    import soundfile
    from math import gcd

    from scipy.signal import resample_poly

    data, rate = soundfile.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if rate != SR:
        g = gcd(rate, SR)
        mono = resample_poly(mono, SR // g, rate // g).astype(np.float32)
    return mono


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="parakeet-ultra-vad")
    parser.add_argument("--weights", help="safetensors with the subsampler and head tensors")
    parser.add_argument("--clips", nargs="*", help="local test clips instead of the pinned ones")
    args = parser.parse_args()

    from safetensors.torch import load_file

    if args.weights:
        weights = load_file(args.weights)
    else:
        from huggingface_hub import hf_hub_download

        weights = load_file(hf_hub_download(ULTRA_REPO, "model.safetensors", revision=ULTRA_REVISION))
    model = UltraVad(weights).eval()

    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower
    from torch.export import Dim, export

    # A whole number of encoder frames: export cannot prove the strided
    # convolutions' shape guards for any length, and can for multiples of 8.
    blocks = Dim("blocks", min=4, max=MAX_FRAMES // 8)
    example = torch.randn(1, NMEL, 800)
    program = export(model, (example,), dynamic_shapes=({2: 8 * blocks},), strict=False)
    et = to_edge_transform_and_lower({"vad": program}, partitioner=[XnnpackPartitioner()]).to_executorch()
    os.makedirs(args.output_dir, exist_ok=True)
    pte = os.path.join(args.output_dir, "ultra_vad.pte")
    with open(pte, "wb") as f:
        et.write_to_file(f)

    # The lowered program against the eager one, and what it says about
    # speech and the silence around it: two seconds of faint noise each side.
    from executorch.runtime import Runtime

    method = Runtime.get().load_program(et.buffer).load_method("vad")
    if args.clips:
        clips = {os.path.basename(p): p for p in args.clips}
    else:
        from huggingface_hub import hf_hub_download

        clips = {c: hf_hub_download(CLIPS_REPO, f"test_wavs/{c}.wav", revision=CLIPS_REVISION) for c in CLIPS}
    rng = np.random.default_rng(0)
    report = {}
    for name, path in clips.items():
        speech = load_clip(path)
        quiet = (rng.standard_normal(SR * 2) * 3e-3).astype(np.float32)
        features = mel(np.concatenate([quiet, speech + rng.standard_normal(len(speech)).astype(np.float32) * 3e-3, quiet]))
        features = features[:, :, : features.shape[2] // 8 * 8].contiguous()
        with torch.no_grad():
            eager = model(features)[0]
        lowered = method.execute([features])[0][0]
        p = torch.sigmoid(lowered).numpy()
        start, end = int(2 / 0.08), int((2 + len(speech) / SR) / 0.08)
        report[name] = {
            "max_abs_diff": float((eager - lowered).abs().max()),
            "speech_mean": round(float(p[start + 2:end - 2].mean()), 3),
            "silence_mean": round(float(np.concatenate([p[:start - 2], p[end + 3:]]).mean()), 3),
            "silence_max": round(float(np.concatenate([p[:start - 2], p[end + 3:]]).max()), 3),
        }
    manifest = {
        "source": {"repo": ULTRA_REPO, "revision": ULTRA_REVISION},
        "method": "vad",
        "input": "Parakeet preprocessor mel, [1, 128, frames], frames a multiple of 8 from 32 to %d" % (MAX_FRAMES // 8 * 8),
        "output": "one logit per 8 mel frames (80 ms)",
        "files": {"ultra_vad.pte": {"bytes": os.path.getsize(pte), "sha256": sha256(pte)}},
        "check": report,
    }
    with open(os.path.join(args.output_dir, "manifest-vad.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
