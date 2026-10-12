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

## Independent manual CI and builder bootstrap

[Linux WebKit engine](../../../../.github/workflows/linux-webkit.yml) is a
`workflow_dispatch` workflow on `linux`. It runs the same pinned GTK/WebKit stage
without media compilation, Gradle, JNI, DEB, Flatpak or Nuvio launch. Canonical
builds still use `--packaging-work-dir`; independent builds use `--builder-seed`.

One bootstrap is unavoidable: there is no published digest-pinned packaging
builder image. The packaging workflow now exports `nuvio-webkit-builder-seed`
after its canonical build, before AppImage compilation. The exporter verifies
the canonical source-content digest and builder input hashes, then exports the
exact OCI image and a receipt containing its ID, archive SHA256, recipe hashes
and canonical commit. The builder image contains no application/private config.

For this initial export, manually dispatch **Linux packages** on `linux` with
`bootstrap_only=true`. It retains the existing pinned media and canonical
application/DEB build needed by the seed provenance contract. It skips DEB
smoke installation, Flatpak runtime/export, AppImage compilation, normal package
uploads and the three-format release gate. A separate gate requires successful
media/canonical builds, seed export **and upload with an artifact ID**. Its summary
is explicitly **BOOTSTRAP ONLY — not a validated release**. The option defaults
to false; tag pushes always retain the normal full build and release gate.

Choose a **trusted** producing run and enter its run ID and the receipt SHA256
from its summary into the manual workflow. It checks the originating workflow,
branch and commit, the caller-supplied receipt hash, recipe inputs, OCI bytes and
loaded immutable image ID. No mutable tag or arbitrary base is accepted. The
receipt digest is an operator-supplied trust anchor, not a signed attestation;
do not obtain it solely from an untrusted downloaded artifact. Earlier runs did
not export seeds, so one new bootstrap/export is needed. A matching existing
local canonical build can export without compiling anything:

```sh
python3 tools/linux/packaging/webkit/builder_seed.py \
  --packaging-work-dir build/linux-packaging --output build/webkit-builder-seed
```

Reuse that seed on later attempts. Engine-only changes do not invalidate it;
packaging/media builder input changes require a new bootstrap. WebKit's small
dependency image still extends that exact base using the signed APT snapshot.
Its immutable ID is retained for later assembly. No application/media rebuild
is needed for an engine retry.

The runner input defaults to `ubuntu-22.04`; a larger Linux x86_64 runner label
can be selected. On GitHub-hosted Ubuntu, `setup-runner.sh` explicitly installs
Podman, UID mapping/network/storage helpers, Python, curl, jq, gh, CA roots and
zstd from the runner's signed distribution repositories. These are orchestration
tools; the pinned compiler/libraries still come exclusively from the verified
container. On self-hosted runners it checks pre-provisioned tools and performs
no installation. The script refuses local/non-Actions use and requires Python
3.10+, rootless Podman and cgroup v2. It does not enable privileged containers,
relax host namespace policy or remove memory/process limits.

For a cold engine build, use a **configured GitHub larger Ubuntu x64 runner with
8 CPUs, 32 GB RAM and 300 GB SSD**, or a dedicated self-hosted Linux x86_64 runner
with comparable RAM and at least 100 GiB free build/image/cache space. GitHub lists
that [larger-runner configuration](https://docs.github.com/en/actions/reference/runners/larger-runners);
its label is chosen when provisioning it and is not a built-in `ubuntu-22.04`
alias. Repository access/billing must be configured by its owner. The standard
runner is not a guaranteed cold-build target; its default remains available for
explicit preflight diagnostics. No larger runner has been provisioned or measured
by this change, so build time/cache-transfer acceptance is still pending.

Before downloading/importing a seed or building containers, preflight prints total/available RAM,
affinity CPU count and disk, and requires **14 GiB available RAM, two CPUs and
40 GiB free disk**. Available RAM respects the containing cgroup; the engine CLI
rechecks before seed import (after cache restoration) and after loading/extending
images. The RAM floor provides modest margin over the
measured 13.24 GiB local peak, not a guarantee. Podman's image store, up-to-8-GiB
cache snapshot and exported artifacts also need room. A standard runner may
fail preflight; use sufficient infrastructure instead of removing limits.
There is no `--cpus`: concurrency remains four Ninja jobs, two GTK jobs and one
link job, with 18 GiB memory/no-extra-swap and 512-process container limits.

Compiler stdout/stderr stream live and append unabridged to `build.log`. Ninja
prints completed/total edges, percentage and elapsed time. Every minute the
supervisor prints log-byte growth, seconds since output, host RAM/disk and
`podman stats` CPU/memory/PIDs. Quiet links remain visible without declaring
every silence a stall. ccache stats print before compilation and on WebKit shell
exit when its trap can run. Logs/resource evidence upload even after failure.

The dedicated compiler budget defaults to 270 minutes (maximum 300), inside a
315-minute step/360-minute job. Canonical packaging defaults to 210 minutes
inside its existing 240-minute step. Deadline/SIGINT/SIGTERM handling removes
only the named/labelled compiler container and waits for the client. An
`always()` cleanup repeats this check before snapshot/save. Failure to confirm
cleanup or finish the snapshot prevents cache upload. Each cleanup command has
a 30/60-second timeout; cleanup/snapshot workflow steps have a ten-minute limit.
The Podman client wait is also bounded to 30 seconds. Runner loss, SIGKILL or exhausting the entire
job budget can still prevent uploads; they cannot be guaranteed after host loss.

Only a stopped-container `ccache-snapshot` is uploaded, under unique run/attempt
keys and recipe/builder restore prefixes. `CCACHE_TEMPDIR` is inside the disposable
container, outside the cache mount. Snapshot/restore copy completed result and
manifest entries plus counters, excluding temporary files, locks, symlinks and
arbitrary configuration. Existing local cache/generations are retained. Fresh
runner retries recheck source and rebuild through **ccache**, not incomplete
object/install trees. A retained work directory can also resume its objects.
Cache availability is optional and never acceptance.

Successful builds recheck the completion contract and export `engine.tar.gz`,
the exact `engine-builder.oci` for assembly, and `SHA256SUMS`. The engine archive
includes install inventory, build policy, CMake cache and corresponding source,
omitting temporary objects. It remains **BUILT_ONLY**. For later assembly, check
the trusted artifact's SHA256SUMS, load its exact image and extract the generation
under `generations/`. `package.py` still requires the current recipe **and the
same canonical packaging builder ID**, then runs the full AppImage payload audit.
A new canonical image with a different builder ID cannot consume it by renaming
directories. Automatic cross-run application/candidate assembly is not added.
The mandatory three-format release gate remains unchanged for normal/tag builds;
bootstrap-only runs use the explicit non-release seed gate described above.

CI resource fit and transfers still need a real run. The reported four-hour
timeout in run 38188755529 motivated this separation; no new workflow/full engine
compile was launched while implementing it. Safe local review commands:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/linux/packaging/tests -v
python3 -c 'import ast,pathlib; [ast.parse(p.read_text(), filename=str(p)) for p in pathlib.Path("tools/linux/packaging").rglob("*.py")]'
bash -n tools/linux/packaging/webkit/build-engine.sh
bash -n tools/linux/packaging/webkit/build-gtk.sh
git diff --check
git diff -- .github/workflows/linux-packages.yml tools/linux/packaging
git status --short
```

## Production engine and acceptance boundary

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
