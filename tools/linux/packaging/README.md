# Linux x86_64 packages

This pipeline reuses Compose Desktop `createDistributable`, compiles its JARs with
GraalVM native-image (the Linux tasks in `composeApp/native-image-linux.gradle.kts`)
and adds the canonical [private media recipe](../media-runtime/README.md). Packages
ship the native executable only: no application JAR and no Java runtime. DEB and
AppImage are independent wrappers of that application image; Flatpak consumes the
same source-built image, never either final native package. No package is built
unless the [native smoke gate](#native-image-and-smoke-gate) passes. Windows/macOS
distribution settings and player behavior are unchanged.

Current development and release gates are in the
[final readiness report](../../../docs/FINAL-READINESS-REPORT.md). The latest
inspected three-format run is 38397205286 at `fce98dc1633b51f813c20965e05941c2745beb8c`;
it is not a package of the later `linux`/compliance commits. Owner acceptance of
the final login SVG in AppImage/Flatpak, account switching and selected playback
does not establish full desktop QA. Historical observations below retain their
original scope. Source/rights/recipient-availability blockers apply before any
new binary-producing workflow or publication is authorized.

## Build without a desktop

Requirements: rootless Podman, Python 3.10+, Bash, curl, jq, GNU coreutils,
readelf, Flatpak and OSTree for its export/inspection. Native package tools and library
checks run inside the pinned builder. No script installs host packages. The native builder
installs its own prerequisites in a disposable Ubuntu container. No Homebrew
tools, libraries or paths are inputs.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  JAVA_HOME=/path/to/temurin-21 PATH=/usr/bin:/bin \
  python3 tools/linux/packaging/build.py --format all --jobs 2
```

The default builds the pinned media runtime first if absent. For a previously
audited generation, pass `--runtime /path/to/runtime`; its manifest, file
inventory, validation marker and ELF audit must pass. CI always rebuilds media
from verified source. The media builder image ID recorded in that artifact is
the packaging container's base, so it must still exist in the local Podman store.
If missing, rebuild media; never substitute an arbitrary host image.

`--format deb` builds the canonical application image and DEB. `--format native`
also builds AppImage. `--format flatpak` exports an already completed canonical
image. `--format webkit` builds/resumes only the pinned AppImage engine;
`--format appimage` reuses the canonical application/media and builds that engine
if necessary. Neither AppImage-only command rebuilds media or modifies an existing
DEB/Flatpak artifact. An optional `--webkit-generation PATH` must pass the engine
recipe, provenance, policy and file-inventory checks before assembly. `--gradle-cache DIR --offline` optionally
seeds verified Gradle module/wrapper inputs. Project build output, configuration
cache, Gradle initialization scripts and local.properties are not copied.
The disposable snapshot supplies the reviewed
[public client properties](public-client.properties) to upstream's existing
`GenerateRuntimeConfigsTask`: `https://api.nuvio.tv` and the official publishable
key documented in the pinned Nuvio self-host example. DEB, Flatpak and AppImage
verification reads the generated `SupabaseConfig` fields from the exact native-image
input JARs and requires both values verbatim in the shipped `lib/nuvio`; missing,
empty or different defaults fail. Runtime custom/self-hosted
server selection remains unchanged. No developer properties or private/service-role
credentials are inputs. Account/login acceptance against the service is separate;
optional service defaults described below remain unavailable.
Gradle build-cache reuse is disabled. A git-file source snapshot prevents
accidentally packaging the developer checkout, personal data or native caches.

Output is ignored `build/linux-packaging/`: `canonical/`, `Nuvio.AppDir/`,
`flatpak-build/`, `artifacts/`, dependency reports and `SHA256SUMS`.
The build lock prevents concurrent replacement. A failed format never becomes a
placeholder; missing/empty/malformed formats fail final verification.

## Identity and reproducibility boundary

Application ID: `com.nuvio.media.desktop` (existing macOS bundle identity).
Display name: **Nuvio Desktop**. DEB name/portable command: `nuvio-desktop`.
`composeApp/Configuration/DesktopVersion.properties` supplies version and code;
there is no separate Linux release version. `release-info.json` and the existing
`lib/app/build-info.properties` carry the full Git commit, channel, source epoch,
source-content digest, dirty marker, GraalVM release (`graalvm_release`), build JDK
(`build_jdk_release`) and builder image ID.
Local dirty/nightly builds explicitly identify themselves as development.
Stable builds require a clean tree; tag-triggered CI requires `v<VERSION_NAME>`.
Desktop/icon metadata is shared; the existing icon is resized offscreen to 512px.

The media builder uses the existing digest-pinned Ubuntu 22.04/signed snapshot
and GLIBC 2.35 / GLIBCXX 3.4.30 floor. Packaging extends that builder with
GTK3/WebKitGTK4.1 (plus Xvfb and Mesa lavapipe for the smoke gate), verifies pinned
Temurin 21.0.12.1+1 (build JDK: Gradle, Kotlin, jpackage), GraalVM CE **25.0.2**
(`/opt/graalvm`, native-image) and CMake 3.31.6 inputs, and records complete package
versions. Neither JDK is copied into a package. AppImage tool **1.9.1** is SHA-256 pinned. The type-2
runtime is not downloaded: `appimage_runtime.py build --output DIR` compiles it
from pinned sources (`appimage-runtime/manifest.json`, digest-pinned Alpine,
`--network=none`), reproducibly, and `--runtime-file` embeds only a runtime equal
to the manifest's `runtime_sha256`. `webkit/package.py` builds it (or reuses a
verified `NUVIO_APPIMAGE_RUNTIME_DIR`) into `<work>/appimage-runtime/` before
assembly; its notices go to `usr/share/licenses/appimage-runtime/` in the
AppDir and `Nuvio-AppImage-runtime-source-<sha12>.tar.gz` (sources, recipe,
relink kit, `RELINK.md`) to `artifacts/`. The `appimage-runtime` entry still in
`config.json` is unused (that file is part of the WebKit builder-seed identity). GNOME Platform **49** is pinned to the commit in
`config.json`; it supplies GTK3/WebKitGTK4.1 and graphics/audio interfaces.

Fixed inputs and stamps make builds traceable/rebuildable. Whole-package
byte-for-byte reproducibility has **not** been established: JAR archive
timestamps, native-image output, package compression and export tools require
independent repeat build comparison. Do not present media-runtime reproducibility as proof of
whole-application reproducibility. GitHub runner orchestration tools are
recorded separately; application/native build inputs live in the pinned builder.

## Native image and smoke gate

`build.py`/`canonical_from_seed.py` run the builder twice (`native-build.sh`):

1. **build** (network for Gradle dependencies): `createDistributable` and the
   focused JVM tests, then a second Gradle run of `createLinuxNativeDistributable`
   (`-Pnuvio.graalvm.home=/opt/graalvm`, never `-Pnuvio.native.qa`) so native-image
   runs in the Ubuntu 22.04 builder (glibc floor 2.34 for the executable) with
   `NUVIO_NATIVE_BUILDER_HEAP` (default `9g`; ~9-11 GB peak RSS, one build on the
   16 GB runner). The JNI bridge and HDR helper are built from this source, then
   `stage.py` lays out the canonical image from the native layout and
   `native_evidence.py` retains the evidence in `<work>/native-image/`: the exact
   classpath JARs, `inputs.json` (path-free arguments, metadata file hashes, JAR
   SHA-256s, GraalVM release and archive hash, executable and JDK-library hashes,
   GraalVM notice hashes; also shipped as `share/nuvio-native-image/inputs.json`) and
   native-image's own `build-artifacts.json`/`embedded-resources.json`.
2. **package** (`--network=none`): `native_smoke.py` launches the staged image's
   `bin/nuvio-desktop` three times through stage 1's
   `composeApp/scripts/linux-native-smoke.py` (private Xvfb, private D-Bus, empty
   profile, Mesa lavapipe named through the image's own Vulkan loader). Any missing
   first frame, failed self-test probe, early exit or missing-registration/fatal
   console line fails the run and no DEB is built; a pass writes
   `<work>/native-image/smoke.json` (executable SHA-256 and a digest of every staged
   file, unchanged by the run). Then the DEB and its verification.

`formats.py` publishes the evidence as `Nuvio-native-image-inputs-<commit12>.tar`
(deterministic tar: JARs, manifest, reports, smoke record and console logs).

How each contract verifies a package without JARs:

| Contract | Input JARs (retained, hash-bound) | Shipped bytes |
| --- | --- | --- |
| Inputs | — | `inputs.json` equals the retained one; `lib/nuvio` and every JDK file match it; no `.jar`, `lib/runtime/lib/server`, `libjli.so`, `libapplauncher.so` or QA marker anywhere |
| Backend (`client_config`) | exactly one `SupabaseConfig` with the reviewed URL/key fields | URL and key occur in `lib/nuvio` |
| ONNX/artwork/data | existing contracts (hashes, ABI/ldd of ONNX natives; the bundled anime dataset must match the checkout) | each reviewed payload is listed by `embedded-resources.json` and occurs verbatim in `lib/nuvio` (native-image stores resources uncompressed); no embedded resource has a removed name; anime-mapping notices ship as files in `share/licenses/anime-mapping/` |
| Embedded JNI | quickjs etc. from the input JARs, if embedded | bytes in `lib/nuvio`; ABI floor and `ldd` |
| Report | every embedded JAR resource comes from a recorded input JAR entry of the same size (JDK module resources counted separately) | — |
| Notices | every notice-like entry of every input JAR | `share/licenses/jar-notices/` equals that extraction; GraalVM notices equal `inputs.json` |
| Smoke | — | `smoke.json` is a pass for this exact `lib/nuvio`; the DEB payload is byte-identical to the image the smoke launched |

The contracts check identity and presence, not rights. Flatpak verification rebuilds
the inspected subset (executable, manifest, JDK files, notices) from the exported
OSTree bytes and runs the same checks; AppImage verifies its extracted payload.

## Runtime and dependency boundary

**Experimental HDR helper.** The canonical native build also compiles
`lib/native/nuvio-hdr-probe` (required when `NUVIO_PACKAGE_BUILD=ON`) from the
media runtime's build-only SDK area (pinned wayland-scanner, color-management-v1
XML, Vulkan C headers; never staged) and runs `linux_hdr_session_test
--expect-wayland=yes`. `verify.check_image` requires the helper with the bridge's
RUNPATH and bundled libwayland-client/libvulkan resolution; Flatpak checks its
closure against GNOME Platform and its presence in the exported bundle. Details,
gates and the default-off toggle are in the
[HDR design](../../../docs/linux-hdr10-design.md#packaging-implemented-ci-rebuild-pending);
HDR output is unverified on HDR hardware.

Canonical layout:

```text
bin/nuvio                  script: exec ../lib/nuvio
bin/nuvio-desktop          portable environment wrapper (execs lib/nuvio)
lib/nuvio                  GraalVM native executable (application, Maven JARs, JDK class library)
lib/lib*.so                JDK libraries native-image emitted (libawt, libfontmanager, ...; libjvm.so is its stub)
lib/app/                   nuvio.cfg (-D options applied at run time), skiko, build stamp; no JAR
lib/runtime/lib/           java.home: libjawt.so and font properties from GraalVM
lib/native/                player_bridge, nuvio-hdr-probe, mpv and private media ELF closure
share/nuvio-native-image/  inputs.json: exact native-image inputs (JAR names/SHA-256, arguments, GraalVM)
share/licenses/            GraalVM/, jar-notices/ (the input JARs' notice files), anime-mapping/, media, ...
share/                     desktop/icon metadata, media provenance
release-info.json          one revision/version identity for all formats
```

Linux JNI lookup is relative to `java.home` (`lib/runtime`, set by
`NativeImageSupport`), never the working directory. The packaging-only CMake switch
sets `$ORIGIN:$ORIGIN/../runtime/lib`; private media libraries retain relative
RUNPATH. The wrapper selects only image-private libraries (`lib/native`,
`lib/runtime/lib`; the emitted JDK libraries load from the executable's directory),
clears inherited LD_PRELOAD/LD_LIBRARY_PATH and sets `GDK_BACKEND=x11`. The native single-X11-lifetime guard is untouched.
Flatpak's outer launcher instead retains its sandbox-provided graphics/codec
extension LD_LIBRARY_PATH after the private directories; clearing those paths
would hide GNOME's host-driver extensions. It still clears LD_PRELOAD.

`nuvio://` links (Watch Party invites, "Open in Nuvio") open the app on Linux through
the desktop entry: `MimeType=x-scheme-handler/nuvio;` with `Exec=nuvio-desktop %u`.
Every launcher (`bin/nuvio-desktop`, the DEB `/usr/bin` wrapper, AppRun, the Flatpak
launcher, whose Exec `flatpak build-export` rewrites to `... @@u %u @@`) forwards the
link to `lib/nuvio`; a running copy receives it through `DesktopSingleInstance`.
`verify.py` checks the shipped and the exported Flatpak entries. Windows keeps its
own per-user registry registration (`DesktopUrlScheme`).

Media remains mpv **0.41.0**, FFmpeg **9.0.2**, libplacebo **7.360.1**, with the
existing safe-auto and CUDA-mapper patches. The image contains versioned runtime
libraries/SONAME links, not headers, pkg-config files, static archives or media
build tools. The original runtime inventory is provenance for the source-built
input; package audits describe the actual reduced distribution layout.

No proprietary NVIDIA libraries, ICDs, Mesa drivers, glibc or system dynamic
linker are bundled. Optional CUDA/NVDEC interfaces remain dynamically discovered;
no mandatory NVIDIA DT_NEEDED is permitted. Software decoding remains available
as before; displaying the existing Vulkan/x11vk renderer still requires a usable
host graphics stack. Intel/AMD/NVIDIA/hybrid hardware acceptance is separate.

DEB installs privately under `/opt/nuvio-desktop`, with desktop/icon entries
and a `/usr/bin/nuvio-desktop` wrapper. It declares GTK/WebKit, X11, audio/font,
VAAPI/DRM and platform ABI dependencies; no system JDK or libmpv is required.
There are no maintainer scripts or global library registrations. Uninstall
removes package files and intentionally retains user XDG data.

AppImage uses the dedicated [production engine stage](webkit/README.md), not the
builder's non-relocatable system WebKit. WebKitGTK **2.50.4**, its JavaScriptCore,
Web/Network/GPU processes and injected bundle are built together with developer
mode OFF and Bubblewrap ON. A pinned source patch resolves private paths from
the loaded library/executable and mounts the private image read-only in child
namespaces, including paths with spaces and mounts beneath `/tmp`.

The AppImage-only GTK source build retains Ubuntu's 3.24.33-1ubuntu2.2 patches,
relocates GTK resource accessors, and removes upstream SVG editor home-path
metadata before compiling resources. No binary strings are patched. Private
Bubblewrap, xdg-dbus-proxy, GIO/TLS, pixbuf and GStreamer modules, schemas, MIME,
icons and notices are assembled in the recorded engine builder. The wrapper
regenerates absolute module caches for each mounted location. Host graphics/ABI
libraries remain outside the package, as before.

Missing private helpers and sandbox-disable requests fail closed. Seccomp rules,
namespace flags and D-Bus filters remain upstream policy. WebProcess is sandboxed;
upstream NetworkProcess is trusted, and GTK GPUProcess does not request the
WebProcess sandbox. This does not claim all subprocesses are equally isolated.
The old marker/string guard is replaced by verified engine provenance, payload
hashes/modes, helper/resource requirements, ELF/ABI/dependency and path checks.
Compilation alone is not runtime or sandbox acceptance.

[WEBKIT-RELOCATION.md](WEBKIT-RELOCATION.md) records the exact root cause,
implementation and acceptance boundary. The previous UID-map failure occurred
inside Podman; the user subsequently verified Bubblewrap on the actual Bazzite
host. Build containers are not used as evidence of WebProcess sandbox acceptance.

Local AppImage acceptance now includes an actual 258,505,208-byte artifact,
extraction/static inspection (4,225 entries, 410 ELF records), verified checksums
and two production-overlay runs on a private Xvfb display. The runs exercised
private Web/Network processes and the WebProcess seccomp/namespaces/read-only
mounts at unrelated paths containing spaces, including a symlinked launcher.
GTK3's production sandbox opt-in is set by the AppImage launcher; the engine
rejects unsandboxed WebProcess launches. See the [exact evidence and limits](WEBKIT-RELOCATION.md#local-acceptance-2026-10-11).
DEB/Flatpak artifact hashes are unchanged. This reuses their preserved dirty
canonical application identity, not a freshly stamped application build from
HEAD. Nuvio itself and FUSE-mounted playback have not been launched/tested here.
DEB continues using distribution WebKit and Flatpak continues using GNOME WebKit.

Flatpak uses GNOME's GTK/WebKit, Vulkan loader and driver extensions; it removes
the duplicate private Vulkan loader. Older-baseline application/private media
ELF objects are checked against that exact runtime in a headless build sandbox.
No native player, GTK initialization or audio device is used by this check.
If future ABI/SDK changes invalidate that reuse, rebuild media/bridge against
the SDK using the same manifest/patch policy instead of widening permissions.

## Flatpak permissions and deliberate limits

`flatpak-permissions.json` is the explicit sandbox policy. Native Flatpak
`build-init`/`build-finish` generate the standard application `metadata` manifest,
also uploaded as `flatpak-metadata.ini`; `flatpak-provenance.json` records the
pinned runtime/source inputs. X11/XWayland and IPC
are required by current JAWT/Canvas/GTK overlay architecture (`GDK_BACKEND=x11`).
`--socket=wayland` is granted in addition, only for the default-off experimental
HDR session (its capability probe and mpv's own Wayland window); it is not a
native-Wayland embedding claim. Network supports streaming/APIs, PulseAudio supports audio, and `dri`
provides normal GPU interfaces. Portals are available through Flatpak's standard
filtered bus; only the existing MPRIS name ownership and ScreenSaver interface
are additionally requested. For Discord Rich Presence (off by default), only
`xdg-run/discord-ipc-0` and `xdg-run/app/com.discordapp.Discord` are exposed. No home/host filesystem, session/system-bus socket,
Flatpak host-spawn, broad D-Bus name or all-device grant is requested. X11 access
itself carries cross-client limitations; this is not a strong desktop-isolation
claim. Default Flatpak XDG config/data/cache directories persist normally.

| Integration | Sandbox boundary |
| --- | --- |
| Discord IPC | Only the host `discord-ipc-0` socket and Flatpak Discord's runtime directory are exposed; absence is nonfatal. A host Discord started after Nuvio is only seen after Nuvio restarts (the single socket is bind-mounted at launch); Flatpak Discord is seen at any time. Other clients are covered only if they publish `discord-ipc-0`. |
| Game import/launch | Host game folders and launchers are not exposed; no Steam/Proton/host-spawn implementation. Sandbox-visible executables only. |
| External players | Host PATH/programs and `flatpak run` delegation are unavailable; portal/system opening is conditional and needs acceptance. |
| Local media, downloads, backup, shaders | Swing pickers are not portal pickers. Only sandbox-visible paths work by default; no broad host access added. A separately designed portal integration/user-granted path is needed for arbitrary files. |
| MPRIS | Explicit `org.mpris.MediaPlayer2.Nuvio` ownership; desktop/player acceptance still required. |
| Notifications | Desktop OS notifications remain unimplemented; standard portal availability does not add them. |
| Inhibition/screensaver | Existing portal is preferred. ScreenSaver allowed; logind fallback is not granted and GNOME/Mutter-specific probes are not exposed. App-idle fallback and generic Wayland limitations remain. |
| Controllers | `dri` does not expose evdev input; no physical controller acceptance or permission bypass is claimed. |

For local Flatpak export, the pinned GNOME Platform must already be installed.
The build does not register remotes, install runtimes into user/system state, or
change overrides. CI prepares a separate `FLATPAK_USER_DIR` and pins its runtime
commit there; export/import verification uses disposable output repositories.

## Self-hosted Flatpak repository (owner-signed)

Flatpak updates come from a GPG-signed OSTree repository on GitHub Pages:
`https://iamslokker.github.io/NuvioDesktop-Linux/flatpak/repo/` (configuration:
[`flatpak-repo/repo.json`](flatpak-repo/repo.json)). Ref:
`app/com.nuvio.media.desktop/x86_64/stable` (nightly builds export branch
`nightly` and are never published). CI holds no signing secret; the owner signs locally.

**CI bundles stay unsigned and unlinked.** `flatpak.py` adds only
`--runtime-repo` (Flathub). A bundle that embeds `--gpg-keys` makes Flatpak
require a signature on the bundled commit ("GPG verification enabled, but no
signatures found"), so an unsigned CI bundle carrying the key cannot be installed;
a repo URL without a key would subscribe installs to unverified updates.
`verify.py` reads the bundle header (`flatpak_bundle.py`, no install) and
rejects a CI bundle with a repo URL, key or signature.

**Owner publish** (`flatpak_repo.py publish`, wrapped by
[`flatpak-repo/publish-release.sh`](flatpak-repo/publish-release.sh)) takes the
verified CI bundle and a local `gh-pages` checkout. It imports the exact CI
commit into `flatpak/repo` (archive-z2), runs `flatpak build-sign` and
`build-update-repo --gpg-sign --generate-static-deltas --prune --prune-depth=2`,
then writes `nuvio.flatpakrepo`, `com.nuvio.media.desktop.flatpakref` (both
carry the base64 key), the public key, `release.json`, `index.html`,
`.nojekyll` and a `.gitignore` for OSTree scratch directories. Last, it writes a
**signed rebundle** of the same commit with `--repo-url`, `--gpg-keys` and
`--runtime-repo`. Installing that bundle creates a GPG-verified origin remote
for the hosted repository. The release asset is this rebundle; `SOURCE-MAP.json`
keeps the CI hash (`ci_sha256`) and the shared OSTree commit.

It refuses: a missing/armored/secret/multi-key public key (fail closed until
`flatpak-repo/nuvio-flatpak.pub.gpg` is committed), a signing key other than
that key's full fingerprint, a non-`stable`/dirty/development build, release-info
or commit timestamp mismatches, an embedded repo URL/key/runtime repo that
differs, an already published commit, and anything not strictly newer by
`(version_code, source_date_epoch)` (Flatpak also rejects chronologically older
commits). It also refuses a file of 100 MiB or more (GitHub's push limit) and a
site over 1 GB (the Pages limit). Verification is offline: the published
`.flatpakrepo`/`.flatpakref`, with the URL rewritten to `file://`, are added to
a disposable `FLATPAK_USER_DIR`/`FLATPAK_SYSTEM_DIR`. It then checks
`remote-info` and a GPG-verified `install --no-deploy --no-deps`, and that the
signed bundle installs (`--no-deps`) and creates the expected verified origin.
The app is never run. `flatpak_repo.py verify` repeats these checks;
`inspect BUNDLE` prints a bundle header.

CI commits have no parent, so each publish makes the previous commit
unreachable: `--prune` removes it and its deltas. The repository serves only the
current release, plus a from-scratch delta. Updates still download only changed
objects, but rollback is not served. The release hook commits `gh-pages` as a
single orphan commit, so superseded objects do not accumulate in git history,
and prints (never runs) the `git push --force-with-lease` for when the release
goes public.

### Owner one-time setup (not run by any tool or test)

```sh
# 1. Dedicated signing key in your own keyring (passphrase prompt), public key
#    exported to the repo path, secret key + revocation certificate backed up.
tools/linux/packaging/flatpak-repo/create-signing-key.sh --backup-dir /path/to/offline/backup
git add tools/linux/packaging/flatpak-repo/nuvio-flatpak.pub.gpg   # commit via the normal branch flow

# 2. Empty gh-pages branch in a separate small checkout (no history download).
git init ~/nuvio-pages && cd ~/nuvio-pages
git remote add origin git@github.com:iamSlokker/NuvioDesktop-Linux.git
git switch --orphan gh-pages && git commit --allow-empty -m "Initialize GitHub Pages"
git push -u origin gh-pages

# 3. Enable Pages from gh-pages (use -X PUT if Pages already exists), then HTTPS.
gh api -X POST repos/iamSlokker/NuvioDesktop-Linux/pages -f 'source[branch]=gh-pages' -f 'source[path]=/'
gh api -X PUT repos/iamSlokker/NuvioDesktop-Linux/pages -F https_enforced=true
```

The key is RSA-4096, signing-only and **non-expiring**: clients keep the key from
their `.flatpakrepo`/bundle, so an expiring or replaced key breaks updates until
users re-add the remote. Keep the backup offline; never commit the secret export.

### Per release

After `prepare-assets.sh <run>` and before `gh release create` in `publish-draft.sh`:

```sh
tools/linux/packaging/flatpak-repo/publish-release.sh "$STAGE" ~/nuvio-pages <KEY-FINGERPRINT>
```

It requires the stage's bundle to match `SOURCE-MAP.json` and a clean `gh-pages`
checkout. It replaces the staged `.flatpak` with the signed rebundle (the
downloaded CI file is untouched) and regenerates both `SHA256SUMS`. When the
GitHub release is published, run the printed `git push --force-with-lease`. If a
later step fails before pushing, `git -C ~/nuvio-pages reset --hard origin/gh-pages`
restores the published state. Rerunning without that reset fails with
"already published".

User commands (also on the generated `index.html`):

```sh
flatpak install --user https://iamslokker.github.io/NuvioDesktop-Linux/flatpak/com.nuvio.media.desktop.flatpakref
# or
flatpak remote-add --user --if-not-exists nuvio https://iamslokker.github.io/NuvioDesktop-Linux/flatpak/nuvio.flatpakrepo
flatpak install --user nuvio com.nuvio.media.desktop
```

Earlier bundle installs (origin `desktop-origin`, no URL, GPG off) never update.
Migrate once, keeping `~/.var/app/com.nuvio.media.desktop`: add the remote as
above, then `flatpak install --user --reinstall nuvio com.nuvio.media.desktop//stable`.
Flatpak drops the unused `desktop-origin` remote. Installing a new signed bundle
also works after `flatpak uninstall --user com.nuvio.media.desktop`. Plain
`--reinstall --bundle` keeps the old URL-less remote and does not migrate. A
`nightly` bundle install is a separate branch; remove it with
`flatpak uninstall --user com.nuvio.media.desktop//nightly`.
Remote names: `nuvio` (`.flatpakref`/`.flatpakrepo`) or `desktop-origin`
(signed bundle, reused if a remote with the same URL already exists). Updaters
must use the installed ref's origin, not a fixed name.

## CI, licenses and acceptance

[linux-packages.yml](../../../.github/workflows/linux-packages.yml) runs on manual
dispatch/version tags, checks out one commit, rebuilds private media, builds
native packages and exports Flatpak, checks all three outputs and uploads
artifacts/checksums. Verified downloads, Gradle module artifacts and a bounded
WebKit ccache are cached; native/final package outputs are checked anew. Engine
cache reuse requires matching source/recipe/base-image identity, build policy,
file contents/modes and corresponding-source inventory. The same recipe supports
a cold build. Official actions are commit-pinned. Compiler inputs are saved even
after a failed format; cache availability never changes the release gate.

Manual `bootstrap_only=true` runs retain the media/canonical build and require
successful immutable WebKit builder-seed export/upload. They skip Flatpak,
AppImage and normal package release checks/uploads, and report **BOOTSTRAP ONLY**.
They are not validated releases. The option defaults to false and never applies
to tag pushes; the normal three-format release gate remains mandatory.

DEB static verification/smoke installation, Flatpak preparation/export and
AppImage engine/assembly report separate outcomes. The mandatory all-three-format
gate still fails if any format or required verification fails. `--format all`
also continues independent exports and returns failure if any format fails.
The user confirmed DEB build/audit/smoke-install on Ubuntu 22.04 in Actions run
**38137016659**, and Flatpak real playback on Bazzite. The custom-engine attempt
in run **38188755529** timed out after four hours. The
[dedicated manual WebKit workflow and verified builder bootstrap](webkit/README.md#independent-manual-ci-and-builder-bootstrap)
separate engine retries from application/media/DEB/Flatpak builds. Compiler
output streams live, resources are reported and only stopped-container cache
snapshots upload. Run 38280547370's final compiler statistics demonstrate 370
cache hits after restoring a previous attempt, despite its intentional timeout.
A successful standalone engine can be selected through the four verified-handoff
inputs documented in the WebKit README. The current application is built in that
engine's exact canonical seed image; completed engine import does not replace
any DEB/Flatpak/AppImage audit or allow a different builder identity. Full engine
and package acceptance remain distinct from successful cache transfer/reuse.
No release is automatically published.
The measured local full WebKit compile/link pass took 3h09m42s and peaked at
13.24 GiB aggregate container memory. A subsequent link-hardening correction
rebuilt in 3m56s through the compiler cache. These measurements do not establish
that the complete cold pipeline fits a standard GitHub runner.

### Public service configuration and remaining release blockers

`GenerateRuntimeConfigsTask` in `composeApp/build.gradle.kts` reads the following
six values from local.properties. Packaging provides only the two reviewed public
cloud values through its disposable build input; the four optional values remain
empty. The client URL/key are pinned in source rather than downloaded at build time.

| Build property | Missing behavior / release decision |
| --- | --- |
| `SUPABASE_URL` | `https://api.nuvio.tv`, the official Nuvio Cloud endpoint. |
| `SUPABASE_ANON_KEY` | Official `sb_publishable_…` public client key from the pinned Nuvio self-host example; never a secret/service-role key. Backend access controls remain the service's responsibility. |
| `INTRODB_API_URL` | Optional skip-intro service endpoint; `SkipIntroRepository.isAvailable` is false when blank. |
| `IMDB_RATINGS_API_BASE_URL` | Optional season-ratings endpoint; this service returns no ratings when blank. The separate IMDb GraphQL/TMDB fallback paths still exist. |
| `IMDB_TAPFRAME_API_BASE_URL` | Optional alternate season-ratings endpoint; likewise unavailable when blank. |
| `PREMIUMIZE_CLIENT_ID` | OAuth application identifier; Premiumize sign-in explicitly rejects an empty value. Registration/redistribution approval is needed before supplying an identifier. |

Service URLs without embedded credentials and OAuth client IDs are public
configuration, subject to their operator's distribution policy. Supabase
[publishable/anon keys are client-facing; secret/service-role keys bypass access controls](https://supabase.com/docs/guides/getting-started/api-keys)
and must never be shipped. The official values come from public upstream
documentation, never a developer machine.

Desktop custom-server configuration is preserved. `AuthRepository.initialize`
starts the Supabase session collector even on initial startup. The owner has
since accepted official login/account switching within the tested Bazzite flows;
correct constants or anonymous local sign-in alone would not establish that.
Broader account/session/provider acceptance remains separate. Optional service
configuration and redistribution approval remain explicit decisions.

Generated `TraktConfig` credentials are always blank and are not read by current
Trakt consumers: `TraktSettingsRepository` uses a user-configured public client ID,
falling back to the generated built-in ID if one is configured. This snapshot uses
device-code authentication; no client secret or localhost callback is required.
The reviewed public packaging defaults do not supply an optional Trakt client ID.
TMDB likewise uses user settings, not a generated bundled key. Those settings,
OAuth secrets, user API tokens and account data must not be copied into packages.

Nuvio is GPLv3; this mpv build is GPL, FFmpeg is LGPLv3 (OpenSSL configuration),
and private components/GraalVM (GPLv2 with Classpath Exception)/GTK/WebKit carry
their own notices and source duties. The image carries media/GraalVM/platform notices,
the input JARs' notice files and the application license. `compliance/release_sources.py`
adds the pinned GraalVM sources (graal, labs-openjdk, mx) and the native-image input
archive to every package's `SOURCE-MAP.json` entry. The source
artifact contains exact application inputs and media recipes/patches, **not a
complete third-party corresponding-source release**. Before publication retain
and provide the verified media archives, GraalVM corresponding sources, required
GTK/WebKit/platform sources, dependency notices and any required build/linking
materials. Hashes, URLs and notices alone do not fulfill source obligations.
The embedded AppImage type-2 runtime has its own licensing/source duties too;
its pinned upstream source must accompany any future redistribution as required.
See [FFmpeg compliance](https://ffmpeg.org/legal.html),
[mpv copyright](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright),
[Adoptium licensing](https://adoptium.net/about/licensing/), and
[Flatpak permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html).
The [corresponding-source audit](../../../docs/compliance/linux-redistribution-audit.md)
records actual package/JAR/native inventories, unresolved source/notice/font
rights evidence, and a BLOCKED assessment for each format. Its
[offline collection/inventory tools](compliance/README.md) do not publish artifacts
or replace legal review. This is a redistribution inventory, not legal certification. Frozen WebKit/media
snapshots require deliberate security refreshes, not indefinite deployment.

The Umbra snapshot also packages CPU ONNX Runtime Java 1.31.0 and the unchanged
U2NetP/YuNet hero-framing models. Full model/runtime licenses and pinned model
hashes are in `backdrop-framing/` inside the application JAR, embedded verbatim in
`lib/nuvio`; the ONNX dependency retains its third-party notices.
`onnx_contract.py` checks the Linux JNI/core bytes, model/license bytes, CPU
dependencies and ABI floor in the exact native-image input JARs, and verification
requires those bytes in the shipped executable (DEB, Flatpak readback, AppImage). See the
[snapshot integration notes](../../../docs/linux-umbra-snapshot-integration.md)
for the accepted engine provenance and remaining desktop acceptance.

User-reported DEB smoke installation and Bazzite Flatpak playback are accepted
within those tested boundaries. Broader DEB desktop/playback/uninstall, AppImage
executable launch/playback/library portability and the remaining device/session
integrations still require acceptance.
Never run these against the user's live desktop without explicit authorization.

## Retained initial local evidence

This is historical evidence for the original packaging scaffold, before the
subsequent CI and AppImage engine work described above. At `linux` HEAD `d261f848` plus this dirty packaging snapshot, with Temurin 17
and all three display/live-test variables unset:

- Production/test Kotlin compilation and Compose application-image build pass.
- 41 focused JVM tests pass, with zero failures/errors/skips.
- Fresh JNI build passes; native fixtures pass 6 separate-audio cases,
  29 HTTP recovery groups and 19 audio recovery groups.
- 12 headless packaging contract tests pass, including the sandbox driver-path
  preservation and release-WebKit relocation rejection.
- Canonical image: 595 files, 47 loose ELF objects plus 2 embedded Linux x86_64
  JNI objects; ABI, dependency, archive, path and desktop checks pass.
- DEB extraction and Flatpak sandbox/import/identity/metadata checks pass. Neither
  package is installed or graphically launched. SHA256SUMS covers actual outputs.
- AppImage emits no artifact and fails on the explicit WebKit relocation guard.
  The all-format command returns failure while retaining independent evidence.
- GitHub workflow execution, fresh-host/manual runtime acceptance and repeat
  builds remain pending. Host orchestration/export tool versions are recorded;
  they are not proof of a completely immutable export environment.

Generated artifacts/reports are under `build/linux-packaging/artifacts/` and
are intentionally ignored by Git. The source artifact includes the exact
snapshot inventory; this is development evidence, not a published release.
The retained local packages/source archive predate the subsequent CI reporting
and documentation audit; they were preserved without rebuilding or rewriting.
They do not validate those later edits or a clean committed build.
