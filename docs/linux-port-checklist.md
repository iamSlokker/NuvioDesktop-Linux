# Linux port checklist

Baseline: `upstream/linux` @ `57629923` (the `NuvioDesktop-Linux-linux.zip` source is identical).

The old branch `feature/linux-1.36-goals` is rooted at `1ad68129`, a squashed 1.27.0 snapshot. It has no common ancestor with `upstream/linux`, so this branch is a fresh port, not a rebase.

## Local commits to account for

These are the commits on `feature/linux-1.36-goals` after the 1.27.0 snapshot. Each one must be represented by a ported commit or explicitly dropped.

- [ ] `c4aec2dd` theirs: the 1.32.0 Linux snapshot
- [ ] `1995eb67` upstream: newer UmbraProjects snapshot (2026-11-05)
- [ ] `b60f7da7` Linux: keyboard navigation for the sidebar, on the 1.32.0 nav bar entries
- [ ] `65ee7539` Linux TV Mode: stacked Home rows, the clipped band and the hero layout
- [ ] `6cfd205e` Dithered scrim: resample a dense ramp into the stop cap instead of refusing it
- [ ] `0aa454f7` Linux playback: HUD D-pad navigation, panel focus and the volume control
- [ ] `458f35f0` Linux settings: keyboard navigation of the settings pages (parked)
- [ ] `23c0b671` Honor the bound Dismiss key in the context menu and the first-run wizard
- [ ] `9fe4c50e` HDR probe: initialize the listeners it was missing
- [ ] `963d6504` Linux: pack a development AppImage from the JVM app image
- [ ] `3d714960` Linux: per-tab keyboard handoff, settings navigation (step 1) and the bar's way back
- [ ] `44cce04d` Linux settings: keyboard navigation of the dropdown and multi-select menus
- [ ] `6f75a0b1` Linux settings: Back and arrow focus reach the page and the sidebar
- [ ] `02d163cb` nav-checkpoint: preserve settings keyboard/gamepad navigation WIP (stages 1-2) and merge artifacts
- [ ] `10181274` nav: rebindable directional navigation + search/focus fixes
- [ ] `531bed2f` nav: expand/collapse indicators, hold-navigation smoothness, 2-option toggles
- [ ] `adbc3329` incoming: developer source snapshot 2026-11-07 (NuvioDesktop-Linux-linux.zip)
- [ ] `9bf23e0c` Merge branch 'incoming-20261107' into integrated-20261107
- [ ] `2afde1ab` linux: restore native libmpv playback and controller input
- [ ] `b0ef6f95` settings: unify focus navigation, Back, search and reachability
- [ ] `be83ebc1` ui: hover/focus zoom, TV hero credits and navigation polish
- [ ] `7c97a4bb` integrations: sync upstream v1.34 source and platform features
- [ ] `d8520442` ux: screen-scoped voice search, YouTube player strip and PiP focus
- [ ] `dbe3b179` release: 1.35.0
- [ ] `a6214a12` WIP: local working state with YouTube mini-player nav icon
- [x] `34d54997` auth: overhaul login screen and fix remote/gamepad navigation
- [x] `42f67aaf` docs: add Linux port audit

## Port slices

- [x] Linux input / gamepad / media keys / OSK integration — `dabbe260`
- [x] Linux player / native bridge / HUD — `6e677db4`
- [x] Build / packaging / native runtime — `063326ed` (scripts only; no packages produced)
- [x] Navigation / focus / shared UI — `afc1a07d`
- [x] Auth / login / QR — `f60632ba`
- [x] Home / TV rows / navbar — `5eb00420`
- [x] Settings — `3d47179b`
- [x] Music / MPRIS UI — `fbf5ba66` (MPRIS files are in `dabbe260`)
- [x] Backup / Google Drive — ported as a settings dependency (`3d47179b`)
- [x] Other app features — `deacfdc9`
- [x] Tests (ported with each slice)
- [x] Remove patch files and document the port

## Final verification

- `./gradlew :composeApp:compileKotlinDesktop` passes.
- `./gradlew :composeApp:desktopTest` runs 6131 tests: 7 failures, 78 skipped.
- The 7 failures are pre-existing game/install tests that also fail on the old branch:
  - `GameLauncherTest`
  - `GameLaunchTargetTest`
  - `SteamArtworkTest`
  - `InstallManifestMigrationTest`
- The old local history patches (`docs/history/patches-1.32/*.patch`, `v1.36-to-1.37.patch`) were removed from `feature/linux-1.36-goals`. They were never part of `linux-clean`.
- Upstream build patches under `composeApp/src/desktopMain/native/` and `tools/linux/` remain; they are build inputs, not port history.

## YouTube mini-player

- The corner-page wiring in `App.kt` is ported (`23281fc7`), including the mini tablet layout, nav bar handoff, and top-chrome padding.

## Local-only file counts from the audit

- Settings — 42
- Home / TV rows / navbar — 21
- Auth / login / QR — 20
- Docs / patches / handover — 15
- Linux input / gamepad / OSK / media keys — 15
- Navigation / focus / shared UI — 14
- Linux player / native bridge / HUD — 13
- Build / packaging / repo — 10
- Other app features — 7
- Backup / Google Drive — 4
- Music — 3
- Desktop platform / diagnostics — 2
- Player / YouTube shared — 1

The full three-way audit is in `docs/linux-port-audit.md` on `feature/linux-1.36-goals`.

## Known pre-existing test failures

The three `linux-hud-volume-nav.test.cjs` cases fail identically on the old branch. They are not introduced by the port.

