#!/usr/bin/env bash
set -euo pipefail
unset DISPLAY WAYLAND_DISPLAY NUVIO_RUN_LIVE_DISPLAY_TESTS LD_PRELOAD LD_LIBRARY_PATH
export LC_ALL=C TZ=UTC
# Ubuntu patches add sources to Makefile.am. Regenerate instead of silently
# building the upstream release's stale Makefile.in without those sources.
python3 /recipe/prepare-gtk.py
mkdir -p /work/gtk-objects
cd /work/gtk-objects
/work/gtk-source/configure --prefix=/opt/nuvio-webkit --libdir=/opt/nuvio-webkit/lib/native \
  --disable-wayland-backend --enable-x11-backend --disable-introspection \
  --disable-gtk-doc --disable-man --disable-installed-tests --disable-cups --disable-maintainer-mode \
  --disable-schemas-compile CFLAGS='-O2 -g0 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -ffile-prefix-map=/work=.' \
  LDFLAGS='-Wl,-z,relro,-z,now'
/usr/bin/time -v -o /work/gtk-build-resources.txt make -j2
make DESTDIR=/work/install install
