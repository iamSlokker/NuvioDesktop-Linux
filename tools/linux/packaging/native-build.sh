#!/usr/bin/env bash
# Inside the pinned builder. Two runs (build.build_in_builder):
#   build    Gradle (network for dependencies), GraalVM native-image, JNI bridge, staging
#   package  without network: native smoke gate on the staged image, then the DEB
set -euo pipefail
unset DISPLAY WAYLAND_DISPLAY NUVIO_RUN_LIVE_DISPLAY_TESTS LD_PRELOAD LD_LIBRARY_PATH
export LC_ALL=C TZ=UTC PYTHONDONTWRITEBYTECODE=1
mkdir -p "$HOME"
cd /source
phase=${1:?usage: native-build.sh build|package}
if [[ $phase == package ]]; then
  # Launches the staged package only in this disposable container (private Xvfb,
  # D-Bus and profile, no network); fails on any failed probe or native-image error.
  python3 tools/linux/packaging/native_smoke.py /output
  python3 tools/linux/packaging/formats.py /output --deb-only
  python3 tools/linux/packaging/verify.py artifacts /output --formats deb
  exit 0
fi
[[ $phase == build ]] || { echo "Unknown phase: $phase"; exit 1; }
[[ ! -e local.properties ]] || { echo 'Refusing developer configuration'; exit 1; }
# Feed upstream's existing generator reviewed public defaults, never developer
# configuration. This file exists only in the disposable source snapshot.
cp tools/linux/packaging/public-client.properties local.properties
offline=()
[[ ${NUVIO_GRADLE_OFFLINE:-0} != 1 ]] || offline=(--offline)
gradle_flags=(-Pnuvio.desktop.channel="$NUVIO_BUILD_CHANNEL"
  --no-daemon --no-configuration-cache --no-build-cache --max-workers=2
  -Pkotlin.compiler.execution.strategy=in-process
  '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' "${offline[@]}")
# Explicit channel avoids resolveDesktopChannel's local-user preference fallback.
./gradlew :composeApp:createDistributable :composeApp:generateNativeMetadata :composeApp:compileKotlinDesktop \
  :composeApp:compileTestKotlinDesktop :composeApp:desktopTest \
  --tests '*LinuxPackagedNativeRuntimeTest' --tests '*NativeRuntimeLayoutTest' \
  --tests '*DesktopMpvRuntimeOverrideTest' --tests '*LinuxPlayerOwnershipTest' \
  --tests '*LinuxHeroHostGeometryTest' --tests '*LinuxNativeImageMetadataTest' \
  --tests '*NativeImageMetadataTest' --tests '*LinuxDesktopRelaunchTest' "${gradle_flags[@]}"
# The shipped executable: GraalVM native-image over those jars (a separate Gradle
# run, so the compiler daemon's heap is gone before native-image's ~10 GB peak).
# Never a QA build; the layout is composeApp/native-image-linux/README.md's.
./gradlew :composeApp:createLinuxNativeDistributable -Pnuvio.graalvm.home=/opt/graalvm \
  -Pnuvio.native.builderHeap="${NUVIO_NATIVE_BUILDER_HEAP:-9g}" -Pnuvio.native.qa=false "${gradle_flags[@]}"
# nuvio-hdr-probe (required by NUVIO_PACKAGE_BUILD) uses the media runtime's
# build-only SDK area: pinned wayland-scanner, color-management-v1.xml and Vulkan
# headers/loader (the builder's wayland-protocols/scanner are too old). Only the
# builder's wayland-client development interface comes from Ubuntu; at run time the
# helper resolves the bundled libwayland-client/libvulkan through its RUNPATH.
cmake -S composeApp/src/desktopMain/native/linux -B /output/native \
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DMPV_ROOT=/media -DNUVIO_PACKAGE_BUILD=ON \
  -DNUVIO_WAYLAND_SCANNER=/media/bin/wayland-scanner \
  -DNUVIO_WAYLAND_PROTOCOLS_DIR=/media/share/wayland-protocols \
  -DNUVIO_VULKAN_INCLUDE_DIR=/media/include -DNUVIO_VULKAN_LIBRARY=/media/lib/libvulkan.so \
  -DCMAKE_C_FLAGS='-ffile-prefix-map=/source=. -fdebug-prefix-map=/source=.' \
  -DCMAKE_CXX_FLAGS='-ffile-prefix-map=/source=. -fdebug-prefix-map=/source=.'
cmake --build /output/native --parallel "$NUVIO_BUILD_JOBS" \
  --target player_bridge nuvio-hdr-probe quickjs linux_separate_audio_test linux_http_recovery_test \
  linux_audio_recovery_test linux_hdr_session_test linux_auto_crop_test linux_speech_spot_test \
  linux_live_cache_seek_test linux_hud_labels_carry_test
/output/native/linux_separate_audio_test
/output/native/linux_auto_crop_test
/output/native/linux_speech_spot_test
/output/native/linux_live_cache_seek_test
/output/native/linux_hud_labels_carry_test
/output/native/linux_http_recovery_test
/output/native/linux_audio_recovery_test
# Display variables are unset above; the canonical runtime must report waylandvk.
/output/native/linux_hdr_session_test --expect-wayland=yes
python3 tools/linux/packaging/stage.py /output
python3 tools/linux/packaging/verify.py image /output/canonical --native-evidence /output/native-image
