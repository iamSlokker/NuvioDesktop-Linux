# Release preparation: font and notice closure

**2026-10-13 — reviewable correction; public redistribution remains BLOCKED.**
Base: `linux` at `a5adf33bda9fee1273d7d6cd8f1ad629464feb05`.
Later [artwork/data follow-up](artwork-data-closure.md), based on `805797ed`,
replaces the seven UI imports and recovers the exact anime import revision.
Other rights, original generation evidence and all public redistribution gates
remain blocked; the original font/notice validation below is historical evidence.
The subsequent correction at baseline `1555d7ea` removes nine unused award assets
and replaces six quality marks and four setup previews with original artwork.
There are now 47 unresolved provider/rating/award resources, including four active
Umbra originals retained under an explicitly documented exact-path byte exception.
This supplement records the current changes. The original inventory and previous
readiness reports describe historical packages from run `38397205286`; those
artifacts have not acquired the corrections below. HDR development is postponed
and is not part of this branch or a prerequisite for an SDR release.

## Resolved for future application payloads

- The three JetBrains Sans binaries are removed. Compose and the desktop HUD use
  unchanged official Inter 4.1 TTFs at weights 400, 600 and 700, with full SIL OFL
  copyright/license and notice. Exact archive, source revision and font hashes
  are in the [Inter manifest](../../tools/linux/packaging/fonts/inter-manifest.json)
  and [provenance report](inter-font-replacement.md). The approved logo is unchanged.
- The package gates require all three Inter resources and both notices at their
  pinned hashes. They reject the old font filenames and old font bytes renamed
  as TTF/OTF files, missing/tampered resources and duplicate entries. This gate
  covers the actual canonical application JARs and exported Flatpak JARs.
- Full authoritative ODbL 1.0 and Database Contents License 1.0 texts now accompany
  the existing anime mapping attribution and a byte-specific provenance record.
  The dataset itself is unchanged. The artwork/data follow-up establishes its
  exact Fribb import revision; original generation inputs, run receipt and
  applicable merged-data rights remain unresolved.
- The shader directory notice correctly distinguishes fourteen MIT shaders and
  two Unlicense downscalers. All sixteen shader bytes and original grants remain
  unchanged.
- [Modified-build/repack and installation instructions](modified-build-and-install.md)
  identify actual entry points, private-library replacement boundaries, security
  gates and format-specific procedures. They do not claim a completed offline
  rebuild, static relink or installation test.

## Validation and its limits

[The local validation receipt](evidence/release-closure-validation-2026-10-13.json)
records the exact development JAR, resource pins and focused test results.
`compileKotlinDesktop`, `compileTestKotlinDesktop` and `desktopJar` passed.
Seventeen focused JVM tests passed, including offscreen text wrapping at 100%,
125%, 150%, 175% and 200%, all three font weights, missing-system-font fallback,
existing login wordmark rendering, and packaged database/shader notices.

The completed packaging/compliance suite was reused: 141 tests, initially one
skip because `JAVA_HOME` was unset. Before this finalization, the already-started
run with Java 17 completed all 141 without skips. Only checks affected by later
review-index changes are rerun for finalization. No full package build, WebKit
compilation, GUI test, app launch or new binary publication occurred.

The generated application JAR passes the font payload gate and contains the
new database/shader notices. This proves its resource payload, not final
DEB/Flatpak/AppImage bytes or human acceptance of every screen's changed font
metrics. New candidates still require their own source identity, gates and
targeted typography/playback acceptance after the distribution gates below.

## AppImage runtime provenance (source build)

New AppImages embed the type2 runtime built by
`tools/linux/packaging/appimage_runtime.py` from fully pinned inputs
(`appimage-runtime/manifest.json`: type2-runtime `dd6cebed`, libfuse 3.15.0 +
upstream patch, squashfuse 0.5.2, zstd 1.5.7, zlib 1.3.2, mimalloc 2.5.2; musl
from `musl-dev 1.2.5-r11` in a digest-pinned Alpine 3.21.8 image) and compiled
offline. The result `6ee69667...a8ed` was reproduced byte-for-byte by two clean
builds. Its notices are in the AppDir under `usr/share/licenses/appimage-runtime/`,
and its corresponding source plus LGPL-2.1 relink kit is published as
`Nuvio-AppImage-runtime-source-6ee696672e2a.tar.gz` next to the AppImage. The
verifier rejects an AppImage without them or with another runtime prefix.
Details: [platform source evidence](platform-source-evidence.md).

## Smallest remaining action set

**2026-10-14:** the engineering items below are closed; see the update at the top of
[the final readiness report](../FINAL-READINESS-REPORT.md) for evidence and the remaining owner decisions.

1. **Resolve the specific rights/data questions.** Retain asset-specific grants
   for the 47 remaining provider/rating/award resources and the claimed
   IMDb permission. Seventeen resources now have original MIT replacements, and
   nine unused resources are excluded. The four retained Umbra byte exceptions
   remain unresolved; the package exception is not a license grant.
   The anime import revision is known; establish its original generation inputs,
   run receipt and applicable merged-data rights. Review model grants/preferred forms and
   applicable license elections/exceptions. Obtain evidence or separately
   approve bounded replacements; do not infer permission. Old Sans distribution
   remains a historical recipient issue even though new resources are corrected.
2. **Close the required native/static source and relink materials.** Use the
   retained ONNX Runtime, Skiko/Skia, QuickJS and Jansi roots and recipes; identify
   required incorporated sources, build modifications, toolchain provenance,
   notices and replacement/relink procedures under the applicable licenses.
   AppImage: the original runtime's musl/zstd/zlib/mimalloc identities remain
   unresolved for historical artifacts; new candidates use the reproducible source
   build described below and need only review of its notices/relink kit. Do not repeat the completed 125 Maven, Temurin or 427 Ubuntu
   source-retention work. Complete the identified gaps and validate modified
   build/install procedures in disposable environments.
   **Notice coverage update (2026-10-14):** all three formats now ship
   `share/licenses/third-party/` from `tools/linux/packaging/notices/`: Skiko,
   Skia m144-22f58c9fd4 and each library in the pinned skia-pack static set
   (dng_sdk, expat, FreeType under FTL with its credit line, HarfBuzz, ICU,
   libjpeg-turbo, libpng, libwebp, piex, Wuffs, zlib), ONNX Runtime with its
   ThirdPartyNotices, quickjs-kt, QuickJS, c-vector and Jansi. Every text is taken
   from the exact upstream revision recorded with its SHA256 in `manifest.json`;
   `verify.py` fails DEB/AppImage/Flatpak when any is missing or altered. This
   closes permissive notice coverage for those native components only. The
   AppImage runtime's static inputs and any copyleft source/relink scope remain open.
3. **Approve and test recipient source delivery.** Select a durable channel,
   access/retention policy and applicable delivery method. Build immutable
   per-artifact maps from the exact selected binaries and required retained
   materials, including notices and build/relink instructions. Verify recipient
   access and address existing recipients. Expiring authenticated Actions
   artifacts and local caches are not an already complete durable source offer.
   Any separately conveyed builder/engine OCI needs its own source/notice map.
4. **After the above gates, authorize the final candidate cycle.** Review/merge
   this correction separately, build all three formats from the selected source,
   reuse the verified WebKit engine only if its existing provenance contract
   still matches, verify package/source hashes and font notices, then perform
   isolated installation/startup, typography, sandbox and normal SDR playback
   acceptance. Publication requires a separate decision.

| Format | Remaining gate | Public redistribution |
| --- | --- | --- |
| DEB | Engineering gates closed in run 38538334113 (notices, artwork, dataset exclusion, source bundles, Ubuntu install smoke, owner acceptance). Remaining: TMDB attribution confirmation, final owner review, publication with its source assets | **PENDING OWNER DECISIONS** |
| Flatpak | Same as DEB; the GNOME runtime is installed separately from Flathub and is not part of the bundle | **PENDING OWNER DECISIONS** |
| AppImage | Same as DEB; source-built runtime with notices, source archive and relink kit, plus WebKit/GTK and Ubuntu library sources, verified in run 38538334113 (historical artifacts: original runtime inputs unresolved, do not redistribute) | **PENDING OWNER DECISIONS** |

These are evidence boundaries, not legal certification. No permissions or
license exemptions were invented, and no historical package/source receipt was
rewritten to describe a new binary.
