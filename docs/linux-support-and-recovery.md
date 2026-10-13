# Linux support, installation and recovery boundaries

This describes the accepted development baseline, not a release announcement.
Use [the final readiness report](FINAL-READINESS-REPORT.md) for redistribution
gates and [the parity roadmap](linux-feature-parity.md) for individual feature
boundaries. Do not infer support from a dependency merely compiling.

## Desktop and media support

Linux x86_64 is the initial target. Bazzite GNOME/Wayland with NVIDIA is the main
tested desktop; the integrated player uses **XWayland/X11**, an AWT Canvas,
libmpv `gpu-next`, Vulkan and `x11vk`. Native Wayland embedding is not implemented.
A usable host Vulkan/graphics stack and X11/XWayland are required even when
software decoding is selected. Proprietary GPU drivers/ICDs are not bundled.

NVIDIA playback, the corrected WebKit HUD, selected profile/aspect/seek/PiP/input
paths and subsequent owner acceptance are recorded in the integration/packaging
evidence. These do not establish every driver, hybrid-GPU, multi-monitor or
desktop combination. AMD and Intel acceleration remain **unaccepted on physical
hardware** in this evidence set. Do not change system drivers or Vulkan selection
merely to satisfy a checklist. The existing decoder patches and software fallback
remain; hardware-decoder availability and Vulkan presentation are distinct.

HDR sources can be decoded and mapped to the existing SDR presentation path.
**Genuine HDR10 display output is not implemented/accepted.** The packaged mpv
has no compiled Wayland backend. Windows HDR UI compensation does not provide
Linux HDR. See [the separate HDR design](linux-hdr10-design.md); forcing PQ or
cropping the picture is not a supported workaround.

Shared Umbra features include Calendar, library/discovery filters, TMDB grouping,
higher-resolution backdrops, ONNX CPU hero framing, Ambient Adaptive, updated
defaults, account-scoped storage, keyboard/gamepad profile selection, Settings
highlight, artwork prewarming and automatic subtitle synchronization. New-install
defaults follow the integrated snapshot; existing explicit settings remain
authoritative. This list is source presence, not complete GUI/provider acceptance.
Native X11 hero blending and start/stop flicker remain known limitations.

## Formats and updates

These are instructions for a future approved candidate, not authorization to
install a new package on the owner's computer. Check its trusted SHA256 and exact
source revision first. Test in a disposable VM/user when practical.

| Format | Installation/upgrade model | Uninstall and rollback boundary |
| --- | --- | --- |
| DEB | Private `/opt/nuvio-desktop` payload and `/usr/bin/nuvio-desktop` wrapper; Ubuntu 22.04 ABI floor and declared GTK/WebKit/platform dependencies. Use the OS package manager with a verified DEB. No system Java/mpv replacement. | Package removal removes package-owned files, not XDG settings. Roll back by installing a retained approved package in a disposable test system; assess data/schema compatibility before reusing a profile. |
| Flatpak | Application payload plus separately pinned GNOME Platform; graphics extensions are supplied by Flatpak/host. Use a separately approved repo/bundle and an isolated installation for acceptance. | Remove the app through Flatpak; do not use data-deletion flags when preserving a profile. A retained app/runtime revision may be tested for rollback, but no guarantee of old-schema compatibility. |
| AppImage | Standalone file with bundled Java/media and private patched WebKit/GTK/helpers; host graphics/ABI remain external. Keep the previous verified file and launch the new file with a disposable profile first. | Remove the file to remove the package; data is separate. Restore the prior file/profile snapshot together after a failed test. AppImage execution still needs the host's FUSE/extraction and namespace support. |

Linux self-updating is not implemented. Windows's portable ZIP installer is not a
Linux upgrade path. Do not hand-edit packaged libraries and keep claiming the old
checksum/provenance. Preserve the WebKit Bubblewrap sandbox and its failure checks.
Installation, runtime acceptance, source availability and release approval are
separate gates for each format.

The latest inspected successful three-format run remains
[38397205286](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38397205286),
packaging `fce98dc1633b51f813c20965e05941c2745beb8c`. The later updater/audit commits
were not rebuilt as Linux packages in this mission. Earlier packaging failures
in historical documentation remain historical; they are not the current AppImage
relocation status.

## Flatpak limitations

The reviewed policy permits X11/IPC, network, PulseAudio, GPU rendering,
Nuvio's MPRIS name and ScreenSaver access. It does not grant arbitrary home/host
files, all devices, broad buses or host command execution. The X11 permission
itself is not strong isolation from other X11 clients.

Consequences: host Discord sockets, external host players/game launchers,
arbitrary local folders and evdev controllers are not promised. Swing file
pickers are not portal file pickers. Use only sandbox-visible files or a future
separately reviewed user-granted/portal integration; do not widen permissions as
an undocumented fix. Generic Wayland global-idle detection remains limited;
app-input fallback does not prove session-wide idle sensing. Real tray,
receiver/passthrough, desktop notification and controller acceptance are separate.

## Services and profiles

Reproducible packages contain the reviewed public cloud endpoint
`https://api.nuvio.tv` and official publishable client key; custom/self-hosted
server selection is preserved. Owner-reported login/account switching acceptance
does not prove all service/provider flows. Never copy developer properties,
private/service-role credentials or an existing profile into a package.

Optional INTRODB/IMDb endpoints and the Premiumize client ID remain unset in the
public package recipe. Optional Trakt/TMDB integrations require supported user
configuration/registration as documented in the
[packaging README](../tools/linux/packaging/README.md). Unavailable optional
services should not be described as enabled, nor should guessed credentials be
added. Service/trademark permissions are separate from open-source licenses.

Account-scoped storage and restart behavior have deterministic tests. The GUI
Settings Backup → Import → Restart flow remains manually pending; the owner
declined additional testing and is not asked to repeat it here. The
[disposable acceptance protocol](linux-acceptance-protocol.md) supplies a future
test plan. Neither backup import nor updater copy failure is represented as a
proven atomic whole-application rollback. Keep a full disposable profile/install
snapshot for failure tests and preserve logs without exposing credentials.

## Building and redistributing

Use Java 17 and the existing
[source/media recipe](../tools/linux/media-runtime/README.md) and
[canonical packaging pipeline](../tools/linux/packaging/README.md). Ordinary
automated validation is headless; live window tests require separate explicit
authorization. Do not rebuild WebKit for documentation/Kotlin changes when the
verified engine's strict recipe/provenance contract still matches.

The application is GPLv3, with component-specific media/JDK/WebKit/JVM/model/font
terms. [The source-delivery plan](compliance/source-delivery-plan.md) links binary
identities to retained sources/patches/recipes and specifies the missing recipient
channel and rebuild/relink evidence. Sources/notice URLs and hashes alone do not
establish complete compliance. All formats and separately distributed OCIs remain
**BLOCKED for redistribution** pending the precise rights/source/availability
decisions in the final readiness report. Native HDR is optional and is not added
as an unrelated release requirement.
