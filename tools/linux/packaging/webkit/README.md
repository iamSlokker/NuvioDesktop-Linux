# AppImage-only WebKit build stage

This stage builds WebKitGTK 2.50.4 in production mode against the canonical
Ubuntu 22.04 packaging builder. It does not rebuild libmpv, JNI or the application,
and does not replace DEB's system WebKit or Flatpak's GNOME runtime.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/build.py --format webkit \
  --work-dir build/linux-packaging \
  --webkit-source-archive build/linux-webkit-feasibility/webkitgtk-2.50.4.tar.xz
```

The source archive is optional and is always SHA256 verified. The source and
Ubuntu GTK source inputs are pinned in `manifest.json`. GTK retains Ubuntu's
3.24.33-1ubuntu2.2 patches, including CVE-2024-6655. Source SVG editor export-path
attributes are removed before regenerating resources; no ELF strings are edited.
GTK's private resource-directory accessors resolve relative to its loaded DSO.
Compiler prefix maps reduce absolute and relative WebKit build locations to
source-relative diagnostic paths. An early probe uses the configured production
C/C++ flags. The final scan rejects absolute build/home paths; it recognizes
standalone public license URLs, relative copyright file globs and complete
NUL-delimited `./source/...` diagnostics as non-absolute references. It never
exempts a whole file or rewrites library/license bytes to satisfy the audit.
Installed Adwaita SVGs receive the same metadata-only treatment during assembly
(including both XML quote styles). Changed originals and the exact patch are
retained in the source artifact. MIME, schema and icon caches are regenerated
from packaged resources; they are not copied from the builder's trigger output.

After the engine completes, reuse the canonical app/media image with:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/build.py --format appimage \
  --work-dir build/linux-packaging
```

`--webkit-generation build/linux-webkit/generations/<recipe-id>` optionally
selects a completed candidate; it must match the current recipe/base image.
AppImage assembly preserves existing DEB/Flatpak artifacts. Engine and assembly
identities are separate, so a launcher/audit-only change need not recompile WebKit.
The runtime manifest retains policy switches and a digest of the full build
policy. Exact compiler flags, including container prefix maps, remain in the
corresponding-source `engine-build.json` rather than the runtime payload.

`patches/0001-private-release-paths.patch` is local to this engine build. Paths
come from `dladdr` and `realpath`, not the working directory or developer-mode
environment variables. Missing/escaping/non-executable or setuid helpers are
fatal. Private paths cover all three WebKit helpers, the injected bundle,
bubblewrap, xdg-dbus-proxy and locale data. The private image is mounted read-only
after WebKit replaces `/tmp`, so an AppImage mount remains visible in children.

The patch preserves upstream seccomp and D-Bus filter rules and namespace flags.
It rejects sandbox-disabling API/environment requests, debug sandbox permissions,
unsupported nested Flatpak/Snap launches and failed container namespace probes.
It does **not** pretend all subprocesses have the same upstream trust boundary:
WebProcess requests the Bubblewrap sandbox; upstream NetworkProcess is trusted,
and the GTK GPUProcess launch does not set the WebProcess sandbox flag. Their
private path discovery is covered, without inventing a new GPU sandbox policy.

Developer mode stays OFF and Bubblewrap stays ON. The GTK3/libsoup3 ABI is kept.
GTK3's 4.1 API does not enable the sandbox automatically. The AppImage launcher
sets the pinned release's production `WEBKIT_FORCE_SANDBOX=1` hook before context
creation. An isolated run without that opt-in was rejected by the private
engine's fail-closed WebProcess guard. This does not change DEB/Flatpak launchers.
Shared libraries, modules (including the injected bundle) and executables all
receive full RELRO linker flags; cache policy and the payload audit check them.
Documentation, introspection, test runners, MiniBrowser and WebDriver are omitted.
JPEG XL is disabled because the pinned Ubuntu baseline predates the required
libjxl 0.7; this matches an optional WebKit image format, not libmpv codecs.
The current player uses X11/XWayland, so this stage builds the X11 target.
Optional libbacktrace/system-sysprof tooling is not required by the HUD.

Cold builds require at least 40 GiB free disk. Compilation is bounded to four CPU
jobs, one link job and an 18 GiB container memory limit with no additional swap.
`build-resources.txt` records elapsed time and maximum process RSS; the latter
is not aggregate container memory. `container-memory-peak.bytes` records the
kernel's aggregate cgroup high-water mark. ccache is limited to 8 GiB. Source, recipe,
patches, flags and base-image identity select a generation; completed candidates
must match their recorded file bytes, modes and relative symlinks before reuse.
Prepared WebKit source is also checked before resuming an interrupted build.
No host package installation, Flatpak overrides or user-profile access is used.

Outputs are **BUILT_ONLY candidates**. A successful build/cache record does not
authorize bypassing the existing AppImage gate. Whole-engine relocation, private
dependency/resource closure, ABI and sandbox acceptance are separate checks.
The original source archive, Ubuntu source control/archive/patch inputs, local
patches, build recipe, package versions and generated GTK resource patch must be
retained with corresponding-source material; the runtime still needs its notices.

## Isolated graphical acceptance procedure

Do not use the active desktop DISPLAY or the existing Nuvio profile. Before any
graphical probe, prepare a disposable test directory, private D-Bus session,
temporary XDG config/data/cache/runtime directories and a dedicated Xvfb server
with a private Xauthority cookie, TCP disabled and GLX disabled. Choose its display
through Xvfb's display-fd mechanism. Only the probe receives that DISPLAY.
Keep XDG_RUNTIME_DIR in a short private temporary path, independently of the
image location: Unix socket path limits also apply to WebKit's D-Bus proxy.

Use an ephemeral WebKit context and local fixture assets; verify the actual
controls-overlay message handler and JavaScript replies. Capture Web/Network/GPU
process command paths, `/proc` namespace IDs, `NoNewPrivs`, seccomp status and
read-only private-image mounts. Confirm sandboxed WebProcess cannot write the
image or read a sentinel outside its allowed paths. Test two unrelated image
locations, one containing spaces, then missing-helper and unavailable-sandbox
failures. Never disable sandboxing to make a container pass. A container that
cannot create namespaces is suitable for compilation, not sandbox acceptance.

Compilation and the production resolver fixture do not prove this graphical
procedure has passed. No live desktop or installed Nuvio/Flatpak may be used as
the probe environment.

Once the AppImage has passed static inspection, the following commands prepare
test tools in a disposable container, then run the isolated procedure. The second
command opens windows **only on its dedicated Xvfb**, using copied local controls
assets and the production `LinuxControlsOverlay`; it never runs Nuvio, Java or mpv.
The page loads from a temporary `nuvio-player-ui` directory outside the image,
matching the production asset-export location. This checks native page/JavaScript
messaging, not Kotlin font export or full visual/player acceptance.
The normal packaging/CI commands do not invoke it. Host `/usr/bin/xkbcomp` is an
Xvfb prerequisite; do not install host packages to satisfy this probe.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/webkit/prepare-probe.py \
  --engine-generation build/linux-webkit/generations/<recipe-id> \
  --output build/linux-webkit/probe-tools

env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/webkit/probe-isolated.py \
  --image build/linux-packaging/Nuvio.AppDir/usr/lib/nuvio-desktop \
  --tools build/linux-webkit/probe-tools \
  --controls composeApp/src/desktopMain/resources/player-ui \
  --output build/linux-webkit/isolated-acceptance
```

Only children connected to the newly allocated Xvfb receive the live-test flag.
One image copy lives under `/tmp` to exercise WebKit's tmpfs replacement; the
other uses an unrelated output-filesystem location and a symlinked entry point.
Both paths contain spaces. The harness requires room for one image copy plus
512 MiB in `/tmp`, and fails rather than skipping that case when space is short.
It uses fresh HOME/XDG paths and a private session bus, disables core
dumps, records subprocess/sandbox evidence and tears down its server/process
group. It cannot certify full application playback, mounted-FUSE execution or
unseen GPU/desktop combinations; those remain explicit manual acceptance.

Both cases passed locally on 2026-10-11. Per-case JSON preserves completed
evidence even if a later case fails; `acceptance.json` is written only after both
cases pass. See [the artifact, resource measurements and acceptance limits](../WEBKIT-RELOCATION.md#local-acceptance-2026-10-11).
