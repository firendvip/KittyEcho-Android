#!/usr/bin/env python3
"""Generate a deterministic, non-speech PCM fixture for contract checks."""

from __future__ import annotations

import hashlib
import math
import struct
import wave
from pathlib import Path


SAMPLE_RATE = 16_000
FRAME_COUNT = 1_600
AMPLITUDE = 800
FREQUENCY_HZ = 440


def main() -> int:
    target = Path(__file__).resolve().parents[1] / "fixtures" / "synthetic-tone.wav"
    target.parent.mkdir(parents=True, exist_ok=True)
    frames = bytearray()
    for index in range(FRAME_COUNT):
        sample = int(
            AMPLITUDE * math.sin(2 * math.pi * FREQUENCY_HZ * index / SAMPLE_RATE)
        )
        frames.extend(struct.pack("<h", sample))
    with wave.open(str(target), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(bytes(frames))
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f"{target} {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
