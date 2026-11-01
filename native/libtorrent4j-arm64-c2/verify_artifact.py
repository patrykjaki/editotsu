#!/usr/bin/env python3
"""Verify the approved arm64 JAR and the ELF embedded in release APKs."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def c_string(data: bytes, offset: int) -> str:
    if offset < 0 or offset >= len(data):
        return ""
    end = data.find(b"\0", offset)
    if end < 0:
        end = len(data)
    return data[offset:end].decode("utf-8", errors="replace")


def align4(value: int) -> int:
    return (value + 3) & ~3


def parse_elf64_le(data: bytes) -> dict[str, object]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 2 or data[5] != 1:
        raise ValueError("expected a little-endian ELF64 artifact")

    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)
    section_offset = header[6]
    section_entry_size = header[11]
    section_count = header[12]
    if section_entry_size < 64:
        raise ValueError("invalid ELF64 section entry size")

    sections: list[dict[str, int]] = []
    for index in range(section_count):
        offset = section_offset + index * section_entry_size
        if offset + 64 > len(data):
            raise ValueError("truncated ELF section table")
        values = struct.unpack_from("<IIQQQQIIQQ", data, offset)
        sections.append(
            {
                "type": values[1],
                "offset": values[4],
                "size": values[5],
                "link": values[6],
                "entry_size": values[9],
            }
        )

    build_id: str | None = None
    for section in sections:
        if section["type"] != 7:  # SHT_NOTE
            continue
        cursor = section["offset"]
        limit = cursor + section["size"]
        while cursor + 12 <= limit and cursor + 12 <= len(data):
            name_size, description_size, note_type = struct.unpack_from("<III", data, cursor)
            cursor += 12
            name = data[cursor : cursor + name_size].rstrip(b"\0")
            cursor += align4(name_size)
            description = data[cursor : cursor + description_size]
            cursor += align4(description_size)
            if name == b"GNU" and note_type == 3:
                build_id = description.hex()

    needed: list[str] = []
    for section in sections:
        if section["type"] != 6:  # SHT_DYNAMIC
            continue
        link = section["link"]
        if link >= len(sections):
            raise ValueError("dynamic section has an invalid string-table link")
        string_section = sections[link]
        strings = data[
            string_section["offset"] : string_section["offset"] + string_section["size"]
        ]
        entry_size = section["entry_size"] or 16
        for cursor in range(section["offset"], section["offset"] + section["size"], entry_size):
            if cursor + 16 > len(data):
                break
            tag, value = struct.unpack_from("<QQ", data, cursor)
            if tag == 0:
                break
            if tag == 1:  # DT_NEEDED
                needed.append(c_string(strings, value))

    undefined_symbols: list[str] = []
    for section in sections:
        if section["type"] != 11:  # SHT_DYNSYM
            continue
        link = section["link"]
        if link >= len(sections):
            raise ValueError("dynamic symbol table has an invalid string-table link")
        string_section = sections[link]
        strings = data[
            string_section["offset"] : string_section["offset"] + string_section["size"]
        ]
        entry_size = section["entry_size"] or 24
        for cursor in range(section["offset"], section["offset"] + section["size"], entry_size):
            if cursor + 24 > len(data):
                break
            name_offset = struct.unpack_from("<I", data, cursor)[0]
            section_index = struct.unpack_from("<H", data, cursor + 6)[0]
            if section_index == 0:
                name = c_string(strings, name_offset)
                if name:
                    undefined_symbols.append(name)

    if build_id is None:
        raise ValueError("ELF has no GNU Build ID")
    return {
        "sha256": sha256(data),
        "build_id": build_id,
        "dt_needed": needed,
        "undefined_symbols": undefined_symbols,
    }


def read_zip_entry(path: Path, entry: str) -> bytes | None:
    with zipfile.ZipFile(path) as archive:
        try:
            return archive.read(entry)
        except KeyError:
            return None


def check_elf(label: str, data: bytes, expected: dict[str, object], exact_hash: bool) -> dict[str, object]:
    info = parse_elf64_le(data)
    if exact_hash and info["sha256"] != expected["elf_sha256"]:
        raise ValueError(
            f"{label}: ELF SHA-256 mismatch: expected={expected['elf_sha256']} actual={info['sha256']}"
        )
    if info["build_id"] != expected["elf_build_id"]:
        raise ValueError(
            f"{label}: ELF Build ID mismatch: expected={expected['elf_build_id']} actual={info['build_id']}"
        )
    if info["dt_needed"] != expected["dt_needed"]:
        raise ValueError(
            f"{label}: DT_NEEDED mismatch: expected={expected['dt_needed']} actual={info['dt_needed']}"
        )
    forbidden = tuple(expected["forbidden_undefined_prefixes"])
    bad_symbols = [name for name in info["undefined_symbols"] if name.startswith(forbidden)]
    if bad_symbols:
        raise ValueError(f"{label}: forbidden OpenSSL runtime symbols: {bad_symbols}")
    info.pop("undefined_symbols")
    return info


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("provenance.json"))
    parser.add_argument("--jar", type=Path, help="Approved release JAR; JAR and ELF hashes are exact")
    parser.add_argument(
        "--candidate-jar",
        type=Path,
        action="append",
        default=[],
        help="Rebuilt JAR; the embedded ELF must exactly match C2, while ZIP metadata may differ",
    )
    parser.add_argument("--apk", type=Path, nargs="*", default=[])
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    expected = manifest["artifact"]
    results: dict[str, object] = {"candidate": manifest["candidate"], "artifacts": []}
    artifacts: list[dict[str, object]] = results["artifacts"]  # type: ignore[assignment]

    if args.jar:
        jar_hash = sha256(args.jar.read_bytes())
        if jar_hash != expected["jar_sha256"]:
            raise ValueError(
                f"{args.jar}: JAR SHA-256 mismatch: expected={expected['jar_sha256']} actual={jar_hash}"
            )
        elf = read_zip_entry(args.jar, expected["entry"])
        if elf is None:
            raise ValueError(f"{args.jar}: missing {expected['entry']}")
        artifacts.append(
            {
                "type": "approved-jar",
                "path": str(args.jar),
                "jar_sha256": jar_hash,
                "elf": check_elf(str(args.jar), elf, expected, exact_hash=True),
            }
        )

    for candidate in args.candidate_jar:
        elf = read_zip_entry(candidate, expected["entry"])
        if elf is None:
            raise ValueError(f"{candidate}: missing {expected['entry']}")
        artifacts.append(
            {
                "type": "rebuilt-jar",
                "path": str(candidate),
                "jar_sha256": sha256(candidate.read_bytes()),
                "elf": check_elf(str(candidate), elf, expected, exact_hash=True),
            }
        )

    arm64_apk_count = 0
    required_arm64_apk_kinds = {"arm64-v8a": False, "universal": False}
    for apk in args.apk:
        elf = read_zip_entry(apk, expected["entry"])
        matching_kinds = [kind for kind in required_arm64_apk_kinds if kind in apk.name]
        expects_arm64 = bool(matching_kinds)
        if elf is None:
            if expects_arm64:
                raise ValueError(f"{apk}: expected arm64 native entry is missing")
            artifacts.append({"type": "apk-without-arm64", "path": str(apk), "sha256": sha256(apk.read_bytes())})
            continue
        arm64_apk_count += 1
        for kind in matching_kinds:
            required_arm64_apk_kinds[kind] = True
        artifacts.append(
            {
                "type": "apk-with-arm64",
                "path": str(apk),
                "sha256": sha256(apk.read_bytes()),
                "elf": check_elf(str(apk), elf, expected, exact_hash=False),
            }
        )
    if args.apk and arm64_apk_count == 0:
        raise ValueError("none of the supplied APKs contained the approved arm64 native entry")
    if args.apk:
        missing_kinds = [kind for kind, present in required_arm64_apk_kinds.items() if not present]
        if missing_kinds:
            raise ValueError(
                "required packaged arm64 output(s) missing: " + ", ".join(missing_kinds)
            )
    if not artifacts:
        parser.error("provide --jar, --candidate-jar, or --apk")

    rendered = json.dumps(results, indent=2, sort_keys=True)
    print(rendered)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"native artifact verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
