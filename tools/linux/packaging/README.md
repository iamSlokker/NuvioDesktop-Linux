# Linux x86_64 packages

This pipeline reuses Compose Desktop `createDistributable`/jpackage and the
canonical [private media recipe](../media-runtime/README.md). DEB and AppImage
are independent wrappers of that application image; Flatpak consumes the same
source-built image, never either final native package. Windows/macOS distribution
settings and player behavior are unchanged.

## Build without a desktop

Requirements: rootless Podman, Python 3.10+, Bash, curl, jq, GNU coreutils,
readelf, Flatpak and OSTree for its export/inspection. Native package tools and library
checks run inside the pinned builder. No script installs host packages. The native builder
installs its own prerequisites in a disposable Ubuntu container. No Homebrew
tools, libraries or paths are inputs.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  JAVA_HOME=/path/to/temurin-17 PATH=/usr/bin:/bin \
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
cache, Gradle initialization scripts and local.properties are not copied;
the disposable snapshot receives an empty local.properties required by upstream.
Consequently the generated official backend URL/publishable key and optional
service defaults are empty. These development packages do not certify official
account/service operation. A reviewed public release configuration (or explicit
self-hosted distribution policy) is needed before publishing stable builds;
never recover those values by copying a developer's private configuration.
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
source-content digest, dirty marker, JDK provenance and builder image ID.
Local dirty/nightly builds explicitly identify themselves as development.
Stable builds require a clean tree; tag-triggered CI requires `v<VERSION_NAME>`.
Desktop/icon metadata is shared; the existing icon is resized offscreen to 512px.

The media builder uses the existing digest-pinned Ubuntu 22.04/signed snapshot
and GLIBC 2.35 / GLIBCXX 3.4.30 floor. Packaging extends that builder with
GTK3/WebKitGTK4.1, verifies pinned Temurin 17.0.20.1+1 and CMake 3.31.6 inputs,
and records complete package versions. AppImage tool **1.9.1** and type-2 runtime
**20251108** are independently SHA-256 pinned; `--runtime-file` prevents a
mutable runtime download. GNOME Platform **49** is pinned to the commit in
`config.json`; it supplies GTK3/WebKitGTK4.1 and graphics/audio interfaces.

Fixed inputs and stamps make builds traceable/rebuildable. Whole-package
byte-for-byte reproducibility has **not** been established: jpackage/JAR archive
timestamps, package compression and export tools require independent repeat
build comparison. Do not present media-runtime reproducibility as proof of
whole-application reproducibility. GitHub runner orchestration tools are
recorded separately; application/native build inputs live in the pinned builder.

## Runtime and dependency boundary

Canonical layout:

```text
bin/nuvio                  original jpackage launcher
bin/nuvio-desktop          portable environment wrapper
lib/app/                   application/dependency jars, build stamp
lib/runtime/               bundled Java 17 runtime, including JAWT
lib/native/                player_bridge, mpv and private media ELF closure
share/                     desktop/icon metadata, media provenance and licenses
release-info.json          one revision/version identity for all formats
```

Linux JNI lookup is relative to bundled `java.home`, never the working directory.
The packaging-only CMake switch sets `$ORIGIN:$ORIGIN/../runtime/lib`; private
media libraries retain relative RUNPATH. The wrapper selects only image-private
libraries plus bundled Java, clears inherited LD_PRELOAD/LD_LIBRARY_PATH and
sets `GDK_BACKEND=x11`. The native single-X11-lifetime guard is untouched.
Flatpak's outer launcher instead retains its sandbox-provided graphics/codec
extension LD_LIBRARY_PATH after the private directories; clearing those paths
would hide GNOME's host-driver extensions. It still clears LD_PRELOAD.

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
are required by current JAWT/Canvas/GTK overlay architecture; no native Wayland
claim. Network supports streaming/APIs, PulseAudio supports audio, and `dri`
provides normal GPU interfaces. Portals are available through Flatpak's standard
filtered bus; only the existing MPRIS name ownership and ScreenSaver interface
are additionally requested. No home/host filesystem, session/system-bus socket,
Flatpak host-spawn, broad D-Bus name or all-device grant is requested. X11 access
itself carries cross-client limitations; this is not a strong desktop-isolation
claim. Default Flatpak XDG config/data/cache directories persist normally.

| Integration | Sandbox boundary |
| --- | --- |
| Discord IPC | Host/other-app sockets are not exposed; absence is nonfatal. Presence is not promised without separately reviewed socket access. |
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

DEB static verification/smoke installation, Flatpak preparation/export and
AppImage engine/assembly report separate outcomes. The mandatory all-three-format
gate still fails if any format or required verification fails. `--format all`
also continues independent exports and returns failure if any format fails.
The user confirmed DEB build/audit/smoke-install on Ubuntu 22.04 in Actions run
**38137016659**, and Flatpak real playback on Bazzite. The new custom-engine CI
path has not run on GitHub; its cold-build time, disk and memory fit must be
verified there. No release is automatically published.
The measured local full WebKit compile/link pass took 3h09m42s and peaked at
13.24 GiB aggregate container memory. A subsequent link-hardening correction
rebuilt in 3m56s through the compiler cache. These measurements do not establish
that the complete cold pipeline fits a standard GitHub runner.

### Public service configuration release blocker

`GenerateRuntimeConfigsTask` in `composeApp/build.gradle.kts` reads the following
six values from local.properties. All are empty in the isolated packaging build;
no environment/CI injection for approved release defaults exists yet.

| Build property | Missing behavior / release decision |
| --- | --- |
| `SUPABASE_URL` | Official backend endpoint for account/authentication and server-backed data. An operator-approved public URL or an explicit self-hosted distribution policy is required. |
| `SUPABASE_ANON_KEY` | Official backend public client key. Supply only an approved publishable/legacy anon key compatible with the current client/backend, never a secret or service-role key. Backend access controls must be reviewed separately. |
| `INTRODB_API_URL` | Optional skip-intro service endpoint; `SkipIntroRepository.isAvailable` is false when blank. |
| `IMDB_RATINGS_API_BASE_URL` | Optional season-ratings endpoint; this service returns no ratings when blank. The separate IMDb GraphQL/TMDB fallback paths still exist. |
| `IMDB_TAPFRAME_API_BASE_URL` | Optional alternate season-ratings endpoint; likewise unavailable when blank. |
| `PREMIUMIZE_CLIENT_ID` | OAuth application identifier; Premiumize sign-in explicitly rejects an empty value. Registration/redistribution approval is needed before supplying an identifier. |

Service URLs without embedded credentials and OAuth client IDs are public
configuration, subject to their operator's distribution policy. Supabase
[publishable/anon keys are client-facing; secret/service-role keys bypass access controls](https://supabase.com/docs/guides/getting-started/api-keys)
and must never be shipped. No actual release values have been supplied or
inferred from a developer machine.

Desktop custom-server configuration exists, but it does not establish a working
fresh-install experience with these empty defaults. `AuthRepository.initialize`
starts the Supabase session collector even on initial startup; default
account/session initialization and backend services are therefore unaccepted
release blockers, not merely optional untested integrations. Anonymous local
sign-in exists; it is not evidence that fresh packaged startup is accepted.
Settle the approved public defaults or self-hosted onboarding policy and test
fresh startup before publication. This task does not add an injection mechanism.

Generated `TraktConfig` credentials are always blank and are not read by current
Trakt consumers: `TraktSettingsRepository` uses user-configured credentials.
TMDB likewise uses user settings, not a generated bundled key. Those settings,
OAuth secrets, user API tokens and account data must not be copied into packages.

Nuvio is GPLv3; this mpv build is GPL, FFmpeg is LGPLv3 (OpenSSL configuration),
and private components/JDK/GTK/WebKit carry their own notices and source duties.
The image carries media/JDK/platform notices and application license. The source
artifact contains exact application inputs and media recipes/patches, **not a
complete third-party corresponding-source release**. Before publication retain
and provide the verified media archives, JDK corresponding sources, required
GTK/WebKit/platform sources, dependency notices and any required build/linking
materials. Hashes, URLs and notices alone do not fulfill source obligations.
The embedded AppImage type-2 runtime has its own licensing/source duties too;
its pinned upstream source must accompany any future redistribution as required.
See [FFmpeg compliance](https://ffmpeg.org/legal.html),
[mpv copyright](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright),
[Adoptium licensing](https://adoptium.net/about/licensing/), and
[Flatpak permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html).
This is a redistribution inventory, not legal certification. Frozen WebKit/media
snapshots require deliberate security refreshes, not indefinite deployment.

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
