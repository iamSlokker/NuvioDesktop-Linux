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

## Smallest remaining action set

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
   AppImage additionally needs the original runtime's unresolved
   musl/zstd/zlib/mimalloc input identities or a separately authorized reproducible
   replacement. Do not repeat the completed 125 Maven, Temurin or 427 Ubuntu
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
| DEB | Common rights/data, required native source/notices/relink scope, tested replacement/install procedures, recipient delivery, fresh candidate acceptance | **BLOCKED** |
| Flatpak | Same common gates; review responsibility/source availability for any separately supplied runtime or driver extensions without assuming an exemption | **BLOCKED** |
| AppImage | Same common gates plus the original static runtime's unresolved inputs and applicable relink materials | **BLOCKED** |

These are evidence boundaries, not legal certification. No permissions or
license exemptions were invented, and no historical package/source receipt was
rewritten to describe a new binary.
