#!/bin/sh
# Compile the AppImage type2 runtime from verified source inputs.
#
# Runs inside the digest-pinned Alpine toolchain image with --network=none.
# /inputs: hash-verified tarballs (read-only), /recipe: this directory
# (read-only), /out: result directory. Mirrors upstream type2-runtime
# dd6cebed scripts/common/install-dependencies.sh, src/runtime/Makefile and
# scripts/build-runtime.sh, but every library comes from a pinned tarball
# instead of an unpinned Alpine package.
set -eu

: "${SOURCE_DATE_EPOCH:?}" "${RUNTIME_VERSION:?}" "${JOBS:=4}"
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH
umask 022

SRC=/tmp/build/src
KIT=/out/relink
MAP="-ffile-prefix-map=$SRC/="
rm -rf /tmp/build "$KIT"
mkdir -p "$SRC" "$KIT/lib"

for input in zstd zlib mimalloc libfuse squashfuse type2-runtime; do
    tar --no-same-owner --no-same-permissions -xf "/inputs/$input" -C "$SRC"
done

# Static compression/allocator libraries (upstream: Alpine zstd-static,
# zlib-static and mimalloc2-dev, whose libmimalloc.a is the MI_SECURE build).
cd "$SRC"/zstd-*/
make -C lib -j"$JOBS" libzstd.a CFLAGS="-O2 -ffunction-sections -fdata-sections $MAP"
make -C lib install-static install-includes PREFIX=/usr/local CFLAGS="-O2 -ffunction-sections -fdata-sections $MAP"

cd "$SRC"/zlib-*/
CFLAGS="-O2 -ffunction-sections -fdata-sections $MAP" ./configure --static --prefix=/usr/local
make -j"$JOBS" libz.a
make install

cd "$SRC"/mimalloc-*/
cmake -S . -B out -G 'Unix Makefiles' -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local \
    -DCMAKE_C_FLAGS="-D__USE_ISOC11 -ffunction-sections -fdata-sections $MAP" \
    -DMI_SECURE=ON -DMI_BUILD_SHARED=OFF -DMI_BUILD_OBJECT=OFF -DMI_BUILD_TESTS=OFF -DMI_INSTALL_TOPLEVEL=ON
cmake --build out -j"$JOBS"
cmake --install out
cp /usr/local/lib/libmimalloc-secure.a "$KIT/lib/libmimalloc.a"

# libfuse with the upstream runtime's FUSERMOUNT_PROG patch (meson default
# buildtype, as upstream; programs/examples/tests are not part of the runtime).
cd "$SRC"/fuse-3.15.0/
patch -p1 < "$(echo "$SRC"/type2-runtime-*/patches/libfuse/mount.c.diff)"
CFLAGS="$MAP" meson setup build --prefix=/usr -Ddefault_library=static \
    -Dutils=false -Dexamples=false -Dtests=false
ninja -C build -j"$JOBS" install

# squashfuse exactly as upstream (CFLAGS, LDFLAGS=-static), restricted to the
# decompressors upstream's image provided (zlib, zstd).
cd "$SRC"/squashfuse-0.5.2/
./autogen.sh
CFLAGS="-ffunction-sections -fdata-sections -Os $MAP" ./configure LDFLAGS="-static -L/usr/local/lib" \
    CPPFLAGS=-I/usr/local/include --with-zlib=/usr/local --with-zstd=/usr/local \
    --without-xz --without-lzo --without-lz4
grep -q '^#define HAVE_ZSTD_H 1' config.h && grep -q '^#define HAVE_ZLIB_H 1' config.h
make -j"$JOBS"
make install
/usr/bin/install -c -m 644 ./*.h /usr/local/include/squashfuse

cp /usr/local/lib/libzstd.a /usr/local/lib/libz.a /usr/local/lib/libsquashfuse.a \
   /usr/local/lib/libsquashfuse_ll.a /usr/lib/libfuse3.a "$KIT/lib/"
# The official link below must see only the kit libraries.
rm -f /usr/local/lib/libzstd.a /usr/local/lib/libz.a /usr/local/lib/libmimalloc*.a \
      /usr/local/lib/libsquashfuse.a /usr/local/lib/libsquashfuse_ll.a /usr/lib/libfuse3.a

# Runtime object: upstream Makefile CFLAGS, compile step only.
cd "$SRC"/type2-runtime-*/src/runtime
clang -I/usr/local/include/squashfuse -I/usr/include/fuse3 -std=gnu99 -Os -D_FILE_OFFSET_BITS=64 \
    -DGIT_COMMIT="\"$RUNTIME_VERSION\"" -ffunction-sections -fdata-sections -Wall -Werror -fPIE $MAP \
    -c runtime.c -o "$KIT/runtime.o"
cp data_sections.ld "$KIT/"
cp /recipe/relink.sh "$KIT/"
chmod 0644 "$KIT"/lib/*.a "$KIT/runtime.o" "$KIT/data_sections.ld"
chmod 0755 "$KIT/relink.sh"

# Link (and strip/debuglink/magic) from the relink kit only, so the shipped
# runtime is provably reproducible from the shipped objects.
sh "$KIT/relink.sh" "$KIT" /out
sh "$KIT/relink.sh" "$KIT" /tmp/build/trace --trace > /out/linked-inputs.txt

apk info -v | sort > /out/apk-info.txt
awk -v RS= '/\nP:(musl|musl-dev|gcc|libgcc|clang19|binutils)\n/' /lib/apk/db/installed \
    | grep -E '^(P|V|c):' > /out/apk-commits.txt
{ clang --version | head -1; gcc --version | head -1; ld --version | head -1; } > /out/toolchain.txt
