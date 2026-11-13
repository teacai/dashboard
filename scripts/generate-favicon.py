#!/usr/bin/env python3
"""Regenerate the multi-resolution RGBA favicon using only Python's standard library.

Run from any directory: python3 scripts/generate-favicon.py
The lowercase w is a transparent cutout, not a white foreground glyph.
"""

import struct
import zlib
from pathlib import Path

TURQUOISE = (0x40, 0xE0, 0xD0)
SIZES = (16, 32, 48, 64, 128, 256)
SAMPLES = 4
# A geometric lowercase w, expressed in fractions of the square's dimensions.
CUTOUT = (
    (0.16, 0.30), (0.285, 0.30), (0.365, 0.64), (0.445, 0.36),
    (0.555, 0.36), (0.635, 0.64), (0.715, 0.30), (0.84, 0.30),
    (0.715, 0.75), (0.585, 0.75), (0.50, 0.49), (0.415, 0.75),
    (0.285, 0.75),
)


def inside_cutout(x, y):
    inside = False
    previous = CUTOUT[-1]
    for current in CUTOUT:
        x1, y1 = previous
        x2, y2 = current
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
        previous = current
    return inside


def chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", zlib.crc32(kind + payload)))


def png(size):
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)  # PNG scanline filter: none.
        for x in range(size):
            transparent = sum(
                inside_cutout((x + (sx + 0.5) / SAMPLES) / size,
                              (y + (sy + 0.5) / SAMPLES) / size)
                for sy in range(SAMPLES) for sx in range(SAMPLES)
            )
            alpha = round(255 * (1 - transparent / (SAMPLES * SAMPLES)))
            pixels.extend((*TURQUOISE, alpha))
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA.
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(pixels, 9)) + chunk(b"IEND", b""))


def main():
    images = [png(size) for size in SIZES]
    offset = 6 + 16 * len(images)
    directory = bytearray(struct.pack("<HHH", 0, 1, len(images)))
    for size, image in zip(SIZES, images):
        dimension = 0 if size == 256 else size
        directory.extend(struct.pack("<BBBBHHII", dimension, dimension, 0, 0,
                                     1, 32, len(image), offset))
        offset += len(image)
    target = Path(__file__).resolve().parents[1] / "src/main/resources/static/favicon.ico"
    target.write_bytes(bytes(directory) + b"".join(images))
    print(f"Generated {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
