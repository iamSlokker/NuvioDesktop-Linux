# Linux native-image build inputs

Everything the Linux x86_64 GraalVM native-image build needs besides the app's jars, upstream's
shared topics in `../native-image/` and the generated metadata. The tasks live in
`../native-image-linux.gradle.kts`; upstream's `../native-image/README.md` explains the approach
and its rules, which apply here unchanged (no BOM, one reason per topic, a hand entry stays only
while something fails without it).

| Path | What |
|---|---|
| `agent/` | Linux trace from `linuxNativeImageTrace` (GraalVM 25 agent, `reachability-metadata.json`). Do not hand-edit: re-trace instead. |
| `<topic>/reachability-metadata.json` | Hand-written entries the trace and generation miss, one directory per reason, each with a `README.md`. |

`linuxNativeCompile` passes, in this order: `agent/`, the topics here, upstream's topics (not its
`agent/`, a Windows trace), and `build/native-image/generated/`.

**Generated** (upstream's `generateNativeMetadata`, pointed at the Linux jpackage image and
`src/desktopMain/native/linux/*.cpp|*.h` on a Linux host): every serializer, and every JNI lookup
in the Linux bridge, including the callbacks its mpv / GLib threads make after
`AttachCurrentThreadAsDaemon` (`NativePlayerEventSink`, `LinuxMprisCommandSink`, `Runnable`,
`LinuxFlatpakProgressCallback`) and the JDK-internal `sun.awt.X11.XlibWrapper` the X11 lifecycle
calls. Those lookups name an interface with `FindClass("literal")`, never the callback object's
runtime class (`GetObjectClass`), which generation cannot read and an image has no metadata for.
`LinuxNativeImageMetadataTest` enforces that without a native build.

## Layout

`createLinuxNativeDistributable` builds what the packages will contain:

```
bin/nuvio              launcher script: exec ../lib/nuvio
lib/nuvio              the native executable (jpackage.app-path, set by NativeImageSupport)
lib/lib*.so            JDK libraries native-image emits (libawt, libawt_xawt, libfontmanager, ...)
lib/app/               nuvio.cfg (its -D options are applied at run time), skiko .so, build stamp
lib/runtime/lib/       java.home: libjawt.so (skiko, libplayer_bridge.so) and font properties
lib/native/            -Pnuvio.linux.nativeRuntimeDir: libplayer_bridge.so, libmpv and its closure
```

`NativeImageSupport` sets `java.home` to `lib/runtime`, so `NativePlayerBridge` finds `lib/native`
exactly as in the JVM package, and `LinuxDesktopRelaunch` finds the packages' `bin/nuvio-desktop`
one level up from `lib/nuvio`. libjawt.so resolves `libawt.so` by soname to the copy already loaded
from `lib/`; the "linux jdk libraries" probe fails if any of them is mapped twice.

## Running it

GraalVM for JDK 25 (`-Pnuvio.graalvm.home`). native-image runs on the Gradle machine, or with
`-Pnuvio.linux.nativeImageContainer=<image>` in that container; use the package builder image, so
the glibc floor is Ubuntu 22.04's and not the developer's (4.5 min on 8 threads, 9-11 GB peak RSS
with `-Pnuvio.native.builderHeap=9g`: never two at once; the executable needs GLIBC_2.34).

GUI launches (`linuxNativeSmoke`, `linuxNativeImageTrace`) run only in the throwaway container
from `../scripts/linux-native-smoke.Containerfile` (`-Pnuvio.linux.smokeImage`): private Xvfb,
private D-Bus, empty profile, no network. `../scripts/linux-native-smoke.py` refuses to start
outside a container.

```
podman build -t localhost/nuvio-native-smoke:jammy -f composeApp/scripts/linux-native-smoke.Containerfile composeApp/scripts
P="-Pnuvio.graalvm.home=<graalvm-25> -Pnuvio.linux.nativeRuntimeDir=<lib/native of a release, with a fresh libplayer_bridge.so> \
   -Pnuvio.linux.nativeImageContainer=<package builder image> -Pnuvio.linux.smokeImage=localhost/nuvio-native-smoke:jammy"
./gradlew :composeApp:linuxNativeImageTrace $P                          # refresh agent/
./gradlew :composeApp:createLinuxNativeDistributable $P -Pnuvio.native.qa=true   # QA build
./gradlew :composeApp:linuxNativeSmoke $P                               # release gate
```

The smoke launches `bin/nuvio` (default 3 times) with `NUVIO_NATIVE_SELFTEST`: upstream's probes
plus the Linux ones in `LinuxNativeSelfTest.kt` (JDK libraries loaded once, player bridge, X11/JAWT
drawable, a local y4m played through libmpv with the WebKitGTK HUD and mpv-thread events, MPRIS on
a private bus with a command delivered from the bus thread, inhibitor round trip). It fails without
a painted first frame, without `failed=0`, or on any missing-registration / fatal error in the
console. A QA build (`-Pnuvio.native.qa=true`) reports missing registrations and carries on: never
package one (`lib/native-qa` marks it, and its console is copied to `lib/native-qa-console.log`).

## History

2026-10-17, GraalVM CE 25.0.2: first trace (self-test + 60 s idle, signed out). The first QA build
passed every probe and reported two gaps, now `ktor-sockets/` and `font-strikes/`; the second QA
build (two launches, 45 s idle each) and the release smoke (3 launches) reported none. Measured in
the smoke container (software rendering): first frame 1.28-1.38 s native vs 2.87-2.92 s JVM;
`lib/nuvio` 315 MB, layout without `lib/native` 349 MB (92 MiB zstd -19) vs the JVM image's
232 MB (128 MiB). Only paths the trace and the probes reach are covered; signed-in screens,
playback of real streams, plugins' network use and the updater have not been through a QA build.
