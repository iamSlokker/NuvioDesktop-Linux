# Building and installing a modified Linux copy

These are concrete entry points into the existing build, replacement and package
verification paths. They are **not a tested complete offline rebuild kit**, a
license interpretation or authorization to publish. No binary was rebuilt or
installed while preparing these instructions. Work in a disposable checkout,
output directory and OS account/VM; leave any installed Nuvio and its data alone.

## Select the right source and dependencies

1. Read the selected binary's `release-info.json`. Match its full `git_commit`,
   `source_sha256`, media build identity and, for AppImage, engine provenance.
   The historical audited packages are commit
   `fce98dc1633b51f813c20965e05941c2745beb8c`, **not** the current branch.
2. Obtain that exact Git history and the matching application source artifact.
   Compare the source artifact's `source-files.json` against the Git objects and
   retained bytes with `compliance/inventory.py`. The tar alone does not contain
   the Git metadata used by `packaging/build.py`; do not synthesize a new Git
   commit and label it the original. Make modifications only after verification.
3. Supply the pinned Temurin build JDK, the pinned GraalVM CE 25.0.2 (both in
   `tools/linux/packaging/config.json`; the packaging builder installs them),
   wrapper/Gradle dependencies, private media runtime, immutable packaging builder
   and, where needed, verified WebKit engine.
   Exact receipts and offline source checks are in the
   [source-delivery plan](source-delivery-plan.md) and
   [tool instructions](../../tools/linux/packaging/compliance/README.md).
   Rootless Podman and the host orchestration tools listed in the packaging
   README are prerequisites, not files silently obtained from a developer's HOME.
4. Use `tools/linux/packaging/public-client.properties`; never copy a developer's
   `local.properties`. The packaging script supplies these reviewed public values
   inside its disposable source tree. Explicitly select `nightly` for modified
   development builds. Stable packaging rejects a dirty source tree.

The retained inputs do not yet establish every static/native build dependency.
In particular, Skia/ONNX/Jansi/prebuilt JNI and AppImage-runtime static closure
remain bounded gaps described in the existing reports. A missing compiler input
must be identified and retained, not silently substituted with a newer version.

## Application and dynamically linked media

The supported replacement route is a new private package image. No installation
authorization token or vendor signing secret is required by these build scripts.
That observation does not decide whether GPLv3 Installation Information applies
to a particular distribution/device arrangement.

For Kotlin/resources/JNI changes with unchanged media, use the verified private
runtime and a **new** output directory. These commands build; do not run them as
part of a source-only inspection:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/build.py --format deb --channel nightly \
  --runtime /absolute/path/to/verified/runtime \
  --work-dir build/modified-linux-packaging --jobs 4
```

The canonical path compiles the application, runs selected headless tests,
rebuilds `player_bridge` against `/media`, stages an application-private image and
verifies the DEB. It does not install the DEB. For a development bridge alone,
the existing `:composeApp:buildLinuxPlayerBridge` Gradle task accepts
`-Pnuvio.linux.mpvRoot=/absolute/path/to/runtime` (or `MPV_ROOT`) and builds
`composeApp/build/native/linux/libplayer_bridge.so`. This is development output,
not an independently verified release package.

For modified FFmpeg/mpv/libplacebo or another private media component:

- Keep the exact upstream archive and add the actual patch to the corresponding
  manifest patch list, including its checksum and any required before/after
  hashes. Retain the patch and build instructions in the offered source set.
- Change the recipe/manifest deliberately; never edit a validated runtime in
  place, forge `validated.json` or reuse its old inventory after replacement.
- Run the existing builder in a new work directory, with the verified download
  cache available. Every attempt extracts and compiles fresh sources. A failure
  leaves the old validated runtime available.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  tools/linux/media-runtime/build.sh \
  --work-dir build/modified-media --jobs 4

env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/build.py --format deb --channel nightly \
  --runtime "$(realpath build/modified-media/runtime)" \
  --work-dir build/modified-linux-packaging --jobs 4
```

Private media DSOs keep the documented SONAME/ABI contract and `$ORIGIN`-relative
linkage. Packaging installs them under the application's `lib/native`, rather
than replacing host libraries. Changing an ABI requires the corresponding JNI
consumer change/rebuild; copying a different SONAME into an old package is not a
verified replacement. glslang is statically incorporated into libplacebo and
must be rebuilt with that library. Source/build material for other static
combinations must be resolved separately; dynamic replacement is not a universal
relink solution.

For a modified TorrServer (GPL-3.0, the Linux P2P engine): it is a separate program that
Nuvio starts from `lib/resources/torrserver/linux-amd64/TorrServer`, so a modified build of
the same name can take that file's place in an installed copy (Flatpak and AppImage need a
rebuilt package, as above). `NuvioDesktop-Linux-<version>-source-torrserver.tar.gz` carries
the pinned tag archive, the vendored Go modules and `BUILD.txt` with the exact Go version
and build command. To build it into the packages, change `torrserver/manifest.json` (new
archive URL and SHA-256, or a patched copy) and run the packaging build as above;
`torrserver/build.py` rebuilds and self-tests it every time. Nuvio starts any executable it
finds there; a replacement must keep TorrServer's HTTP API on `--port`/`--ip`/`--path`.

For a modified InnerTubeX (GPL-3.0, the second stream resolver for music):
`NuvioDesktop-Linux-<version>-source-innertubex.tar.gz` is the tag archive of the JAR compiled
into the executable. Point the dependency in `composeApp/build.gradle.kts` at your build, update
`tools/linux/packaging/innertubex_source.py` to match, and run the packaging build as above.

The canonical runtime audit, CPU fixtures and package verification remain
mandatory. Their pass is not acceptance of real playback, GPU, audio hardware,
HDR, or a modified sandbox. Preserve production security flags.

## The three format-specific steps

All formats consume the same canonical application tree. Preserve its exact
source identity and complete one format's gate before calling it verified.

| Format | Repack and installation boundary |
| --- | --- |
| DEB | The `--format deb` command above creates a DEB directly. Inspect/verify its control and payload. Test installation only in a disposable Debian-family VM; the maintainer's installed application is not a target. |
| Flatpak | Prepare a disposable Flatpak user installation containing the **exact** GNOME runtime commit from `config.json`, as in `linux-packages.yml`. Keep its HOME/XDG/`FLATPAK_USER_DIR` separate. Then run `build.py --format flatpak --work-dir build/modified-linux-packaging`; it validates the canonical source identity and runtime commit, exports a new bundle, and does not install it. Install/run the result only in that disposable OS account/VM. |
| AppImage | Import the engine through `webkit/import_candidate.py` using its externally trusted receipt SHA256, then pass the verified generation to `build.py --format appimage --work-dir build/modified-linux-packaging --webkit-generation /absolute/path/to/generation`. The gate checks recipe and immutable builder compatibility. Use the isolated AppImage procedure in [the acceptance protocol](../linux-acceptance-protocol.md); no installation or personal profile is required. |

An engine from different source patches or a different required builder cannot
be relabelled for reuse. A changed media/builder identity may invalidate engine
compatibility even if WebKit sources are unchanged. In that case a new verified
engine is required; do not bypass the contract or disable Bubblewrap. AppImage's
runtime is a separate statically linked executable: its unknown original Alpine
input revisions are still a blocker for a complete verified runtime rebuild and
any applicable relink material. No arbitrary replacement is endorsed here.

Packages built since the native-image change contain no JVM or JAR: the
application, its Maven JARs and the JDK class library are compiled into `lib/nuvio`
by GraalVM native-image. To rebuild or relink it with a modified application or
library, start from the matching `Nuvio-native-image-inputs-*.tar` (the exact input
JARs and `inputs.json`: path-free native-image arguments, metadata file hashes and
the GraalVM release), replace the JAR, and rerun
`./gradlew :composeApp:createLinuxNativeDistributable -Pnuvio.graalvm.home=...` (or
`build.py --format deb`, which also runs the native smoke gate) as described in
`composeApp/native-image-linux/README.md`. A modified GraalVM is built from the
pinned graal/labs-openjdk/mx sources with mx; this project has not tested that.
For native JAR dependencies, the retained upstream build recipes provide starting
inputs, but the complete JNI build graph has not been validated. Record those as
incomplete procedures until tested. A sources JAR containing precompiled native
objects is not sufficient by itself.

## Recipient delivery and remaining hard blockers

A future binary must link to the immutable source/material map for **its own**
bytes. Keep the application snapshot, patches, licenses, required native/static
materials and these instructions available together, under the approved channel
and retention policy in the source-delivery plan. Do not use an expiring cache or
an authenticated Actions artifact as an already complete public source offer.

The shader notice now distinguishes fourteen MIT shaders from the two Unlicense
downscalers, preserving all original GLSL bytes/notices. Full ODbL/DbCL texts and
the existing anime mapping attribution are bundled in `anime-mapping/`; their
publisher URLs/checksums and exact dataset byte identity are in `PROVENANCE.json`.
The publisher's ODbL text includes trailing spaces. A `.gitattributes` rule
limited to that one file preserves its exact bytes without treating those
publisher spaces as patch errors; other whitespace checks remain enabled.
The artwork/data follow-up pins the unchanged dump to its exact Fribb import.
This closes the missing-full-text/import-identity gaps, **not** the original
generation-record and merged-data rights gaps. These additions ship in future builds only; they do
not change historical CI artifacts.

Remaining blockers requiring specific evidence or a separate decision include:

- The seven SVG Repo UI imports have original MIT replacements, documented in
  `artwork-data-closure.md`. Remaining provider/award/quality marks, setup artwork
  and the IMDb permission claim still need applicable rights evidence. No
  historical grant was inferred and approved branding remains unchanged.
- Resolve the anime mapping's original generation input/derivation record and the
  model preferred-form/rights questions to the applicable reviewed standard.
- Complete required prebuilt JNI/static-library source/build/relink closure;
  AppImage-runtime's unpinned Alpine build inputs remain specifically unresolved.
- Approve a durable recipient channel and actually verify recipient access,
  including obligations to existing artifact recipients and any conveyed OCIs.
- Perform modified-build/install acceptance in disposable environments. The
  commands above were source-inspected; no rebuild or real installation is
  claimed by adding this document.

Until those applicable gates are resolved, DEB, Flatpak and AppImage remain
**BLOCKED for public redistribution**, independently of playback functionality.
