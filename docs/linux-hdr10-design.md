# Linux HDR10: source audit, design and experimental Wayland session

**Assessment (2026-10-14): EXPERIMENTAL Wayland HDR session implemented behind a
default-off Linux setting and wired into DEB/AppImage/Flatpak packaging (no CI
package built yet). HDR OUTPUT IS UNVERIFIED ON HDR HARDWARE.** Nothing in
this repository has observed a PQ/BT.2020 frame reach an HDR monitor through Nuvio.
The accepted embedded X11 player is unchanged and remains the only path whenever any
gate below is not met. Redistribution of a correctly described SDR player is not
affected; the separate licensing and source blockers still apply.

The original audit (below the next section) is retained unchanged as the evidence
for why the embedded X11 Canvas cannot carry HDR and why a separate native-Wayland
presentation boundary was chosen.

## Implemented experimental session (part 2)

### Architecture

| Piece | Location | Role |
| --- | --- | --- |
| Option contracts | `native/linux/player_session_options.h` | Pure lists: the unchanged X11 embedding boundary (`gpu-context=x11vk`, `wid=<XID>`) and the HDR session boundary (`vo=gpu-next`, `gpu-api=vulkan`, `gpu-context=waylandvk`, no `wid`, `fullscreen=yes`, `input-vo-keyboard=yes`, default bindings off, `wayland-app-id`, optional `vulkan-device`). |
| HDR session helpers | `native/linux/hdr_wayland_session.{h,cpp}` | Headless libmpv capability check (uninitialised handle; mpv rejects `gpu-context=waylandvk` unless compiled in), app-owned key bindings (`keybind <key> "script-message nuvio-hdr-key <code>"`), event translation (`video-target-params`, `vo-configured`, start-time VO errors). |
| JNI | `player_bridge.cpp` (`LinuxHdrSessionNative_*`) | `create` builds an ordinary `Player` with `hdrSession=true` in the shared handle table, so every existing per-handle `NativePlayerBridge` call (position, seek, tracks, subtitles, volume, stats, dispose) works unchanged. Same shared option steps as the embedded player (`hwdec=auto`, custom/Nuvio options, headers, separate audio, start position); no X11 lifetime reservation, AWT lock, WebKit HUD or seek thumbnails. Always starts **paused**. Also `libmpvSupportsWayland`, `transferPlaybackState`, `showText`, `scriptMessage`, `isFocused`. |
| Capability probe | `native/linux/hdr_capability_probe.{h,cpp}`, `nuvio_hdr_probe.cpp` → `nuvio-hdr-probe` | Separate helper process (hard 8 s timeout, killable; Vulkan queries are not cancellable). One **unmapped** `wl_surface` + `VkWaylandSurfaceKHR`; no role, commit, swapchain or setting change. Refuses to connect without `WAYLAND_DISPLAY`. Reports colour values by protocol enum **name** from the generated header. |
| Policy | `LinuxHdrSessionPolicy.kt` | Pure, fail-closed decision table (below). |
| Coordinator | `LinuxHdrSessionCoordinator.kt` | Handoff/fallback state machine (Idle → Probing → Starting → Active). Display-free; driven by a controller adapter. |
| Controller glue | `NativePlayerController.kt` (`LinuxHdrHost`, Linux main player only) | Creates/promotes/retires sessions under the existing `nativeProcessLifecycleLock` and attach generation; replays app-owned mpv properties and subtitle styling; re-creates the Canvas player on fallback through the ordinary `attachPending()` path. |
| Input | `LinuxHdrInput.kt`, `GamepadKeyInjector.kt` | mpv key name → AWT `VK_` code → the same shortcut bindings/actions as the embedded dispatcher. Gamepad keys route to the session only while its window reports `focused`. |
| HUD v1 | `resources/player-scripts/linux-hdr-osc.lua` | Display-only mpv OSD: title, position/duration, playing/paused, progress bar, key hint, "HDR session (experimental)". Exported at runtime into the user's private data directory (never shared `/tmp`), loaded with `scripts=`. Binds no input. |
| Setting | `LinuxHdrSessionSettings` + `PlatformHdrSettingsRows` | "Experimental HDR (Wayland)", Linux only, machine-local, **default off**, shown under HDR mode. |

### Gating (all must hold; otherwise the embedded X11 player is used unchanged)

1. Linux main-player attach (not hero/trailer), not in Picture-in-Picture.
2. "Experimental HDR (Wayland)" enabled.
3. `WAYLAND_DISPLAY` present.
4. Desktop HDR mode is Auto or Always Passthrough (Always Tonemap keeps SDR).
5. Decoded source transfer (embedded player's existing `video-params` report) is PQ or HLG. BT.2020 primaries alone do not qualify.
6. The **loaded** libmpv has the `waylandvk` context. The canonical media recipe on this branch builds it (`HAVE_WAYLAND=1`); every package built before it (including all published/inspected artifacts) has `HAVE_WAYLAND=0` and always fails this gate.
7. `nuvio-hdr-probe` is shipped next to `libplayer_bridge.so`, completes, and reports: `wp_color_manager_v1` with PQ and BT.2020; a ready PQ/BT.2020 preferred surface description with luminance headroom (max > reference); at least one PQ/BT.2020 output; exactly one Vulkan device with a 10-bit HDR10 ST2084 surface format, Wayland presentation and `VK_EXT_hdr_metadata` (its name is passed as `vulkan-device`). Missing helper, timeout, crash, malformed output, multiple eligible devices: SDR.
8. After creation, mpv itself must load the file, configure its VO and report a **PQ/HLG BT.2020 `video-target-params`** before the session is promoted. The target colour space mapping is the shared `desktopHdrTargetMpvProperties()` (Auto: `target-colorspace-hint=auto`, mode `target`; Passthrough: `yes`, mode `source`); PQ is never forced.

Gates 1-6 are evaluated before any display connection; the probe runs at most once
per attach. Each decision is logged (`LinuxHdrSession` tag) when the setting is on.

### Handoff and fallback

- Eligible: the embedded player is paused at position *P*; the HDR session starts
  paused at *P* with the same source URL, headers, separate audio and options. After
  its `fileLoaded` the live track choices, external subtitles, delays, speed and
  volume are copied (`transferPlaybackState`), and app-owned mpv properties,
  subtitle style and resize mode are replayed. On promotion the controller handle
  becomes the HDR session, the embedded player is disposed, and playback resumes
  only if it was playing. Progress polling, scrobbling/Continue Watching, MPRIS and
  track menus keep working because they all read the controller handle.
- Failure before promotion (create error, no Wayland VO, start-time VO error,
  SDR/unknown target, playback error, 15 s timeout, re-attach, disposal): the HDR
  session is disposed and the still-alive embedded player resumes in place.
- Failure or exit after promotion (`vo-configured` false, target becomes SDR e.g.
  window moved to an SDR monitor, session ended, user leave, HDR mode → Always
  Tonemap): the embedded Canvas player is re-created at the HDR session's position.
- Any failure or user leave declines HDR for that source for the rest of the player
  lifetime (no flapping). New sources and ordinary re-attaches are evaluated again.

### Input and HUD in the HDR window

The HDR window has focus, so the AWT dispatcher sees nothing; each bound mpv key is
forwarded once. Esc/Dismiss/Go Back (Backspace) = Back (leave playback, ordinary
progress flush and navigation); app fullscreen key (default F11), double click,
window close, Open Sources/Episodes = leave the HDR session and continue in the
embedded player with its full WebKit HUD; Space/K/Enter/right click play-pause;
←/→/J/L seek; ↑/↓/wheel volume; M mute; S/A subtitle/audio cycle; speed keys;
C zoom; Tab skip interval; F8 cycles HDR mode; I toggles mpv's stats overlay (shows
the negotiated target). SVP, colour profile and Anime4K shortcuts report "not
available" (SDR-only features). Media keys are left to MPRIS. Pills are shown with
mpv `show-text`. Escape while the session is still starting cancels it and goes
back.

### SDR path unchanged — evidence

- `linux_hdr_session_test` asserts `linuxX11EmbeddingOptions()` equals the literal
  pre-change option list; `create` now uses the same helper steps in the same order.
- `player_bridge` NEEDED libraries are unchanged (no Wayland/Vulkan link); new pump
  cases and close branch are guarded by `hdrSession`.
- Linux keeps `applyLinuxBaseVideoProfile`; the shared `applyDesktopVideoProfile`
  only had its HDR-mode table extracted (`desktopHdrTargetMpvProperties`, values and
  order pinned by `LinuxHdrSessionPolicyTest`).
- All existing Linux desktop tests and native fixtures pass unchanged.

### Packaging (implemented, CI rebuild pending)

All three formats carry the same canonical `lib/native/nuvio-hdr-probe` next to
`libplayer_bridge.so`; the OSC script ships inside the application JAR
(`player-scripts/linux-hdr-osc.lua`).

| Step | Implementation |
| --- | --- |
| Build inputs | The packaging builder (Ubuntu 22.04) has wayland-protocols/scanner older than 1.41/1.24. The media runtime therefore exports a **build-only SDK area** next to its existing `include/mpv` and `bin/` (never staged): the pinned `bin/wayland-scanner` (Wayland 1.24.0), `share/wayland-protocols/staging/color-management/color-management-v1.xml` (wayland-protocols 1.42, manifest `artifact_contract.build_data`) and the 34 Vulkan-Headers 1.4.363.0 C headers under `include/vulkan`/`include/vk_video` (listed one by one in the manifest). Only the builder's wayland-client 1.20 development interface comes from Ubuntu; generated code and `-z defs` linking are checked against it. |
| Media audit | `layout_errors` accepts exactly the listed headers/build data. The scanner's builder `libexpat.so.1` is allowed only through `build_tool_needed` for `bin/wayland-scanner`; the same NEEDED on any other runtime ELF still fails (new `tests/test-runtime.py` mutations: missing header/XML, unlisted protocol XML, extra scanner dependency, `libexpat` on libmpv and on `bin/mpv`). |
| Canonical build | `native-build.sh` passes `-DNUVIO_WAYLAND_SCANNER=/media/bin/wayland-scanner`, `-DNUVIO_WAYLAND_PROTOCOLS_DIR=/media/share/wayland-protocols`, `-DNUVIO_VULKAN_INCLUDE_DIR=/media/include`, `-DNUVIO_VULKAN_LIBRARY=/media/lib/libvulkan.so`, builds `nuvio-hdr-probe` and `linux_hdr_session_test`, and runs `linux_hdr_session_test --expect-wayland=yes` (display variables unset) after the existing native fixtures. CMake makes the probe **required** when `NUVIO_PACKAGE_BUILD=ON`, so a package cannot silently omit it. |
| Staging | `stage.py` copies the probe into `lib/native` and removes `build_data` (and its empty parents) after copying the media `share/`; bin/include/pkg-config were already never staged. |
| Image gate | `verify.check_image` (DEB extraction, AppImage payload, canonical image) calls `check_hdr_probe`: executable ELF, RUNPATH exactly `$ORIGIN:$ORIGIN/../runtime/lib` (same as the bridge), NEEDED ⊆ {libwayland-client.so.0, libvulkan.so.1, libstdc++, libgcc_s, libc, libm} with both private libraries present, and — with `LD_LIBRARY_PATH` removed — `libwayland-client.so.0`/`libvulkan.so.1` resolving inside the image's `lib/native`. No `share/wayland-protocols`, `bin/wayland-scanner` or `include/vulkan`/`include/vk_video` may appear. The generic ELF loop (ABI floor, ORIGIN-only RUNPATH, closure) applies as well. |
| AppImage | No extra staging: the probe is not in the WebKit stage's copied set, keeps its RUNPATH, and its in-image dependencies are skipped by the `linked_paths` closure walk. |
| Flatpak | The sandbox closure check requires the probe to be executable and `ldd`s it with the shared libraries (the private Vulkan loader is removed, so `libvulkan.so.1` comes from GNOME Platform as for libmpv). The exported bundle is checked for an executable regular `lib/native/nuvio-hdr-probe` (`flatpak_hdr_probe`). `--socket=wayland` is added **alongside** `--socket=x11`; `GDK_BACKEND=x11` is unchanged, so JAWT/Canvas, GTK/WebKit HUD and the embedded player stay on X11/XWayland. With the wayland socket the sandbox now sees `WAYLAND_DISPLAY` (as DEB/AppImage already do); in application code its only other consumer is the X11 idle fallback, which already stays disabled in Wayland sessions via `XDG_SESSION_TYPE`. Third-party library reactions inside the Flatpak (GNOME WebKitGTK/Mesa) have not been observed and need owner acceptance. |
| Notices | The probe incorporates generated color-management-v1 code, Vulkan header inline code and libwayland client headers; their notices (`share/licenses/wayland-protocols`, `Vulkan-Headers`, `wayland`) already ship from the media runtime and are unchanged. `compliance/inventory.py` attributes the probe to the application (the source review index was regenerated for that script binding only). |

CI implications: the media recipe hash changes (new runtime generation required), so
the packaging builder identity and the immutable WebKit builder seed change; the
AppImage assembly identity also changes (`verify.py`). A new bootstrap/seed export
and a WebKit engine rebuild/import are expected before an all-format run passes.
The bootstrap (`build.py --format deb`) now applies the same Linux source policy
(`source_policy.py`: artwork overlay, runtime-downloaded anime dataset) as
`canonical_from_seed.py`, so its artwork gate passes and the seed can be exported.
Changing `build.py` is part of this seed refresh. The release source step binds the Ubuntu source lock to
the bundled packages' exact versions rather than to the engine builder image ID,
so the rebuilt engine builder needs no lock regeneration while the pinned
snapshot is unchanged.

### Enabling the experimental session

Default **off**. In a package built from this branch, open Settings → Playback and
enable **Experimental HDR (Wayland)** (shown under HDR mode, Linux only, stored per
machine). Keep HDR mode on Auto or Always Passthrough. Nothing changes unless every
gate above holds; otherwise the embedded X11 player is used exactly as before.

### Known limitations / unverified

- **Unverified:** actual HDR10 output, compositor/monitor HDR signalling, HDR
  metadata, brightness of OSD/subtitles in PQ, NVIDIA/AMD/Intel WSI colour-space
  behaviour, multi-monitor placement, and whether mpv renders and reports
  `video-target-params` while paused at start on real drivers (if not, the session
  times out and falls back to SDR).
- The fixture keypress/OSC tests run real libmpv with null outputs only.
- No WebKit HUD in the session (no panels, thumbnails, PiP). The embedded player
  is torn down during the session; leaving re-creates it (brief re-buffer).
- The prototype capability policy compared raw wire numbers and treated value 9
  (sRGB in `wp_color_manager_v1`) as PQ (11); this implementation uses generated
  enum names and pins the case in `LinuxHdrSessionPolicyTest`.

### Owner acceptance commands (HDR display, explicit authorization only)

Never run these against a live session without deciding to: they open Wayland
connections and, for the app run, a fullscreen window.

```sh
# 0. Headless checks (safe anywhere). Packages run the same test in native-build.sh.
unset DISPLAY WAYLAND_DISPLAY NUVIO_RUN_LIVE_DISPLAY_TESTS
runtime=build/linux-media-runtime/runtime   # this branch's media recipe
cmake -S composeApp/src/desktopMain/native/linux -B build/hdr-native -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DMPV_ROOT="$PWD/$runtime" \
  -DNUVIO_WAYLAND_SCANNER="$PWD/$runtime/bin/wayland-scanner" \
  -DNUVIO_WAYLAND_PROTOCOLS_DIR="$PWD/$runtime/share/wayland-protocols" \
  -DNUVIO_VULKAN_INCLUDE_DIR="$PWD/$runtime/include"
cmake --build build/hdr-native --target player_bridge nuvio-hdr-probe linux_hdr_session_test
build/hdr-native/linux_hdr_session_test --expect-wayland=yes
# Package static gate (any extracted DEB / AppImage payload; never runs the app):
python3 -c 'import sys; sys.path.insert(0, "tools/linux/packaging"); import verify, pathlib; \
  print(verify.check_hdr_probe(pathlib.Path(sys.argv[1])))' /path/to/opt/nuvio-desktop
# Flatpak: the installed app's permissions must list both sockets.
flatpak info --show-permissions com.nuvio.media.desktop | grep -E '^sockets='

# 1. Read-only capability snapshot in the real session (unmapped surface only).
#    Use the packaged helper: /opt/nuvio-desktop/lib/native/nuvio-hdr-probe (DEB),
#    `flatpak run --command=/app/lib/nuvio-desktop/lib/native/nuvio-hdr-probe
#    com.nuvio.media.desktop --probe-json` (Flatpak), or build/hdr-native/ (source).
timeout --signal=TERM --kill-after=2s 20s \
  build/hdr-native/nuvio-hdr-probe --probe-json | python3 -m json.tool
#    Expect probe_complete=true, preferred st2084_pq/bt2020 with max_nits > reference_nits,
#    and exactly one device with hdr10_10bit, wayland_present and hdr_metadata.

# 2. Application run: enable Settings > Playback > "Experimental HDR (Wayland)",
#    HDR mode Auto, system HDR on, then play licensed HDR10 PQ test material.
#    Check nuvio.log for "Experimental HDR session active (PQ/HLG BT.2020 target
#    confirmed by mpv)"; press I for mpv stats (target transfer/primaries);
#    confirm the monitor's own HDR indicator / compositor HDR state.
# 3. Exercise: Space, ←/→, ↑/↓, S/A, Esc (back to app, Continue Watching position),
#    F11 and double click (embedded player resumes at the same position), gamepad
#    A/D-pad/B, F8 to Always Tonemap (falls back), toggling system HDR off mid-play
#    (falls back), and an SDR-only display (never selected, or falls back).
```

Record driver/kernel/compositor/GPU/display identifiers and package hashes with
each result, as required in "Tests and acceptance required" below.

## Original audit (2026-10-13)

Audit date: 2026-10-13. Application baseline:
`a96ce242f084a41ecf433c206958f1cbffa53345`. Actual package evidence comes from
[run 38397205286](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38397205286),
whose application commit is `fce98dc1633b51f813c20965e05941c2745beb8c`.
The checked player/runtime sources agree on the boundaries below. This is source
and artifact inspection, not a new display test. No compositor, GPU, display
setting, installed application or personal profile was accessed or changed.

**Runtime update (2026-10-14):** the media recipe now also builds a
Wayland-capable libmpv (milestone 2 below). It adds pinned source builds of
Wayland 1.24.0 (client/cursor), wayland-protocols 1.42, libxkbcommon 1.13.1 and
build-only Bison 3.8.2, and only `-Dwayland=enabled` for mpv; X11, Vulkan,
libplacebo, `-Dgl=disabled` and both mpv patches are unchanged. The runtime
audit fails closed unless `HAVE_WAYLAND`, `HAVE_WAYLAND_PROTOCOLS_1_41` and
`HAVE_X11` are set, the CLI and a headless libmpv option probe accept both
`x11vk` and `waylandvk`, and libmpv contains the color-management-v1 client.
The player still selects `x11vk` with the Canvas XID, so the SDR path is
unchanged. **HDR10 output remains unimplemented and unproven on hardware**;
the evidence below describes the earlier packaged runtime and still applies to
the application.

## What counts as HDR

These are separate capabilities:

1. Decode HDR source samples and read their mastering/transfer metadata.
2. Map those samples to a correctly described SDR target.
3. Deliver a correctly described high-dynamic-range surface to the compositor,
   with an actual HDR display-output path enabled and accepted by the monitor.

Only the third satisfies this milestone. A 10-bit decoder, BT.2020 source tag,
Vulkan renderer, HDR badge, bright image or enabled GNOME HDR switch does not
prove that this application's video reaches the monitor as HDR10. A desktop can
output an HDR signal while Nuvio contributes only SDR content inside it.

## Confirmed implementation boundary

| Layer | Actual implementation | Consequence |
| --- | --- | --- |
| Compose/AWT host | `NativePlayerHost : Canvas`; Swing interop; `AwtNativeViewResolver` resolves the platform native pointer. | Current Linux host is an X11 drawable, including in a Wayland desktop session. |
| JNI create | Linux `player_bridge.cpp`, `JNI_METHOD(create)`, validates a nonzero 32-bit XID, calls `reserveX11Lifetime()` and `LinuxAwtX11Lifecycle::prepare()`, then enforces `vo=gpu-next`, `gpu-api=vulkan`, `gpu-context=x11vk`, `wid=<Canvas XID>`. | Saved mpv options cannot switch this embedding boundary into native Wayland. |
| Runtime features | Actual packaged `share/nuvio-media-runtime/config/mpv.h`: `HAVE_VULKAN=1`, `HAVE_X11=1`, **`HAVE_WAYLAND=0`**, `HAVE_GL=0`, `HAVE_DMABUF_WAYLAND=0`. | `waylandvk` is not even compiled into this packaged mpv. The Vulkan loader's Wayland support does not change this. |
| Build recipe | `tools/linux/media-runtime/manifest.json`: mpv 0.41.0, `-Dauto_features=disabled`, explicit X11/Vulkan enablement, no Wayland enablement. libplacebo 7.360.1 enables Vulkan and disables OpenGL. | Native Wayland requires a new pinned media recipe/build and validation, not a one-line player option. |
| Source detection | `Player::reportVideoParams()` reads `video-params/gamma` and `primaries`; PQ/HLG or BT.2020 are conservatively treated as HDR-like for grading. | This is source classification, not display probing. BT.2020 alone also does not establish HDR10. |
| Linux picture policy | `PlayerEngine.desktop.kt` takes its Linux branch and returns before `applyDesktopVideoProfile()`. `applyLinuxBaseVideoProfile()` grades only confirmed SDR and leaves HDR/unknown neutral. | The shared HDR Auto/Passthrough/Tonemap profile modes are deliberately not the Linux output contract. |
| HUD | `controls_overlay.cpp` restricts GDK to `x11`, requires an X11 display/RGBA visual/root matching the Canvas, and positions an unfocused GTK/WebKit window with X11 geometry. | The working transparent HUD cannot simply be reparented onto an arbitrary Wayland surface. |
| Lifetime/PiP | `NativePlayerController` serializes native lifetime and uses `LinuxPlayerOwnership`; `NativePlayerHost.removeNotify()` disposes before peer removal; native `reserveX11Lifetime()` protects global X11/JAWT state. Main and the Linux PiP coordinator own AWT placement/bounds. | An alternate window path must retain these guarantees and coordinate navigation, focus and PiP explicitly. |

Relevant application sources:
[native player](../composeApp/src/desktopMain/native/linux/player_bridge.cpp),
[X11 lifetime guard](../composeApp/src/desktopMain/native/linux/awt_x11_lifecycle.h),
[host](../composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerHost.kt),
[controller](../composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt),
[engine/profile dispatch](../composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/PlayerEngine.desktop.kt),
[Linux picture profile](../composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/LinuxBaseVideoProfile.kt),
[HUD](../composeApp/src/desktopMain/native/linux/controls_overlay.cpp).

Windows HDR UI compensation is separately gated by `DesktopHostOs.WINDOWS` in
[`DesktopHdrUiCompensation.kt`](../composeApp/src/desktopMain/kotlin/com/nuvio/app/core/ui/DesktopHdrUiCompensation.kt).
It adjusts SDR UI fades for Windows composition. It is neither Linux output
negotiation nor a candidate workaround. RTX Video HDR is also a separate
Windows/D3D11 facility.

## Pinned mpv/libplacebo evidence

The existing source cache was read as data and its bytes independently hashed
against the media manifest before inspection; nothing was compiled or executed:

| Source | SHA-256 |
| --- | --- |
| [mpv v0.41.0 archive](https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz) | `ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209` |
| [libplacebo v7.360.1 archive](https://code.videolan.org/videolan/libplacebo/-/archive/v7.360.1/libplacebo-v7.360.1.tar.bz2) | `937aa5eeea596798b3274d362de2e3bd32bc537a66d149dd85043349c74dffb6` |

The two packaged mpv patches affect decoder selection and CUDA mapper failure
cleanup, not the output code examined here. Preserve both and all existing
source checksums in any future recipe revision.

The exact source trace is:

- mpv `video/out/vulkan/context_xlib.c:xlib_init()` creates a `VkXlibSurfaceKHR`
  for mpv's X11 child. Its `ra_ctx_params` has no `preferred_csp` callback.
  `video/out/vulkan/context.c:target_csp()` therefore returns an unknown color
  space for this backend.
- mpv `video/out/vo_gpu_next.c` defaults `target-colorspace-hint` to `auto`.
  Its frame path enables that hint automatically only when the target transfer
  function is known. The unknown-target internal HDR assumption does **not**
  turn on automatic HDR hinting. Current X11 defaults therefore do not establish
  an HDR display target.
- libplacebo `src/vulkan/swapchain.c:pl_vulkan_create_swapchain()` starts with an
  sRGB hint. `pick_surf_format()` selects among the actual WSI-supported formats;
  `map_color_space()` maps the standard sRGB surface to BT.709/sRGB and an
  HDR10-ST2084 surface to BT.2020/PQ. `set_hdr_metadata()` sends metadata only
  when the function is supported and the swapchain is HDR. A 10-bit format alone
  does not substitute for the selected color space.
- mpv `video/out/vulkan/context_wayland.c:wayland_vk_init()` creates a real
  `VkWaylandSurfaceKHR` and supplies `vo_wayland_preferred_csp()` as the preferred
  target callback. `wayland_common.c` reads compositor color-management feedback
  and responds to preferred-description changes. These are different ownership
  and window-system paths from `wid`/X11.
- mpv `video/out/meson.build` includes color-management protocol code only with
  wayland-protocols **at least 1.41**. Baseline Wayland build dependencies in
  `meson.build` include wayland-client/cursor >=1.21, wayland-protocols >=1.31,
  xkbcommon and wayland-scanner. Enabling Wayland against the older minimum
  alone is insufficient for this HDR feedback path.
- In this pinned mpv, `wayland_common.c` binds its own color surface only for
  `dmabuf-wayland`, explicitly avoiding ownership conflicts with graphics
  drivers. For `gpu-next`/Wayland Vulkan, the WSI driver must carry the selected
  swapchain color space into compositor signaling. Reading preferred feedback
  is insufficient if the driver cannot present the required color space.
- `wayland_common.c:create_xdg_surface()` creates mpv's own `xdg_toplevel`.
  There is no supported conversion from the existing Canvas XID to a Wayland
  parent. mpv's `--wid` X11 semantics create a child of an X11 window; they are
  not a cross-protocol parenting interface.
- `include/mpv/render.h` exposes OpenGL and software render APIs, not a public
  Vulkan/gpu-next texture render API. Replacing `wid` with an imagined Vulkan
  render callback is not a supported small adaptation. The packaged OpenGL
  backend is disabled in any case.

Pinned upstream code references:
[Xlib Vulkan context](https://github.com/mpv-player/mpv/blob/v0.41.0/video/out/vulkan/context_xlib.c),
[Wayland Vulkan context](https://github.com/mpv-player/mpv/blob/v0.41.0/video/out/vulkan/context_wayland.c),
[gpu-next](https://github.com/mpv-player/mpv/blob/v0.41.0/video/out/vo_gpu_next.c),
[Wayland common](https://github.com/mpv-player/mpv/blob/v0.41.0/video/out/wayland_common.c),
[render API](https://github.com/mpv-player/mpv/blob/v0.41.0/include/mpv/render.h),
[libplacebo swapchain](https://code.videolan.org/videolan/libplacebo/-/blob/v7.360.1/src/vulkan/swapchain.c).

**Conclusion for this application:** the current embedded X11/XWayland path has
no established automatic HDR capability/signaling route. Treat it as the SDR
output baseline. Forcing `target-trc=pq`, `target-prim=bt.2020`, or
`target-colorspace-hint=yes` would not prove an HDR connection to GNOME or the
monitor and is not a fix. This conclusion is scoped to the pinned application
and normal GNOME path; it is not a claim that no vendor-specific XWayland bridge,
Gamescope environment or future compositor could ever support HDR.

With ordinary defaults, gpu-next/libplacebo maps source content to its SDR
swapchain. Nuvio's conservative grading leaves that processing neutral for
HDR/unknown sources. This is an existing fallback mechanism, **not new visual
acceptance of every HDR source**. User Replace/Full mpv configuration remains
authoritative; arbitrary output overrides cannot be represented as validated
automatic fallback. Neutral/Cinematic/Vivid/Custom SDR controls and shader HDR
guards must remain intact.

## Host requirements and what they do not prove

GNOME 48 introduced system HDR controls; GNOME 50 documents version 2 of the
Wayland color-management protocol. These are compositor capabilities, not proof
that the installed Bazzite session, GPU, connector or Nuvio has negotiated them.
Do not use the desktop name/version as the sole runtime capability test.
[GNOME 48](https://release.gnome.org/48/),
[GNOME 50](https://release.gnome.org/50/).

Wayland color management describes both a surface's content and the compositor's
preferred target, including multi-monitor treatment. The compositor remains
responsible for presentation to its outputs. A renderer must process capability
and preferred-description changes rather than hardcode a monitor's peak or
force PQ when HDR is disabled.
[Wayland color-management design](https://wayland.freedesktop.org/docs/book/Color.html).

Vulkan `VK_COLOR_SPACE_HDR10_ST2084_EXT` describes BT.2020/PQ samples. HDR metadata
and pixel color encoding are separate contracts: `VK_EXT_hdr_metadata` supplies
mastering/content light information but does not choose the color space or
guarantee how the presentation engine uses it. The actual surface format/color
space and compositor output are acceptance evidence, not the presence of an
extension name alone.
[Khronos color-space specification](https://docs.vulkan.org/refpages/latest/refpages/source/VkColorSpaceKHR.html),
[HDR metadata specification](https://github.khronos.org/Vulkan-Site/refpages/latest/refpages/source/VK_EXT_hdr_metadata.html).

NVIDIA documents DRM connector HDR metadata/colorspace support beginning in the
545.29.02 release when DRM modesetting is enabled. That historical fact is **not**
a sufficient driver floor for this future Wayland Vulkan integration. The exact
installed driver, kernel modesetting path, GPU/display/connector combination,
compositor and Vulkan WSI color-space behavior require joint acceptance. The
repository's 530.41.03 NVDEC interface floor is not an HDR claim. Do not change
kernel modules, install drivers or assume open/proprietary module flavor proves
the presentation path.
[NVIDIA 545.29.02 release notes](https://www.nvidia.com/en-us/drivers/details/215001/).

The bundled Temurin 17/AWT X11 interface cannot become a native Wayland embedding
by changing `GDK_BACKEND`. OpenJDK treats native Wayland as separate toolkit work;
newer Wakefield/JetBrains runtime support does not establish compatibility for
this project's pinned Java/Skiko/JAWT stack.
[OpenJDK native Wayland prototype](https://wiki.openjdk.org/spaces/flyingpdf/pdfpageexport.action?pageId=77693134).

## Architecture choices

| Approach | Decision |
| --- | --- |
| Set PQ/BT.2020 or `waylandvk` on today's Canvas | Reject: missing compiled backend, wrong native handle contract and no proven presentation signaling. |
| Add a global Vulkan HDR layer, Gamescope or compositor override | Reject as an implicit app fix: external architecture/dependency, unsupported embedding/input assumptions, no universal GNOME contract. |
| Direct DRM/KMS ownership | Reject for this desktop milestone: competes with compositor/session ownership and does not solve shared UI integration. |
| Copy decoded frames into an SDR Compose texture | Reject as HDR proof: loses the required output contract even if a 10-bit source decodes. |
| Introduce a native Wayland playback presentation boundary | Feasible direction to investigate, but substantial and separately reviewed. Keep existing controller/recovery/player state and the X11 fallback. |
| Rebuild the entire Compose/JDK rendering stack for native Wayland/HDR | Architectural alternative, not justified for this bounded mission. |

The narrowest **research** prototype is a disposable native Wayland libmpv
presentation fixture with the same pinned mpv/libplacebo versions and a reviewed
Wayland-enabled media recipe. Its purpose would be to prove negotiation and
output on authorized hardware, not to replace the integrated player with an
external mpv process or ship a second incomplete player. No such prototype was
built or run in this audit.

A production proposal must resolve all of these before implementation approval:

- **Window owner:** define one native Wayland toplevel/subsurface hierarchy and
  legal parent/role ownership. If mpv owns a toplevel, Kotlin must coordinate its
  lifecycle explicitly; do not pretend it is embedded in the existing Canvas.
  A toolkit-owned surface needs an actual supported embedding interface or a
  narrowly reviewed upstream change, not an integer pointer substitution.
- **HUD:** reuse current controls assets/messages and sandboxed WebKit processes,
  but design Wayland surface ordering, transparency, clipping and scale. The
  current unfocused X11 overlay/absolute-coordinate placement cannot be assumed
  to work across separate Wayland connections. UI/subtitle white levels must be
  described correctly relative to HDR video; do not brighten the entire HUD as
  PQ or blend SDR alpha in the wrong transfer function.
- **Input/focus:** route native seat keyboard/pointer events into the existing
  player action policy exactly once. Preserve Escape modal/toast/back ordering,
  gamepad, OSK, seeking and focus return. The existing AWT dispatcher cannot see
  every key directed to a new native toplevel. Do not enable conflicting mpv
  default bindings to conceal this gap.
- **Lifetime:** retain controller generation cancellation and full-player/hero
  priority. No overlap of old/new native owners; stop outgoing rendering and
  callbacks before destroying its surface. Do not wait while holding lifecycle
  locks needed by disposal. Keep `reserveX11Lifetime()` on every X11 fallback
  lifetime, even if a future Wayland implementation uses a different guard.
- **Fullscreen/PiP:** define which window receives fullscreen and close requests,
  save/restore placement once, and preserve playback progress/navigation intent.
  PiP reparenting/always-on-top/global movement is compositor policy on Wayland;
  preserve the accepted X11 PiP fallback until a truthful equivalent exists.
- **Monitor changes:** consume surface-preferred feedback, not only primary
  monitor EDID. Atomically change render target and signaling on mixed displays,
  hotplug and HDR toggles. Reject stale asynchronous feedback by presentation
  generation; never leave PQ pixels tagged as SDR during fallback.
- **Packaging:** add pinned Wayland/protocol sources and exact compiled-feature
  verification through the existing media pipeline. Review each format's
  socket/library/runtime requirements and source closure. No GPU ICD bundling,
  sandbox disablement or assumption that DEB/AppImage/Flatpak host stacks match.

## Bounded follow-up milestones

1. **Review this design and authorize an isolated capability fixture.** Decide
   the surface/HUD integration contract first. No application default changes.
2. **Prepare a Wayland-enabled pinned media recipe separately.** Preserve mpv
   decoder patches and fallback; include >=1.41 protocol generation and exact
   library/build/source provenance. *Done in the media recipe (2026-10-14,
   local build only); packaged artifacts still need a CI rebuild.*
3. **Prove actual presentation on authorized Bazzite GNOME/NVIDIA/HDR hardware.**
   Query the created surface's available/selected formats and preferred feedback;
   establish compositor/monitor HDR evidence. Abort the feature if this fails.
4. **Only then implement the adapter and shared-controller integration.** Bound
   scope to full-player presentation first. Keep SDR X11/hero/PiP operational;
   explicitly represent unsupported transitions until accepted rather than
   silently dropping controls or changing user preferences.
5. **Review independent package and hardware acceptance before enabling it.**
   Use a separate Draft PR for renderer code. Documentation status remains
   `LINUX_MISSING` for G04 until there is an implementation with evidence.

## Tests and acceptance required

Headless policy tests should use fake capability/feedback snapshots and existing
controller fakes, exercising production selection logic:

- missing Wayland/backend/protocol/HDR format, SDR target, HDR disabled and
  unknown capabilities all choose SDR without setting PQ;
- HDR10 requires compatible source/target signaling and actual negotiated
  format; unsupported/failed negotiation returns to SDR coherently;
- mixed-display/preferred-feedback changes invalidate stale generations;
- one color conversion owner, consistent transfer/metadata, no accidental
  inverse tone mapping or duplicate tone mapping;
- source change, detach, fullscreen and PiP preserve one owner, progress,
  callback ordering, cleanup and fallback;
- existing Linux SDR grading and custom mpv ownership remain unchanged;
- Escape/modal/gamepad/OSK dispatch reaches the shared policy exactly once;
- package static checks verify compiled Wayland/color features, runtime closure,
  retained X11 backend and corresponding sources.

Existing `LinuxBaseConfigurationTest`, `LinuxShaderProfileTest`,
`LinuxShaderPolicyTest`, ownership/lifecycle/PiP fixtures and player key-dispatch
tests cover useful regression boundaries; they do not prove HDR output. The live
`LinuxPlayerRenderingProbeTest` must remain explicitly opt-in. This design audit
did not run any tests, binaries or live display probe.

On disposable profiles and explicitly authorized physical hardware, retain a
matrix of driver/kernel/compositor/GPU/connector/display identifiers and package
hashes. For each test record source metadata, negotiated target transfer/gamut/
format, compositor output state and monitor HDR indication (or equivalent
authoritative output evidence), not merely screenshots:

1. Known HDR10 PQ/BT.2020 test material with legal redistribution rights;
   black/near-black, highlight and gradient patterns without clipping surprises.
2. SDR material before/after HDR, Neutral/Cinematic/Vivid and subtitles/HUD.
3. Windowed/fullscreen, seek/rapid seek, source/episode switch, pause/end/exit.
4. HDR disabled/enabled, move between HDR and SDR outputs, span outputs and
   hotplug; no incorrectly tagged PQ or lost video.
5. PiP transitions, Escape, gamepad controls, focus return and teardown.
6. DEB/AppImage and Flatpak independently on the selected host driver stack.

The remaining dependency is specific: a reviewed native surface/HUD contract,
an authorized pinned media build, and compatible physical display-stack testing.
No passing source/unit test can replace these. Keep the accepted SDR renderer
and do not present optional HDR architecture work as a prerequisite for solving
the unrelated redistribution evidence gaps.
