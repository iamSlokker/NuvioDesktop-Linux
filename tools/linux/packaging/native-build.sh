#!/usr/bin/env bash
set -euo pipefail
unset DISPLAY WAYLAND_DISPLAY NUVIO_RUN_LIVE_DISPLAY_TESTS LD_PRELOAD LD_LIBRARY_PATH
export LC_ALL=C TZ=UTC PYTHONDONTWRITEBYTECODE=1
mkdir -p "$HOME"
cd /source
[[ ! -e local.properties ]] || { echo 'Refusing developer configuration'; exit 1; }
# Feed upstream's existing generator reviewed public defaults, never developer
# configuration. This file exists only in the disposable source snapshot.
cp tools/linux/packaging/public-client.properties local.properties
offline=()
[[ ${NUVIO_GRADLE_OFFLINE:-0} != 1 ]] || offline=(--offline)
# Explicit channel avoids resolveDesktopChannel's local-user preference fallback.
./gradlew :composeApp:createDistributable :composeApp:compileKotlinDesktop \
  :composeApp:compileTestKotlinDesktop :composeApp:desktopTest \
  --tests '*LinuxPackagedNativeRuntimeTest' --tests '*NativeRuntimeLayoutTest' \
  --tests '*DesktopMpvRuntimeOverrideTest' --tests '*LinuxPlayerOwnershipTest' \
  --tests '*LinuxHeroHostGeometryTest' \
  -Pnuvio.desktop.channel="$NUVIO_BUILD_CHANNEL" \
  --no-daemon --no-configuration-cache --no-build-cache --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' "${offline[@]}"
cmake -S composeApp/src/desktopMain/native/linux -B /output/native \
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DMPV_ROOT=/media -DNUVIO_PACKAGE_BUILD=ON \
  -DCMAKE_CXX_FLAGS='-ffile-prefix-map=/source=. -fdebug-prefix-map=/source=.'
cmake --build /output/native --parallel "$NUVIO_BUILD_JOBS" \
  --target player_bridge linux_separate_audio_test linux_http_recovery_test linux_audio_recovery_test
/output/native/linux_separate_audio_test
/output/native/linux_http_recovery_test
/output/native/linux_audio_recovery_test
python3 tools/linux/packaging/stage.py /output
python3 tools/linux/packaging/verify.py image /output/canonical
python3 tools/linux/packaging/formats.py /output --deb-only
python3 tools/linux/packaging/verify.py artifacts /output --formats deb
