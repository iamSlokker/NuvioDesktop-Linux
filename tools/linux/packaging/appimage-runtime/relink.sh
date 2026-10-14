#!/bin/sh
# Relink the AppImage type2 runtime from runtime.o and static libraries.
# Usage: relink.sh KIT_DIR OUTPUT_DIR [--trace]
# KIT_DIR holds runtime.o, data_sections.ld and lib/*.a (replace lib/libfuse3.a
# with a modified build to exercise LGPL-2.1 section 6). Run inside the pinned
# toolchain image (see RELINK.md); the final steps follow upstream
# scripts/build-runtime.sh at type2-runtime dd6cebed.
set -eu
kit=$(cd "$1" && pwd)
mkdir -p "$2"
out=$(cd "$2" && pwd)
trace=
[ "${3:-}" = --trace ] && trace=-Wl,--trace
cd "$out"
clang -static -static-pie -T "$kit/data_sections.ld" -Wl,--gc-sections $trace "$kit/runtime.o" \
    -L"$kit/lib" -lsquashfuse -lsquashfuse_ll -lzstd -lz -lfuse3 -lmimalloc -o runtime
objcopy --only-keep-debug runtime runtime.debug
strip --strip-debug --strip-unneeded runtime
mv runtime runtime-x86_64
mv runtime.debug runtime-x86_64.debug
objcopy --add-gnu-debuglink runtime-x86_64.debug runtime-x86_64
# "classic" AppImage magic bytes; must follow strip/objcopy.
printf 'AI\002' | dd of=runtime-x86_64 bs=1 count=3 seek=8 conv=notrunc 2>/dev/null
