"""Check ELF LOAD alignment and ZIP alignment for native libraries in an APK/AAR."""
import argparse
import json
import struct
import zipfile
from pathlib import Path


def inspect(path: Path) -> list[dict]:
    rows = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as stream:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            data = archive.read(info)
            if data[:4] != b"\x7fELF":
                raise ValueError(f"Not ELF: {info.filename}")
            elf64 = data[4] == 2
            order = "<" if data[5] == 1 else ">"
            offset = struct.unpack_from(order + ("Q" if elf64 else "I"), data, 32 if elf64 else 28)[0]
            stride, count = struct.unpack_from(order + "HH", data, 54 if elf64 else 42)
            alignments = []
            for index in range(count):
                base = offset + index * stride
                if struct.unpack_from(order + "I", data, base)[0] == 1:
                    alignments.append(struct.unpack_from(order + ("Q" if elf64 else "I"), data, base + (48 if elf64 else 28))[0])
            stream.seek(info.header_offset + 26)
            name_length, extra_length = struct.unpack("<HH", stream.read(4))
            zip_offset = info.header_offset + 30 + name_length + extra_length
            rows.append({
                "library": info.filename,
                "elf_bits": 64 if elf64 else 32,
                "load_alignment_bytes": alignments,
                "elf_16kb_compatible": bool(alignments) and all(value >= 16384 for value in alignments),
                "stored_uncompressed": info.compress_type == zipfile.ZIP_STORED,
                "zip_data_offset": zip_offset,
                "zip_16kb_aligned": zip_offset % 16384 == 0,
            })
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--require-apk-zip-alignment", action="store_true")
    args = parser.parse_args()
    records = inspect(args.archive)
    print(json.dumps({"archive": str(args.archive), "native_libraries": records}, indent=2))
    bad = any(not item["elf_16kb_compatible"] for item in records if item["elf_bits"] == 64)
    if args.require_apk_zip_alignment:
        bad |= any(item["stored_uncompressed"] and not item["zip_16kb_aligned"] for item in records)
    raise SystemExit(1 if bad else 0)
