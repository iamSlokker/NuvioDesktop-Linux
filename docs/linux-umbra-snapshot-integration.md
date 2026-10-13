# Umbra Linux snapshot integration

This integration imports the authorized WIP snapshot
`UmbraProjects/NuvioDesktop@18537e3c85d7f365487abb6f7e7edb8a4432a081`
(`wip/linux-snapshot`) with its history. It is not a production release.
This explicitly authorized pin follows the initial `6f155d26` snapshot integrated
in `e2812f68`. Later upstream commits are outside this integration's scope and
require a separate decision. The previous integration and its verified packages
remain available as a reference; their GUI acceptance is not assumed.
The Linux reference is the Bazzite-accepted cloud/player correction
`a5568593562ba18dac4b7d7efe294081801ae74e`, followed by the inherited workflow
guard `d9bfcdad8f5e99d2ef4345c435ae32434aecd096`. The tested correction remains
available on `fix/linux-cloud-player-2026-10-12` and the local rollback reference
`rollback/linux-bazzite-accepted-2026-10-13`.

## Scope and adaptations

The merge retains automatic subtitle synchronization, image prewarming/cache
partitioning, Discover/Library filters, Calendar changes, account-scoped settings
and import, cast/search improvements, mouse-operated OSK and intelligent hero
framing. Shared implementations remain the source of truth. The native Linux
player, controller backend, MPRIS, Discord, PiP and WebKit sandbox are retained.
No speculative aspect-ratio, colour-profile or mpv rendering changes are made.

The second snapshot adds the Nuvio Calendar source/cache, revised hero and profile
presentation, dithered scrims, setup resources and optional Windows UI HDR
compensation/ZIP delta updates. Windows-only native calls and update installation
remain behind their platform guards. Linux retains its existing package/update
boundary. Resource loading must work from the packaged application without a
checkout or development fallback; the Linux model/license payload checks remain
mandatory.
Upstream's loose-resource exclusion/staging is Windows-only. Linux deliberately
keeps both ONNX models, their notices, setup images and anime mapping data in the
application JAR; the shared `PackagedResources` loader uses its classpath fallback.
No separate Linux resource layout or source-tree dependency is introduced.

A deterministic loopback regression exposed one shared Calendar ordering defect:
an already-cached watched-history alias could take precedence over a newly
followed library entry after that entry's metadata arrived. The integration
reserves uncached titles in follow order before parallel fetches, preserving the
existing library-first deduplication rule and the new episode dates/navigation
ID. Cache lifetime, metadata fetching and the UI are unchanged by this correction.

The upstream Windows ZIP delta updater is retained without a Linux implementation.
Its delta path validates entry CRCs and returns before the full-archive SHA256
check used by ordinary downloads; this Windows integrity difference needs
separate upstream review. Linux controller checks cannot enter either installer
path. Windows HDR compensation likewise does not call its JNI entry point on
Linux. New-install defaults and hero/profile presentation follow the snapshot;
stored explicit user choices remain authoritative.

The build-script conflict combines upstream dependencies and test-data isolation
with Linux packaging/native tasks and live-display test protections. The four
automatically merged overlap files retain both sides' functionality: `Main.kt`,
common/desktop `GamepadBindings` and `PlayerScreenRuntimeUi.kt`.

ONNX Runtime Java 1.31.0 packages the current desktop's native libraries. Linux
x86_64 uses the upstream JAR loader and CPU execution provider; Windows keeps its
persistent DLL loader. Both U2NetP and YuNet model files are unchanged. Complete
model/runtime licenses and pinned provenance accompany them. Model-load failure
returns to the existing manual framing bias, and failed partial initialization
closes the already-created session. JNI resources, models and licenses are checked
by hash in the actual exported application JARs. The pinned JNI's upstream build
RUNPATH is not rewritten: Java loads the core first, satisfying its SONAME before
JNI loads. The package audit models this load order in temporary files and checks
the Ubuntu 22.04 ABI floor and CPU-only dependencies.

Account switching and settings import use a small Linux restart adapter. It
restarts the stable AppImage executable or DEB/Flatpak wrapper after the previous
process exits, with a bounded wait and preserved HOME/XDG/JVM profile paths.
It neither escapes the Flatpak sandbox nor reuses a disappearing AppImage mount.
Windows/macOS relaunch behavior is unchanged. A stalled application shutdown
cancels the Linux restart rather than creating a second player owner.

## Pinned engine reuse

The application changes do not change the canonical builder inputs or WebKit
recipe. The integration workflow accepts only this previously verified tuple:

| Input | Identity |
|---|---|
| Engine run / attempt | `38327066209` / `1` |
| Engine producing commit | `a5568593562ba18dac4b7d7efe294081801ae74e` |
| Engine `SHA256SUMS` SHA256 | `3709311586e9fc58dcd460e712f03accc8e9d8170787ef8db530c9224a9ef288` |
| Builder seed run | `38265810825` |
| Seed receipt SHA256 | `848a8fadf6957bb04d5702ae63639904c0e4f7977f54eb6aea8a14dce29fd311` |
| WebKit recipe | `b6e4ab22c0b2dd58e8dcc85d1eb6801810bd9d89c4ce2256d87596d2b1aede60` |

The producing workflow/event/branch/commit, seed receipt, OCI image, source and
patch hashes, completed-engine inventory, production flags, sandbox policy and
all three package verification gates remain mandatory. Reuse does not relabel an
old unpatched engine. The retained 2.50.4 engine includes upstream WebKit fix
`645ce1764ad44d709b8ddfdcfd564fd06d1d8dc6` for transparent HUD clearing.

## Validation and acceptance boundary

Headless validation covers real CPU model inference and unavailable-model paths,
cache reuse/cancellation, isolated account storage/import/restart and URL handoff,
local HTTP subtitle synchronization, player recovery/input/PiP policies, native
fixtures and actual package contents. Compiler success alone is not GUI acceptance.

The official backend remains `https://api.nuvio.tv` with the reviewed public
publishable key; custom/self-hosted configuration is preserved. Optional Trakt
device authentication needs a public client ID supplied by the user when no
built-in ID is configured. TMDB and other optional service credentials are not
invented or copied from private configuration. CPU framing requires no CUDA;
the pinned ONNX artifact does not provide macOS Intel natives, so that optional
feature retains its unavailable-model fallback there.

Before merging this snapshot into `linux`, use the newly built AppImage with a
disposable HOME/XDG profile and explicit JVM `user.home` and data-directory
overrides. Keep the active display/audio/session connection, but never reuse the
installed Flatpak's data or the existing Nuvio settings directory. Check:

- Official login and custom-server selection; account switching and settings
  import/restart using only disposable data.
- Home/Details hero framing, navigation/caches, Discover/Library filters,
  Calendar and cast/search behavior.
- 16:9 fullscreen playback; HDR/SDR where supported; Neutral/Cinematic/Vivid;
  seek-thumbnail clearing, rapid seeking, audio and subtitles.
- Automatic subtitle synchronization, including graceful no-fit behavior.
- PiP, controller/OSK, desktop media controls and clean player/app shutdown.

Native X11 hero composition limitations and other existing parity-roadmap gaps
are not promoted by this integration. DEB/Flatpak/AppImage artifact verification,
manual desktop acceptance and general release/source obligations remain distinct.
