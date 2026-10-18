# resources

Linux counterparts of upstream's Windows-only globs in `../../native-image/resources/`:

- `jni/linux_x64/**`: quickjs-kt extracts `libquickjs.so` from its jar. Without it every plugin
  returns 0 streams (upstream's `jni/windows_x64/**` note). Probes: "plugin runtime",
  "plugin js error".
- `ai/onnxruntime/native/linux-x64/**`: onnxruntime extracts `libonnxruntime.so` and its JNI
  library from the jar (the build trims the jar to these). Probe: "backdrop framing models".
- `backdrop-framing/**`, `setup/**`, `anime-list-mini.json`: `PackagedResources.open` reads these
  from the install's `resources/` folder on Windows, where the build stages them loose
  (`externalDesktopResources`). The Linux packages keep them in the jar, so the image must carry
  them. Probe: "backdrop framing models"; the setup screenshots show on first run; the anime ID
  mapping (`AnimeIdMappingStorage`) loads the dataset like upstream, with no download.
