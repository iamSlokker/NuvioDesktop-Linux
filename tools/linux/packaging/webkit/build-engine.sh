#!/usr/bin/env bash
set -euo pipefail
unset DISPLAY WAYLAND_DISPLAY NUVIO_RUN_LIVE_DISPLAY_TESTS LD_PRELOAD LD_LIBRARY_PATH
export LC_ALL=C TZ=UTC CCACHE_DIR=/work/ccache CCACHE_BASEDIR=/work
[[ -r /sys/fs/cgroup/memory.max && $(cat /sys/fs/cgroup/memory.max) != max ]] || {
  echo 'A real container memory limit is required for the engine build' >&2; exit 1;
}
ccache --max-size=8G
cmake -S /work/source/webkitgtk-2.50.4 -B /work/objects -G Ninja \
  -DPORT=GTK -DCMAKE_BUILD_TYPE=Release -DDEVELOPER_MODE=OFF \
  -DCMAKE_C_COMPILER=gcc-12 -DCMAKE_CXX_COMPILER=g++-12 \
  -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
  -DCMAKE_C_FLAGS_RELEASE='-O2 -DNDEBUG -g0 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -ffile-prefix-map=/work=. -ffile-prefix-map=../source/webkitgtk-2.50.4/= -ffile-prefix-map=/work/source/webkitgtk-2.50.4/=' \
  -DCMAKE_CXX_FLAGS_RELEASE='-O2 -DNDEBUG -g0 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -ffile-prefix-map=/work=. -ffile-prefix-map=../source/webkitgtk-2.50.4/= -ffile-prefix-map=/work/source/webkitgtk-2.50.4/=' \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,--no-keep-memory,-z,relro,-z,now \
  -DCMAKE_MODULE_LINKER_FLAGS=-Wl,--no-keep-memory,-z,relro,-z,now \
  -DCMAKE_EXE_LINKER_FLAGS=-Wl,--no-keep-memory,-z,relro,-z,now \
  -DCMAKE_JOB_POOLS=link=1 -DCMAKE_JOB_POOL_LINK=link \
  -DCMAKE_INSTALL_PREFIX=/opt/nuvio-webkit -DCMAKE_INSTALL_LIBDIR=lib/native \
  -DLIBEXEC_INSTALL_DIR=/opt/nuvio-webkit/lib/webkit2gtk-4.1 \
  -DUSE_GTK4=OFF -DUSE_SOUP2=OFF -DENABLE_BUBBLEWRAP_SANDBOX=ON \
  -DENABLE_DOCUMENTATION=OFF -DENABLE_INTROSPECTION=OFF \
  -DENABLE_API_TESTS=OFF -DENABLE_LAYOUT_TESTS=OFF \
  -DENABLE_MINIBROWSER=OFF -DENABLE_WEBDRIVER=OFF \
  -DUSE_JPEGXL=OFF -DUSE_SYSTEM_SYSPROF_CAPTURE=OFF -DUSE_LIBBACKTRACE=OFF \
  -DENABLE_WAYLAND_TARGET=OFF
python3 /recipe/test-compiler-paths.py /work/objects/CMakeCache.txt
/usr/bin/time -v -o /work/build-resources.txt cmake --build /work/objects --parallel 4
cat /sys/fs/cgroup/memory.peak > /work/container-memory-peak.bytes
DESTDIR=/work/install cmake --install /work/objects
cp /webkit-builder-packages.tsv /work/
ccache --show-stats > /work/ccache-stats.txt
