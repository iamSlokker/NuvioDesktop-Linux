# Curated upstream integration, 2026-10-12

## Scope and source revisions

This integration targets `sync/nuvio-dev-2026-10-12`, based on Linux commit
`388c0476e5c54336787be5c1b73d526356f8cb4e`. It does not update the stable
`linux` branch or claim to merge the complete official Dev history.

| Source | Audited revision | Result |
| --- | --- | --- |
| UmbraProjects/NuvioDesktop, `windows-tv-adaptive` | `a70cc3700570555abb3f165548a67dec7c7a5430` | Already an ancestor of the Linux baseline; no additional merge required. |
| NuvioMedia/NuvioDesktop, `Dev` | `d2e21f20782f4516b62bbdcb4764d6271db1d614` | Selected compatible fixes backported; architectural changes deferred below. |

The official Dev merge base is `ee5c92309172c5225abcc6ce29d43fa653972d23`.
There are 1,406 official non-merge commits beyond the Linux baseline's ancestry.
A full merge would combine different navigation, player, backend and dependency
architectures. Traceable cherry-picks and small adaptations preserve the HTPC
fork rather than treating the official tree as a replacement.

## Integrated shared fixes

| Official commit | Change | Fork adaptation / regression boundary |
| --- | --- | --- |
| `a255680b0bbbda1bfae1d05eb78094b9ac44cec7` | Atomic collection-tab updates | Preserve both concurrent completions using `MutableStateFlow.update`; also make the All-tab rebuild atomic after a deterministic interleaving proved it could undo the upstream fix. Exercise both actual repository methods with synchronized threads. |
| `4b6a15c950cd799cb3ca177c2c2c926dec79db39` | Trakt watched/progress precedence | Normalize snapshots and compare completion timestamps with the upstream one-second tolerance; retain this fork's continue-watching policies. |
| `5327166faf575000c0f181a542badd0dbd97b1cd` | Refresh profiles before caching an online-verified PIN | Cache against the refreshed profile version; test online RPC ordering and subsequent offline unlock using isolated storage and localhost HTTP. |
| `c388bd45d28575add42d399e0415f9ff6fa6123e` | Preserve stream loading during external-player preparation | Use the existing route-owned scope and cancel stream loading after preparation/dispatch; keep the fork's subtitle forwarding and platform launch adapters. |
| `27c646eafab5615df8e03cf142d6111abb62e2ba` | Debrid sign-in survives transient polling exceptions | Retain cancellation and response failures; upstream monotonic watchdog bounds persistent throwing requests. Dialog interaction still needs GUI acceptance. |
| `319fb564e40bd8ea25a5cf9b72a071b7728fc1dc` | Collection grid artwork fallback | Use this fork's existing `posterFallback` field instead of upstream's `rawPosterUrl`; no new metadata schema. |
| `6aa42153da0c199d0647757656862c7dc9c451ce` | Select the requested debrid episode reliably | Reuse upstream exact path/name and bounded episode-token matching for TorBox, Real-Debrid and Premiumize; do not fall back to an unrelated large file when an explicit request cannot be matched. |
| `be81802c81f20ccf572ea9f8861e0dc6897534bb` | Paginate large TorBox cloud libraries | Reuse the existing HTTP API and response-size policy, aggregate pages, propagate failed pages and reject a repeated identical page rather than looping forever. |

Pagination retains the desktop transport's existing 64 MiB large-response cap.
The page aggregator also handles the existing truncation marker from transports
that return it; this does not change the desktop transport's hard-limit exception
into a retry. The deterministic fixture verifies pagination and error handling,
not live TorBox service interoperability.

## Already covered by this fork

- Source identity resets already clear audio/subtitle lists and restoration state.
- Shared track selection already normalizes ISO 639-2 language codes, retains
  regional preference precedence and supports secondary preferences. A focused
  regression exercises the existing audio selection helper instead of adding a
  competing native policy.
- Playback credential detection already distinguishes static `api_key` / `apikey`
  parameters from expiring credentials; a regression covers the upstream case.
- Artwork caching already has bounded, playback-aware memory budgets, disk caching
  and animated-image handling. Upstream's simpler cache settings are not substituted.
- Custom poster templates, multilined TTML parsing and native runtime extraction
  have existing fork implementations; overlapping upstream replacements are excluded.

## Intentionally excluded / deferred

- The upstream plugin-subtitle mapping depends on a different plugin result model,
  `StreamItem.externalSubtitles` and platform playback handoff. The attempted
  standalone cherry-pick was explicitly reverted when that dependency was found;
  its final production diff is empty. A mapping-only patch would be incomplete.
- Navigation 3 / mobile shell, new server-media and backend contracts, expanded
  catalog integrations and the replacement P2P engine require separate feature
  migrations. Their Android/iOS implementations and packaged binaries are not
  copied into the HTPC fork.
- Kotlin 2.4.10, Compose 1.12 and Gradle 9.4.1 changes accompany the official
  architecture. Existing fork versions and the immutable packaging toolchain remain.
- Official Linux/native renderer, windowing and runtime installers are not layered
  over this fork's tested mpv, X11/JAWT, PiP, inhibition, controller, MPRIS, Discord
  and hero ownership integrations. Windows/macOS native implementations also remain.
- Upstream next-episode and post-credits changes depend on a different player state
  model. The fork's next-episode resolver, HTPC navigation and source handoff remain.
- Supabase endpoint/network retry changes require different backend and dependency
  contracts. This fork's existing atomic client publication is preserved.
- Upstream cosmetic control placement, labels and mobile layout changes are not
  applied independently of their corresponding HUD/navigation contracts. In
  particular, unconditional desktop suppression of shared controls would remove
  this fork's fallback; existing HTPC focus/navigation is retained.
- Official version bumps, mobile workflows, native binaries and unrelated assets
  are excluded; this integration preserves the fork's version/channel identity.

No textual merge conflicts occurred. The meaningful compatibility resolutions
were the poster field adaptation, the existing TorBox HTTP contract, extending
the collection race fix to the All-tab rebuild, and rejecting
an incomplete plugin contract backport.

## Validation and acceptance

Automated validation is headless, with display opt-ins removed and repository
storage tests isolated from user configuration. It covers the changed shared
logic and existing Linux player/controller/OSK/ownership/PiP policies, native
fixtures, Kotlin compilation and packaging regression tests.

Local results:

- `compileKotlinDesktop` and `compileTestKotlinDesktop`: passed (existing compiler warnings remain).
- Combined focused desktop run: 390 tests in 34 suites; 389 passed, one optional
  real-mpv test skipped, zero failures/errors.
- Packaging unit/regression suite: 65 passed.
- Native bridge build and eight safe native fixtures: passed, including separate
  audio, HTTP/audio recovery, controller input and PiP geometry/chrome/visibility.
- The All-tab overwrite regression failed before the atomic rebuild fix and passed
  afterward. The fixture uses the real repository methods, not a duplicate algorithm.
- `git diff --check`: passed.

Focused review commands (headless; use temporary XDG configuration/data/cache
locations when running repository/storage suites):

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  JAVA_HOME=/path/to/jdk-17 ./gradlew :composeApp:compileKotlinDesktop \
  :composeApp:compileTestKotlinDesktop :composeApp:desktopTest \
  --tests '*LinuxFolderDetailConcurrencyTest' --tests '*LinuxOnlineProfilePinTest' \
  --tests '*TorboxCloudPaginationTest' --tests '*DebridFileSelectorTest' \
  --tests '*DebridFileSelectorReleaseTest' --tests '*WatchProgressRulesTest'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/linux/packaging/tests -v
git diff --check
```

The packaging builder inputs and WebKit recipe are unchanged. The full packaging
workflow can consume verified engine run `38284354443` and builder seed run
`38265810825` through their existing checksum/provenance gates, without rebuilding
WebKit. A passing package build/static verification is not desktop acceptance.

Before merging, manually check integrated video/audio playback, seeking, EOF and
next episode, source switching, Home/Details heroes, fullscreen/PiP, controller
navigation and OSK, MPRIS/Discord, collection poster fallback, profile PIN reload,
and debrid sign-in and episode selection with representative configured providers.
Use test accounts/data where needed. No GUI or real provider/receiver/controller
acceptance is implied by the headless fixtures.
