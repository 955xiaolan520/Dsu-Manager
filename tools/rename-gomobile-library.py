#!/usr/bin/env python3
"""Relocate Go Mobile runtime classes/symbols to avoid collision with Gopeed's go.Seq."""
from __future__ import annotations

import io
import os
import struct
import sys
import zipfile
from pathlib import Path

OLD_LIBRARY = b"gojni"
NEW_LIBRARY = b"dsuparsevideo"
OLD_RUNTIME_PATH = b"go/"
NEW_RUNTIME_PATH = b"g2/"  # Same length as "go/" so ELF/class references remain patchable in place.
OLD_RUNTIME_DOTTED = b"go."
NEW_RUNTIME_DOTTED = b"g2."
OLD_JNI_PREFIX = b"Java_go_Seq_"
NEW_JNI_PREFIX = b"Java_g2_Seq_"


def patch_class(data: bytes) -> tuple[bytes, int, int]:
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a Java class file")
    count = struct.unpack_from(">H", data, 8)[0]
    cursor = 10
    output = bytearray(data[:10])
    patched_loader = 0
    relocated_refs = 0
    index = 1
    while index < count:
        tag = data[cursor]
        output.append(tag)
        cursor += 1
        if tag == 1:  # CONSTANT_Utf8
            length = struct.unpack_from(">H", data, cursor)[0]
            cursor += 2
            value = data[cursor:cursor + length]
            cursor += length
            if value == OLD_LIBRARY:
                value = NEW_LIBRARY
                patched_loader += 1
            updated = value.replace(OLD_RUNTIME_PATH, NEW_RUNTIME_PATH).replace(
                OLD_RUNTIME_DOTTED, NEW_RUNTIME_DOTTED
            )
            if updated != value:
                relocated_refs += 1
                value = updated
            output.extend(struct.pack(">H", len(value)))
            output.extend(value)
        elif tag in (3, 4):  # Integer / Float
            output.extend(data[cursor:cursor + 4])
            cursor += 4
        elif tag in (5, 6):  # Long / Double take two constant-pool slots
            output.extend(data[cursor:cursor + 8])
            cursor += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            output.extend(data[cursor:cursor + 2])
            cursor += 2
        elif tag in (9, 10, 11, 12, 17, 18):  # refs, NameAndType, Dynamic, InvokeDynamic
            output.extend(data[cursor:cursor + 4])
            cursor += 4
        elif tag == 15:  # MethodHandle
            output.extend(data[cursor:cursor + 3])
            cursor += 3
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
        index += 1
    output.extend(data[cursor:])
    return bytes(output), patched_loader, relocated_refs


def rewrite_jar(data: bytes) -> tuple[bytes, int, int]:
    target = io.BytesIO()
    patched_loader = 0
    relocated_refs = 0
    with zipfile.ZipFile(io.BytesIO(data), "r") as zin, zipfile.ZipFile(target, "w") as zout:
        for item in zin.infolist():
            name = item.filename
            content = zin.read(name)
            if name.endswith(".class"):
                content, loader_count, ref_count = patch_class(content)
                patched_loader += loader_count
                relocated_refs += ref_count
                if name.startswith("go/"):
                    name = "g2/" + name[3:]
            zout.writestr(item if name == item.filename else _copy_info(item, name), content)
    return target.getvalue(), patched_loader, relocated_refs


def _copy_info(source: zipfile.ZipInfo, name: str) -> zipfile.ZipInfo:
    target = zipfile.ZipInfo(name, source.date_time)
    target.compress_type = source.compress_type
    target.comment = source.comment
    target.extra = source.extra
    target.internal_attr = source.internal_attr
    target.external_attr = source.external_attr
    target.create_system = source.create_system
    return target


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <gomobile.aar>")
    aar = Path(sys.argv[1]).resolve()
    if not aar.is_file():
        raise SystemExit(f"AAR not found: {aar}")

    temporary = aar.with_suffix(aar.suffix + ".tmp")
    patched_loader = relocated_refs = relocated_jni = renamed_libraries = 0
    with zipfile.ZipFile(aar, "r") as zin, zipfile.ZipFile(temporary, "w") as zout:
        for item in zin.infolist():
            name = item.filename
            content = zin.read(name)
            if name == "classes.jar":
                content, loader_count, ref_count = rewrite_jar(content)
                patched_loader += loader_count
                relocated_refs += ref_count
            elif name.startswith("jni/") and name.endswith("/libgojni.so"):
                content = content.replace(OLD_RUNTIME_PATH, NEW_RUNTIME_PATH)
                content = content.replace(OLD_RUNTIME_DOTTED, NEW_RUNTIME_DOTTED)
                content = content.replace(OLD_JNI_PREFIX, NEW_JNI_PREFIX)
                relocated_jni += content.count(NEW_JNI_PREFIX)
                name = name[:-len("libgojni.so")] + "libdsuparsevideo.so"
                renamed_libraries += 1
            zout.writestr(item if name == item.filename else _copy_info(item, name), content)

    if patched_loader < 1 or relocated_refs < 1 or relocated_jni < 1 or renamed_libraries < 2:
        temporary.unlink(missing_ok=True)
        raise SystemExit(
            "unexpected Go Mobile AAR: "
            f"loadLibrary patches={patched_loader}, class refs relocated={relocated_refs}, "
            f"JNI symbols relocated={relocated_jni}, libraries renamed={renamed_libraries}"
        )
    os.replace(temporary, aar)
    print(
        f"Patched loadLibrary={patched_loader}; relocated class refs={relocated_refs}; "
        f"JNI symbols={relocated_jni}; renamed libraries={renamed_libraries}"
    )


if __name__ == "__main__":
    main()
