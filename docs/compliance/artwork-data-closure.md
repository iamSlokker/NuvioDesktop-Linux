# Artwork and anime mapping closure

**2026-10-14; latest correction baseline `1555d7ea75c3777f854f4af1b2256c9ff2419cd9`.**
Seven missing-grant UI imports are replaced. The exact anime dump import revision
is established. The 47 previously unresolved provider/rating and award/hero
resources are replaced **in Linux packages only** by an original overlay (see
[Linux package artwork overlay](#linux-package-artwork-overlay)). The full
database-generation and rights scope, and every other release gate, remain
**BLOCKED**; this is evidence for review, not legal certification.

## Seven UI vectors

All seven entered in upstream commit
`a33ad982dec56f275f2203e69d46cda7fd0b81b3`. Their wrappers identify SVG Repo,
without asset IDs or grants. Publisher geometry confirms that speaker, pause and
play came from Solar's Bold set. The inspected
[publisher revision](https://github.com/480-Design/Solar-Icon-Set/tree/44017167688b49109d88ae6a98979b23d8950db0)
has no license file and an unresolved [license request](https://github.com/480-Design/Solar-Icon-Set/issues/5).
Third-party MIT/CC labels were not used as a substitute. No authoritative
asset-specific grant was established for the remaining four from their import
history and bounded source search; this does not assert that no grant could exist.

The replacements are original elementary vector constructions under MIT,
with the full copyright/license, notice and
[per-asset provenance](../../composeApp/src/commonMain/composeResources/files/licenses/NuvioIcons/PROVENANCE.json)
bundled in Compose resources. Original/new SHA256, original Git blob/import
commit, dimensions, actual use and license are recorded for each asset.

| Asset | Disposition and visual difference |
| --- | --- |
| `library_add_plus.svg` | **REPLACED** — centered round-ended plus; same stroke/extent |
| `ic_player_aspect_ratio.svg` | **REPLACED** — rounded frame and opposing corner marks; simplified curves |
| `ic_player_audio_filled.svg` | **REPLACED** — filled speaker cabinet/two drivers; simpler cabinet corners |
| `ic_player_pause.svg` | **REPLACED** — two rounded bars; simpler corner shape |
| `ic_player_play.svg` | **REPLACED** — right-facing rounded triangle; simpler curves |
| `ic_player_subtitles.svg` | **REPLACED** — rounded caption frame and two C strokes; simplified lettering |
| `trailer_play_button.svg` | **REPLACED** — generic rounded video tile/play cutout; no current Kotlin call site, retained resource name |

All intrinsic sizes remain 800×800. Six viewBoxes remain 24×24; the trailer
viewBox remains 566.828×566.828. Tint palettes and existing resource mappings are
preserved. No player code, platform input behavior, approved Nuvio logo or
wordmark is changed. The new MIT grant does not retroactively license old imports.

## Nineteen-resource payload correction

Nine unused resources are removed after checking baseline tracked source, JavaScript,
JSON, platform code, tests, generated-accessor spellings and dynamic filename
paths. `HomeHeroSection.heroDiscoveryBadgeFileName` is a fixed switch;
`HeroCustomBadge.heroBundledBadgeModel` does not enumerate bundled assets.
Fresh resource generation and Kotlin compilation check the removal boundary.

- Removed: `hero_badge_best_picture_nominee.png`, `hero_badge_golden_leopard.png`,
  `hero_badge_must_see.png`, `hero_badge_new_currents.png`, `hero_badge_oscar_win.png`,
  `hero_badge_sundance_grand_jury.png`, `hero_badge_sxsw_jury.png`,
  `hero_badge_tiger_award.png`, `hero_badge_tribeca_audience_award.png`.
- Replaced: the six `quality_badge_{4k_bluray,4k_web,atmos,dts,dv,hdr}.svg`
  files with original generic rectangles and plain Inter typography. Source
  colors, dimensions, Kotlin mappings and detected capabilities are unchanged.
  Dolby/DTS symbols and specialized logo lettering are absent. DTS:X remains
  conditional on the existing DTSX token; no capability detection is broadened.
- Replaced: the four `setup_mode_{basic,adaptive,adaptive_ambient,tv}.webp`
  screenshots with original synthetic illustrations: fictitious titles,
  abstract art, distinguishable layouts, unchanged names and 1920×1082 size.
  They retain the existing loader and wizard layout, with no app dependency.

Original/replacement identities, hashes, authoring recipe, license and notice
are in the bundled [ReleaseArtwork provenance](../../composeApp/src/commonMain/composeResources/files/licenses/ReleaseArtwork/PROVENANCE.json).
The editable Java2D recipe and [reproduction instructions](../../tools/linux/packaging/artwork/README.md)
use only pinned existing Inter fonts and original geometry. New geometry and
illustrations are MIT; the unchanged font remains SIL OFL. This does not license
historical imports or imply endorsement by format owners.

**Superseded for Linux packages by the overlay below.** Previous
**owner-approved narrow exception:** eight removed resources share bytes with
four active Umbra originals. The owner requested preserving `hero_badge_win.png`,
`hero_badge_nom.png`, `hero_badge_foreign.png` and `hero_badge_people_choice.png`.
They are byte-identical in `windows-tv-adaptive` at `a70cc370…` and the pinned WIP
at `18537e3c…` (full revisions/hashes in provenance). Their hashes are allowed
only at those four exact original Compose JAR paths. Deleted filenames, renamed
copies and duplicate entries still fail. These four remain **UNRESOLVED**;
there is no blanket assertion that every deleted hash is absent or licensed.

## Compose Multiplatform template mark

`drawable/compose-multiplatform.xml` (1,493 bytes, SHA256
`b236686cab2cbd8a1396bfee4d976de07a3d6ceee32f8fac631759bf70d069a4`) is the
JetBrains Compose Multiplatform template logo, byte-identical to its
introduction in project initialization commit
`4b99274756e992a531e9539b04b2bbc444fd017c`. It was packaged in the application
JAR but was not classified in the inventory above. `git grep` finds no reference
to `compose-multiplatform.xml` or its generated accessor `compose_multiplatform`
in any source set, Gradle build, tool or workflow. It is **removed**
(category `template-brand-mark`) rather than inferring redistribution rights,
and recorded with the other removals in the bundled provenance. Its filename and
bytes are now rejected in application JARs like the other removed resources.
Fresh Compose resource generation and Kotlin compilation confirm that the
accessor was not needed. Actual dependency JARs in previous package images do
not contain this filename, so the ban affects only the application's own copy.

## Remaining packaged artwork (upstream payload)

This section describes the composeApp originals, which upstream, Windows and macOS
builds still ship. For Linux packages they are replaced; see the next section.
[The machine-readable review](evidence/artwork-data-review.json) classified each
of **47** remaining resources (20 provider/rating marks and 27 award/hero badges)
by exact SHA256, introduction commit, actual code references and **UNRESOLVED**
status. Separate maps record the ten removed and ten newly replaced resources.
Together with the previous seven UI replacements, the cumulative disposition is
17 replaced, ten excluded, 47 unresolved and zero newly verified grants.

- Provider/rating marks include Trakt, IMDb, TMDB, Rotten Tomatoes/Popcornmeter,
  Letterboxd, Metacritic, MyAnimeList, Kitsu, SIMKL, TVDB, MDBList, IntroDB and Discord.
  [TMDB](https://developer.themoviedb.org/docs/faq) supplies conditional attribution
  and approved-logo rules; the current raster's exact approved origin/use still
  needs evidence. [IMDb](https://www.imdb.com/conditions/) refers logo uses to
  permission or specific exceptions; the app's permission assertion is not the
  underlying grant. [Rotten Tomatoes](https://www.rottentomatoes.com/help_desk/licensing)
  requires application-use review and specifies brand/score/link conditions.
  Public [Discord](https://discord.com/branding) and
  [Letterboxd](https://letterboxd.com/about/brand/) guidance is recorded without
  treating it as unconditional permission for current bytes. Trakt's primary
  branding page returned HTTP 403; its red rating mark and wordmark still need
  approved-variant and use evidence. Other provider-specific grants remain absent.
- Award/festival and generic hero badges remain unresolved. The Oscar likeness
  also appears in generic win/nomination PNGs, not just Oscar-specific filenames.
  [Academy regulations](https://www.oscars.org/legal/regulations) restrict statuette
  reproductions and generic decorative use. Retain applicable permission/exception
  evidence or separately approve a bounded replacement; do not merely relabel it
  as a generic trophy. [Emmy policy](https://www.televisionacademy.com/trademark-policy)
  also imposes specific statuette/name-use conditions. Other award/festival
  artwork and generic imported graphics have no verified source/license records;
  a generic laurel is not automatically a licensed festival logo.
- The six former quality-mark imports and four embedded-artwork screenshots are
  replaced as described above. Previous bytes are forbidden in future application
  JARs, including renamed non-image copies. Historical binaries are unchanged.

## Linux package artwork overlay

**Owner decision (2026-10-14):** the 47 UNRESOLVED resources above (20
provider/rating marks, 27 award/hero badges) must not be present in Linux
packages or the Linux source tarball; Linux packages carry original replacement
artwork instead. To keep Windows/macOS unchanged and upstream merges clean, the
composeApp originals and all Kotlin are **not modified**.

- **Replacements:** original MIT artwork authored by the existing
  [Java2D recipe](../../tools/linux/packaging/artwork/GenerateReleaseArtwork.java)
  with only the pinned Inter fonts and original geometry, committed under
  [`tools/linux/packaging/artwork/overlay/`](../../tools/linux/packaging/artwork/overlay)
  at the same repository-relative paths. Names and formats are unchanged; SVGs
  keep their `width`/`height`/`viewBox` and contain only `svg`/`title`/`rect`/`path`;
  PNGs keep their pixel sizes as RGBA. Provider/rating marks are neutral plates
  with plain Inter text (`IMDb`, `TMDB`, `TR`, `Trakt`, `LB`, `MC`, `MAL`, …);
  Rotten Tomatoes critic/audience states stay distinguishable by circle/square or
  rosette shape, color and an up/down/check glyph, with no tomato/popcorn imagery.
  Award/hero badges are generic trophies, laurels, medals, shields, stars,
  screens, frames and clocks with short text (`NOM`, `1ST`, `NEW`, `CULT`); no
  statuettes or festival/award logo likenesses. All 47 have distinct bytes.
- **Substitution:** `tools/linux/packaging/source_policy.py`, applied after `build.source_tree()` by the seeded canonical build (`build.py` stays byte-identical for the pinned builder seed), copies each
  overlay file over its tracked target in the packaging snapshot and records the
  overlay SHA256 in `source-files.json` (and therefore `source_sha256`). It fails if
  a target is not tracked or if original bytes remain anywhere else in the
  snapshot. The overlay directory itself stays in the snapshot; the originals do
  not, so the application JAR and source tarball carry only replacements.
- **Provenance:** each row (original SHA256/bytes/introduction commit and
  replacement SHA256/bytes/dimensions/MIT) is in the bundled
  [ReleaseArtwork provenance](../../composeApp/src/commonMain/composeResources/files/licenses/ReleaseArtwork/PROVENANCE.json)
  under `linux_overlay`; its NOTICE describes the overlay. The four former
  retained-hash exceptions are removed.
- **Gate:** `tools/linux/packaging/artwork_contract.py` requires every overlay
  target's packaged path to carry its replacement hash, rejects all 47 original
  byte sequences at any JAR path (including renamed copies), rejects renamed
  replacement copies, and no longer allows any retained original.
  It reports `linux_overlay_replacements: 47` and `unresolved_exact_path_exceptions: 0`.
- **Disposition:** the machine-readable review records the 47 as
  `LINUX_OVERLAY_REPLACED`, `unresolved: 0` for Linux packages, and
  `unresolved_upstream_other_platforms: 47` because upstream/Windows/macOS still
  ship the originals unchanged. No rights in the originals are claimed.

**TMDB attribution (owner action):** `rating_tmdb.png` (also the TMDB integration
settings icon) was replaced with a plain `TMDB` text mark per owner decision.
TMDB's API terms may require its approved logo for attribution. Compliance with
those terms must be confirmed by the owner; it is **not** claimed here.

Validation for this change: all 47 overlay files reproduced byte-identically
from the recipe on the recorded JDK 17.0.20.1, and the receipt equals the pinned
provenance toolchain. A `desktopJar` built by Gradle from a `build.source_tree()`
snapshot passed the packaged-artwork gate (75 pinned resources, 47 overlay
replacements); the checkout JAR without the overlay is rejected. That JAR is a
local development artifact, not a DEB/Flatpak/AppImage build. Contact-sheet
review was offscreen only; no live GUI acceptance is claimed.

## Exact anime import recovered; generation/rights still incomplete

The current **39,577-entry**, 5,862,205-byte `anime-list-mini.json` is byte-identical
to [Fribb commit `4ddbccd…`](https://github.com/Fribb/anime-lists/blob/4ddbccd752fb4fe39da71f9729b33e4fd7257555/anime-list-mini.json),
dated 2026-09-29. Its Git blob is
`4a92c06f6ab4f0021235bce115da2eab34074dce`, SHA256
`cfcdee0da5c0c9f2cf91948390d40f5a49b0a59469b8f4030599a947afb2deba`.
Application history records two earlier versions and the current update in
`88e5ed6b1d0b958e3efe0faaa36648cc6763f54a`. No data bytes were regenerated,
transformed or relabelled. The updated
[bundled record](../../composeApp/src/desktopMain/resources/anime-mapping/PROVENANCE.json)
pins the raw download and published reduced intermediates.

The [inspected generator](https://github.com/Fribb/anime-lists-generator/tree/6a422015a6f0c7ccb923ed3505a2cf540b864b36)
is the latest commit preceding the dump timestamp, **a candidate, not a proven
build identity**. Its code fetches the offline database's mutable latest release
and Anime-Lists master XML, merges reduced mappings and enriches missing IDs using
live TMDB responses. No original raw-input revisions, response snapshot or run
receipt was established. Fribb output/generator and Anime-Lists trees contain no
license files; the app's ODbL attribution is not independent proof for every
merged/enriched contribution. Complete the applicable grant/rights assessment.
The [2022 permission discussion](https://github.com/Anime-Lists/anime-lists/issues/143)
contains a contributor's explicitly unofficial reassurance, not an authoritative
license grant. The [license request](https://github.com/Anime-Lists/anime-lists/issues/613)
remains open without an official grant in the inspected replies. No relevant
license discussion was found by the bounded Fribb repository issue search.

A deterministic regeneration from the explicitly ODbL/DbCL
[offline database](https://github.com/cedya77/anime-offline-database/tree/32bfaf32c2445dd2f5ff276473cdf1f6bf653a58)
was evaluated. A pinned projection could retain its native IDs, but does not
establish equivalent IMDb/TMDB/TVDB crosswalks or franchise season/episode offsets.
Current data has 8,229 IMDb, 8,512 TMDB, 7,499 TVDB, 7,434 season and 684 offset
entries. Losing these is not a safe drop-in replacement. Copying unresolved
crosswalks into a new dump would not clear the gap; live enrichment needs its own
terms and retained responses. Therefore **no regeneration was performed**.

## Linux packages download the anime dataset at runtime

By owner decision, Linux release packages no longer redistribute
`anime-list-mini.json`. `tools/linux/packaging/source_policy.py` (applied by the seeded
canonical build; `build.py` stays byte-identical for the pinned builder seed)
omits it from the packaging source snapshot, so it is absent from the application JAR and from
`Nuvio-source-<commit>.tar.gz`; the package gate rejects its filename and its
exact bytes anywhere in `lib/app/*.jar`. When the packaged resource is absent,
`AnimeListDownload.kt` fetches the exact reviewed revision from
`raw.githubusercontent.com/Fribb/anime-lists/4ddbccd…/anime-list-mini.json`,
requires 5,862,205 bytes and SHA256 `cfcdee0d…deba`, and caches it under the
app data directory (`$XDG_CONFIG_HOME/nuviohtpc/anime-mapping/`; Flatpak has
network permission). A failed or tampered download is discarded and anime ID
conversion falls back to the plain ID chain for that session. The ODbL/DbCL
texts, NOTICE and PROVENANCE still ship because the data is still used.
Windows/macOS builds and source checkouts are unchanged and keep the bundled
copy. This avoids redistributing the dataset; it does not establish rights to
use it, which remains the user's download from the public upstream.

## Validation and release boundary

Focused Python checks exercise SVG dimensions/safe parsing, exact replacement
and database/notice bytes, rejected missing/tampered/duplicate package resources,
unchanged remaining artwork, and the exported Flatpak verifier handoff.
The new package gate verifies payload identity and explicitly reports unresolved
redistribution status; it is not permission to publish. Existing font, ONNX,
backend, sandbox and source verification remain intact.

Raster-only Compose tests exercise actual `appIconPainter`/resource decoding,
monochrome tint, recognizable cutouts and 18/24/34dp sizes at 100/125/150/175/200%.
The unchanged logo and production anime lookup tests cover representative native
IDs, IMDb/TMDB namespaces, franchise/Pokémon offsets and cold/cached/concurrent loads.
At the previous baseline, **33 focused JVM tests passed**, with zero failures or skips.
That development JAR passed the then-current artwork/data gate and existing Inter
font gate. Its exact SHA256, resource hashes, test classes and limits are in the
machine-readable review under `validation_at_previous_baseline`. Current correction
results are recorded separately under `payload_correction_validation`. These are
local development artifacts; no live GUI or
final package acceptance is claimed.

For the nineteen-resource correction, production/test Kotlin compilation and
`desktopJar` passed. **23 focused JVM tests** and **24 focused Python tests**
passed with zero failures or skips. Actual production quality painters were
rasterized at 18dp and 100/125/150/175/200%; setup painters at narrow/normal widths
across those scales and at 1920px. Contact sheets show readable plain labels,
uncropped UI text and four distinct synthetic modes. Existing login branding,
setup settings and capability selection tests remain passing. No visual defect
was detected offscreen; final target-session acceptance was not performed.

The actual generated application JAR passes all 32 artwork/data resource pins
and the Inter gate. Fixtures exercise the real artwork gate through all three
format verifiers, including rejection of forbidden bytes/names in exported JARs.
All ten new assets reproduced byte-identically using the recorded authoring
toolchain. All nineteen original identities were independently checked against
baseline Git blobs; tests use synthetic old bytes instead of redistributing
unlicensed imports as fixtures. The gate is not a rights grant or a new package
build: other artwork and data/source/delivery blockers remain unchanged.

DEB, Flatpak and AppImage remain **BLOCKED** by the enumerated data rights, owner
confirmation of TMDB attribution,
required native/static source/relink and recipient-delivery gates. The finite
[release closure](release-closure.md) still applies. No binary publication, HDR,
WebKit rebuild or SDR renderer change is part of this correction.
