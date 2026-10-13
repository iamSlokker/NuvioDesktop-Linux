# Linux HDR10: source audit and bounded design proposal

**Assessment: BLOCKED as an optional platform milestone; design ready for review.**
The accepted Linux player remains unchanged. Genuine HDR10 output is not implemented
or hardware-accepted. This does not by itself block redistribution of a correctly
described SDR player; the separate licensing and source blockers still apply.

Audit date: 2026-10-13. Application baseline:
`a96ce242f084a41ecf433c206958f1cbffa53345`. Actual package evidence comes from
[run 38397205286](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38397205286),
whose application commit is `fce98dc1633b51f813c20965e05941c2745beb8c`.
The checked player/runtime sources agree on the boundaries below. This is source
and artifact inspection, not a new display test. No compositor, GPU, display
setting, installed application or personal profile was accessed or changed.

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
   library/build/source provenance. This requires a new binary build, which was
   not authorized as a speculative change in this mission.
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
