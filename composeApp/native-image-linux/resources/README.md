# resources

Linux counterparts of upstream's Windows-only globs in `../../native-image/resources/`:

- `jni/linux_x64/**`: quickjs-kt extracts `libquickjs.so` from its jar. Without it every plugin
  returns 0 streams (upstream's `jni/windows_x64/**` note). Probes: "plugin runtime",
  "plugin js error".
- `ai/onnxruntime/native/linux-x64/**`: onnxruntime extracts `libonnxruntime.so` and its JNI
  library from the jar (the build trims the jar to these). Probe: "backdrop framing models".
- `backdrop-framing/**`, `anime-list-mini.json`: `PackagedResources.open` reads these
  from the install's `resources/` folder on Windows, where the build stages them loose
  (`externalDesktopResources`). The Linux packages keep them in the jar, so the image must carry
  them. Probe: "backdrop framing models"; the setup screenshots show on first run; the anime ID
  mapping (`AnimeIdMappingStorage`) loads the dataset like upstream, with no download.
- `audiosync/**`: the Silero VAD weights (`silero_vad_v5_16k.bin`) that audio-referenced subtitle
  sync (`DesktopAudioSubtitleSync`) reads with `getResourceAsStream`. No upstream topic lists the
  folder, so without this the packaged app's Auto Sync had no voice detector (Umbra snapshot
  148524e33, 2026-10-22).
- `com/sun/swing/internal/plaf/{basic,metal}/resources/*_*.properties` (module `java.desktop`):
  Swing's `UIDefaults` probes a `.properties` variant of its locale bundles (`basic_en_US`, ...)
  before using the compiled `basic`/`metal` classes. The files do not exist; registering the
  globs only makes the probe a normal miss instead of a QA-build report (owner's signed-in QA
  build, 2026-10-18).
