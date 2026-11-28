#!/usr/bin/env python3
"""Decode one sentence with an exported model, as a check that it loads and writes the language.

The sentence is spoken by Meta's MMS-TTS voice for the language
(facebook/mms-tts-<iso3>, used here only, never shipped), so no dataset is
needed. --kind nemotron runs sherpa-onnx's streaming recognizer over
encoder/decoder/joiner; --kind omnilingual runs its offline recognizer over
model.int8.onnx. Prints the transcript and adds it to manifest.json; a model
that writes nothing fails the run.
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx
import torch
from transformers import AutoTokenizer, VitsModel


def speak(iso3: str, text: str) -> np.ndarray:
    name = f"facebook/mms-tts-{iso3}"
    model = VitsModel.from_pretrained(name)
    tokenizer = AutoTokenizer.from_pretrained(name)
    with torch.no_grad():
        wave = model(**tokenizer(text, return_tensors="pt")).waveform[0].numpy()
    rate = model.config.sampling_rate
    if rate != 16000:
        idx = np.arange(0, len(wave), rate / 16000)
        wave = np.interp(idx, np.arange(len(wave)), wave)
    return wave.astype(np.float32)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dir", required=True, type=Path)
    p.add_argument("--kind", required=True, choices=["nemotron", "omnilingual"])
    p.add_argument("--iso3", required=True)
    p.add_argument("--text", required=True)
    args = p.parse_args()
    d = args.dir
    try:
        wave = speak(args.iso3, args.text)
    except Exception as e:  # no MMS voice for the language
        print(f"no MMS-TTS voice for {args.iso3}: {e}; skipped")
        return
    wave = np.concatenate([np.zeros(8000, np.float32), wave, np.zeros(16000, np.float32)])
    if args.kind == "nemotron":
        rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(d / "tokens.txt"),
            encoder=str(d / "encoder.int8.onnx"),
            decoder=str(d / "decoder.int8.onnx"),
            joiner=str(d / "joiner.int8.onnx"),
            num_threads=2,
        )
        s = rec.create_stream()
        s.accept_waveform(16000, wave)
        s.input_finished()
        while rec.is_ready(s):
            rec.decode_stream(s)
        text = rec.get_result(s)
    else:
        rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
            model=str(d / "model.int8.onnx"), tokens=str(d / "tokens.txt"), num_threads=2,
        )
        s = rec.create_stream()
        s.accept_waveform(16000, wave)
        rec.decode_stream(s)
        text = s.result.text
    print("said:", args.text)
    print("heard:", text)
    manifest = d / "manifest.json"
    data = json.loads(manifest.read_text())
    data["smoke"] = {"said": args.text, "heard": text, "voice": f"facebook/mms-tts-{args.iso3}"}
    manifest.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    if not text.strip():
        sys.exit("the model wrote nothing")


if __name__ == "__main__":
    main()
