# AppImage WebKitGTK relocation feasibility

Audited on 2026-10-10 at `linux` HEAD `d261f848` with the existing uncommitted
Q02 packaging work. **AppImage remains blocked.** No WebKit compilation,
application launch, graphical process, binary patch or sandbox bypass was used.
Existing DEB/Flatpak artifacts and the canonical image are retained unchanged.

## Exact input and root cause

The pinned packaging builder contains Ubuntu packages
`libwebkit2gtk-4.1-0` and `libjavascriptcoregtk-4.1-0`
**2.50.4-0ubuntu0.22.04.1**, not 2.50.0. Its library is
`libwebkit2gtk-4.1.so.0.19.7`. Static inspection confirms these strings:

- `/usr/lib/x86_64-linux-gnu/webkit2gtk-4.1`
- `/usr/bin/bwrap`
- `/usr/bin/xdg-dbus-proxy`
- `WEBKIT_INJECTED_BUNDLE_PATH`, but **no `WEBKIT_EXEC_PATH`**.

The upstream 2.50.4 release archive was downloaded for inspection only and
matches the [published SHA-256](https://webkitgtk.org/releases/webkitgtk-2.50.4.tar.xz.sums):
`d3bfa473845acfab72635bada5e0d134fda6792c5b95c5c5cd141b46125bd8e4`.
It contains 33,322 files totaling 381,338,471 uncompressed bytes. This measures
source size, **not** compiler intermediates, build time or peak memory.

Relevant source in that verified archive:

| File / function | Production behavior |
| --- | --- |
| [ProcessExecutablePathGLib.cpp](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/WebKit/Shared/glib/ProcessExecutablePathGLib.cpp), `findWebKitProcess` | `WEBKIT_EXEC_PATH` and sibling-executable discovery are inside `ENABLE(DEVELOPER_MODE)`. Release builds append each process name to `PKGLIBEXECDIR`. |
| [OptionsGTK.cmake](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/cmake/OptionsGTK.cmake), install variables | `LIB_INSTALL_DIR` and `LIBEXEC_INSTALL_DIR` configure absolute installation locations. No production relocation option was found. Developer mode also changes JavaScriptCore development-option defaults; it is not a release relocation switch. |
| [BubblewrapSandboxChecks.cmake](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/cmake/BubblewrapSandboxChecks.cmake) | Both `BWRAP_EXECUTABLE` and `DBUS_PROXY_EXECUTABLE` must be absolute paths; the former is version-checked during native configuration. |
| [BubblewrapLauncher.cpp](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/WebKit/UIProcess/Launcher/glib/BubblewrapLauncher.cpp), `bubblewrapSpawn` | Uses compiled helper/library/data paths for namespace mounts, the compiled proxy path for its bind, and the compiled bubblewrap executable. Developer-only mounts cover the overridden process location. |
| [XDGDBusProxy.cpp](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/WebKit/UIProcess/Launcher/glib/XDGDBusProxy.cpp), `XDGDBusProxy::launch` | Executes the compiled proxy path. |
| [Sandbox.cpp](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/WTF/wtf/glib/Sandbox.cpp), `isInsideUnsupportedContainer` | Separately executes the compiled bubblewrap path to test namespaces. A failed test in a detected container can cause sandboxing to be disabled. |
| [WebKitWebContext.cpp](https://github.com/WebKit/WebKit/blob/webkitgtk-2.50.4/Source/WebKit/UIProcess/API/glib/WebKitWebContext.cpp), `injectedBundleDirectory` | The injected-bundle environment override is available in production; it does not relocate subprocesses or sandbox helpers. |

The process-path and bubblewrap-launcher files are byte-identical between the
inspected 2.50.0 and 2.50.4 tags and the verified 2.50.4 archive. The original
diagnosis therefore applies to the actual packaged input. Relative ELF RUNPATH
changes library lookup, not executable discovery or sandbox filesystem mounts.

## Approaches evaluated

| Approach | Portability and security | Build / CI / maintenance | Redistribution / decision |
| --- | --- | --- | --- |
| Existing release binary, copied helpers, RUNPATH and environment | Does not change release subprocess lookup. Injected-bundle override alone is insufficient. | Cheap, but demonstrably incomplete. | Existing notices/source duties remain. Reject as a solution. |
| Supported CMake install-path settings | Correct for fixed prefixes such as DEB's system installation; cannot predict an AppImage mount or extraction path. Sandbox helpers explicitly require absolute paths. | Easy to configure; every different fixed prefix requires rebuilding. | Normal WebKit obligations. Does not meet relocation requirement. |
| Enable developer mode | Enables extra path discovery, not just the requested release feature. Still needs correct sandbox-helper paths and namespace visibility. | Full build, larger configuration/behavior difference from the distribution release. | Same source duties plus changed build configuration. Reject for release. |
| Maintained linuxdeploy GTK / Tauri packaging | GTK resource/module handling is useful reference. No suitable production relocation solution was established: Tauri's documented WebKit fix patches binary paths. | Reusing the GTK plugin does not resolve this blocker. Its resource/module handling would still need adapting and pinning to this layout. | Tool/plugin notices apply. Binary patching is prohibited here. |
| Outer mount-namespace launcher mapping private files to compiled paths | Could theoretically supply fixed paths without rewriting WebKit. Adds an application-wide container layer, nested namespaces and host graphics/session integration; container detection must not silently suppress WebKit's inner sandbox. | Substantial new launcher/runtime policy and cross-distro validation. Not a small packaging substitution. | Adds helper/closure/source obligations. Not selected. |
| Pinned release source patch deriving private paths at runtime | Plausible route without developer mode: relocate processes, both sandbox tools and read-only namespace mounts together. Must fail on missing private inputs, not fall back to an unsandboxed or host-WebKit launch. | Full WebKit/JavaScriptCore build; security-sensitive patch and dependency refreshes on each version. Cold-build budget and sandbox acceptance are not established. | Must distribute modified corresponding source, patch, recipes, notices and required linking materials. Candidate for further work, not a verified solution. |

References: [linuxdeploy GTK plugin](https://github.com/linuxdeploy/linuxdeploy-plugin-gtk),
[Tauri bundler](https://github.com/tauri-apps/tauri/blob/dev/crates/tauri-bundler/src/bundle/linux/appimage/linuxdeploy.rs),
and [Tauri's binary-path relocation change](https://github.com/tauri-apps/tauri/pull/2940).
These are reference implementations, not build inputs or proof of sandbox safety.

## Required runtime closure

The actual builder includes executable `WebKitWebProcess`,
`WebKitNetworkProcess` **and `WebKitGPUProcess`**, plus
`injected-bundle/libwebkit2gtkinjectedbundle.so`. Do not omit GPUProcess merely
because an older AppImage recipe copies only Web/NetworkProcess. MiniBrowser is
present in the distribution helper directory but is not a required Nuvio helper.
The WebKit library, JavaScriptCore, subprocesses and injected bundle must come
from the same build; their private IPC/internal interfaces are not a mix-and-match
ABI.

The sandbox closure includes `bubblewrap` (currently 0.6.1-1ubuntu0.3),
`xdg-dbus-proxy` (0.1.3-1ubuntu0.1) and their libraries. Both executables are 0755,
not setuid. ELF inspection shows bubblewrap needs libselinux/libcap/libc, and
the proxy needs GIO/GObject/GLib/libc. WebKit's libseccomp-generated filter,
namespace restrictions, D-Bus filtering and portal identity must remain intact.
Upstream 2.50.4 treats NetworkProcess as trusted and launches it outside the
bubblewrap WebProcess sandbox; preservation must describe that boundary accurately.

A source patch needs to cover at least process discovery, bubblewrap spawning,
proxy spawning/binding, and the separate container namespace probe. It must mount
the private executable/library/resource directories read-only at their resolved
paths, including when AppImage is mounted beneath `/tmp` (which the inner sandbox
replaces). Keep PID/network restrictions and seccomp construction unchanged.
Resolving only `findWebKitProcess` or checking for the `WEBKIT_EXEC_PATH` string
is not security/relocation acceptance. The current marker guard rejects the
known release library; it cannot certify a future custom build.

GTK/GIO modules, TLS backends/certificates, pixbuf loaders and their caches,
schemas, MIME data and optional GStreamer plugins/scanner need explicit resource
and dynamic-dependency inspection beyond `ldd`. Keep the GLIBC 2.35 / GLIBCXX
3.4.30 baseline and private media contract. Do not bundle host Mesa/NVIDIA/DRM/GBM
drivers or substitute a modern SDK runtime without repeating ABI/graphics checks.

Another retained payload finding is six absolute artist home-path strings in the
builder's GTK 3.24.33 library, inside SVG `inkscape:export-filename` metadata.
They are upstream resource metadata, not user configuration. They still fail
the current strict path scan. Binary rewriting and silently weakening that scan
are not solutions; a source/resource-level resolution remains necessary.

## Build/cache feasibility and stopping point

[Upstream documents production CMake/Ninja builds](https://docs.webkit.org/Build%20%26%20Debug/BuildOptions.html#building-the-gtk-port).
The inspected release accepts GCC 12.2+; that compiler requirement is compatible
with the existing builder. A production source build is technically plausible.
There is no evidence that WebKit is inherently impossible to build on Actions.

However, the existing job has a 180-minute budget, rebuilds media/application
inputs and caches only downloaded tools/Gradle modules. It has **no WebKit build
recipe, measured cold-build budget or validated engine artifact/cache contract**.
GitHub currently documents standard public Linux runners as 4 CPU / 16 GB RAM /
14 GB SSD (private runners: 2 CPU / 8 GB / 14 GB):
[runner specifications](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
Neither source archive size nor this machine's larger free disk establishes that
the combined cold build fits those limits. The old WebKit development-space
recommendations are not measurements of this production configuration either.

A viable strategy must first establish a dedicated WebKit stage in the existing
canonical pipeline: pinned source plus distro patches, pinned builder and
dependencies, release mode with developer mode off and bubblewrap on, bounded
compile/link concurrency, then measured wall time/peak RSS/peak disk on a cache
miss. Separate that resource budget from media/Gradle/Flatpak work. A larger or
self-hosted Actions runner may be necessary; availability has not been established.
Do not assume deleting runner toolchains or cache hits guarantees a successful build.

Cache verified downloads and, optionally, compiler inputs keyed by source,
patches, compiler, builder digest and CMake options. Reuse a staged WebKit build
only with a matching provenance/file inventory and repeated ELF/path/helper
checks; never trust cache presence alone. A missing cache must have a supported
cold-build path. Integrate that private install tree only into AppImage assembly;
DEB and Flatpak must retain their existing system/GNOME WebKit paths.

There is also a concrete acceptance-environment blocker. With DISPLAY,
WAYLAND_DISPLAY and the live-test flag unset, a network-disabled disposable
instance of the pinned builder ran only:

```sh
bwrap --ro-bind / / --proc /proc --dev /dev --unshare-all /usr/bin/true
```

It failed with `bwrap: setting up uid map: Operation not permitted` (exit 1).
No WebKit process was started. This is evidence about that isolated container,
not a claim about all Bazzite hosts or GitHub runners. Because WebKit's container
probe can disable its sandbox after this failure, launching WebKit there would
not prove preservation. A suitable isolated namespace-capable acceptance
environment is needed; do not disable WebKit sandboxing, use debug permissions
or globally relax host policy to make the test pass.

Therefore no production-safe relocatable engine/build/cache/acceptance combination
has been established. Compilation and implementation stop here under the task's
feasibility rule. No speculative patch or new build system is added. The likely
next route is a separately reviewed release-source relocation patch and measured
engine stage, not enabling a developer-only environment variable.

## Verification required before unblocking

For a future implementation, relocate the completed image into two unrelated
temporary paths (including spaces), remove access to builder/host WebKit, and
exercise the **actual production path resolver and sandbox launch**, not a copied
test implementation. Check all three process paths, injected bundle, proxy and
bubblewrap paths, missing-input failures and read-only mount arguments. In a
namespace-capable isolated environment prove seccomp/PID/network/D-Bus restrictions
remain active and private helpers/data are visible inside the sandbox. Graphical
HUD/playback acceptance remains a separate explicitly authorized manual test.

Only then build the actual AppImage with the existing pinned tool/runtime and
inspect extracted contents, ELF closure, relative RPATH/RUNPATH, symlinks,
permissions, resource caches, absence of developer paths, release identity and
SHA256. Do not emit an artifact solely because an assembly command can compress
an AppDir. Existing DEB/Flatpak artifacts are **built and statically verified**;
AppImage is **not built**, GitHub CI is **not verified**, and package installation /
graphical runtime is **not manually accepted**. Q02 remains `LINUX_PARTIAL / YES`.
Public service configuration and release corresponding-source/licensing work
remain separate publication blockers described in [README.md](README.md).

Follow-up headless validation: all 12 existing packaging contract tests pass.
The actual `portable_support` preflight also rejects the pinned builder's release
WebKit before copying its helpers or emitting an AppImage. All eight entries in
the retained artifact `SHA256SUMS` pass. The other 21 pre-existing changed /
untracked files are byte-preserved; this task changes only the README and Q02
documentation and adds this report. No Kotlin/native production or packaging
script changed, so no application/native rebuild was performed.
