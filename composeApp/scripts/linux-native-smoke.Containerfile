# Throwaway environment for linuxNativeSmoke / linuxNativeImageTrace (linux-native-smoke.py):
# the packages' Ubuntu 22.04 floor with the system libraries a Linux Nuvio package expects, a
# private X server, a D-Bus daemon, and lavapipe so libmpv's gpu-next/Vulkan output works without a
# GPU. Nothing from the developer's session is ever mounted into it. A test environment only;
# nothing built here is shipped.
#
#   podman build -t localhost/nuvio-native-smoke:jammy -f composeApp/scripts/linux-native-smoke.Containerfile composeApp/scripts
#
# From libc6 to xdg-utils the list is the .deb's Depends (tools/linux/packaging/formats.py), so the
# smoke runs against what an installed package can count on. They come from the same dated APT snapshot as the package builder. The snapshot is
# https-only and the base has no CA bundle, so ca-certificates comes from the base's own archive.
FROM docker.io/library/ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && printf '%s\n' \
      'deb https://snapshot.ubuntu.com/ubuntu/20261001T000000Z jammy main universe' \
      'deb https://snapshot.ubuntu.com/ubuntu/20261001T000000Z jammy-updates main universe' \
      'deb https://snapshot.ubuntu.com/ubuntu/20261001T000000Z jammy-security main universe' \
      > /etc/apt/sources.list \
 && rm -rf /var/lib/apt/lists/* \
 && apt-get -o Acquire::Check-Valid-Until=false update \
 && apt-get install -y --no-install-recommends \
      python3 xvfb xauth dbus libglib2.0-bin libgl1-mesa-dri mesa-vulkan-drivers libvulkan1 \
      libxtst6 libxi6 libxrender1 fontconfig fonts-dejavu-core \
      libc6 libstdc++6 libgcc-s1 libatomic1 libgtk-3-0 libwebkit2gtk-4.1-0 libx11-6 libxext6 \
      libxrandr2 libxss1 libxcb1 libwayland-client0 libva2 libva-drm2 libva-x11-2 libdrm2 libasound2 \
      libpulse0 libfontconfig1 libfreetype6 libfribidi0 libharfbuzz0b libssl3 zlib1g liblcms2-2 \
      libudev1 libgl1 xdg-utils \
 && rm -rf /var/lib/apt/lists/*
# Software-only X server: WebKit's accelerated compositing tears down a GLX window that is already
# gone (fatal X error in the HUD), and libplacebo only uses a software Vulkan device when it is named.
ENV WEBKIT_DISABLE_COMPOSITING_MODE=1
ENV NUVIO_NATIVE_SELFTEST_MPV_OPTIONS="vulkan-device=llvmpipe (LLVM 15.0.7, 256 bits)"
