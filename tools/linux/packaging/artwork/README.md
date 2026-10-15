# Release artwork authoring

This optional, headless authoring recipe creates six plain typographic SVG badges,
four original synthetic setup illustrations and the 47-file Linux package overlay
described below. It is not an application or
packaging runtime dependency. No previous image is an input. The fictitious titles,
abstract shapes and preview layouts are authored in `GenerateReleaseArtwork.java`.
No film posters, system fonts or network requests are used. The only external
artwork input is TMDB's official attribution logo, vendored unmodified and
hash-pinned under [`vendor/tmdb/`](vendor/tmdb/README.md) (not MIT; see below).

The badges retain the six existing resource names, viewBoxes and intrinsic sizes.
The two 4K badges retain their gold/white source distinction. `DV`, `ATMOS`, `DTS:X`
and `HDR` use ordinary Inter outlines in a generic rectangle, without logo geometry.
Existing Kotlin detection, category settings and accessibility labels are unchanged.
The previews retain the four names, RGB encoding and 1920×1082 dimensions. Basic
uses two card rails; Adaptive adds a hero; Ambient adds a colored wash; TV enlarges
the hero and emphasizes remote focus. They illustrate modes, not actual films.

## Reproduce and review

Use JDK 17 and Pillow/libwebp, only as authoring tools. Exact versions, input font
hashes and output hashes from this correction are recorded in the bundled
[provenance](../../../../composeApp/src/commonMain/composeResources/files/licenses/ReleaseArtwork/PROVENANCE.json).
JDK rasterization or encoder versions on other hosts can change bytes; compare
outputs before updating pins rather than claiming universal byte reproducibility.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/artwork/generate.py \
  --java-home "$JAVA_HOME" --output build/artwork-review
```

The destination must be fresh. Review generated PNGs/SVGs at actual UI sizes, then
compare the six SVG and four lossless WebP hashes with the manifest. Generated
PNGs and the authoring receipt stay outside tracked source; the recipe is the
preferred editable form. The approved Nuvio brand assets are unchanged.

Original geometry and illustrations: Copyright (c) 2026 NuvioDesktop-Linux
contributors, MIT. Inter 4.1 remains under SIL OFL 1.1, with the unchanged font
license/notice bundled separately. Format names describe detected metadata;
neither certification, trademark permission for old artwork, nor endorsement is
claimed. Publication remains blocked by the separate rights/source/delivery review.

## Linux package overlay (47 resources)

By owner decision, the 47 provider/rating marks and award/hero badges whose
imported rights are UNRESOLVED are not shipped in Linux packages or the Linux
source tarball. The same recipe authors 46 original MIT replacements and renders
TMDB's official attribution logo for `rating_tmdb.png`, all written to
`linux-overlay/` in the output directory and committed under
[`overlay/`](overlay) at their repository-relative paths, e.g.
`overlay/composeApp/src/commonMain/composeResources/drawable/rating_imdb.png`.

- Names and formats are unchanged: SVGs keep the original `width`/`height`/
  `viewBox` attributes and contain only `svg`, `title`, `rect` and `path`
  (Inter glyphs as outlines); PNGs keep their pixel sizes as 8-bit RGBA.
  Kotlin is unchanged (`imageResource` rasters and literal `.png` hero names).
- Provider/rating marks are neutral rounded plates with plain Inter text
  (`IMDb`, `TR`/`Trakt`, `LB`, `MC`, `MAL`, `KIT`, `SIMKL`, `TVDB`,
  `MDB`, `IDB`, `DC`), except TMDB (below). Rotten Tomatoes/audience states use `RT`/`AS` with
  green/slate circles, amber/grey squares and up/down/check glyphs; certified and
  verified states use a generic rosette. No brand colors, logo geometry,
  tomato or popcorn imagery.
- Award/hero badges are generic cup trophies, laurels, medals, shields, stars,
  screens, film frames, clocks and arrows, with `NOM`, `1ST`, `NEW` and `CULT`
  where useful. No statuettes or festival/award logo likenesses. Each file has
  distinct bytes (unique `<title>` or pixels). `hero_badge_director.svg` is drawn
  for its actual use (studio/prestige) and `hero_badge_studio.svg` for director.

`tools/linux/packaging/source_policy.py` (applied once after `build.source_tree()` by both
canonical builds, `build.py` and the seeded `canonical_from_seed.py`) substitutes each overlay file for its tracked
target in the packaging source snapshot, records the overlay hash in
`source-files.json`, and fails if a target is untracked or original bytes remain
elsewhere in the snapshot. The composeApp originals are unchanged for upstream,
Windows and macOS, where they remain UNRESOLVED. The packaged-artwork gate
requires every replacement hash and rejects all 47 original byte sequences at any
JAR path. Rows are in the bundled provenance under `linux_overlay`.

After regenerating, compare `linux-overlay/` with the committed overlay:

```sh
diff -r build/artwork-review/linux-overlay tools/linux/packaging/artwork/overlay
```

The receipt's `overlay_files` and `vendored_inputs` must equal the provenance
`authoring.toolchain`.

### TMDB attribution logo (owner decision 2026-10-14)

TMDB's [logos and attribution page](https://www.themoviedb.org/about/logos-attribution)
states: "As per our terms of use, every application that uses our data or images
is required to properly attribute TMDB as the source. Below you will find some
logos you can use within your application." `rating_tmdb.png` (the detail rating
row and the TMDB integration/attribution logo) is therefore TMDB's official
"Primary full (blue)" logo rather than an original plate. Its SVG is vendored
byte-for-byte at `vendor/tmdb/blue_square_2.svg` (SHA-256
`d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b`, retrieved
2026-10-14); its 1.387:1 viewBox best fits the 67×48 slot. `generate.py` and the
recipe refuse other bytes. A minimal reader in the recipe, limited to that file's
structure (one path of `M/L/H/V/A/Z` commands, one user-space linear gradient, no
transforms), renders it with Java2D antialiasing, uniformly scaled and centered
with transparent padding: no recolor, stretch or crop. The provenance row records
license `TMDB-attribution-terms`, not MIT; `artwork_contract.py` allows that
exception only for this resource and pinned SVG. It is used for attribution only;
no endorsement or broader trademark permission is claimed.

## Former retained-byte exception (superseded)

The owner previously chose to retain Umbra's four active originals
`hero_badge_win.png`, `hero_badge_nom.png`, `hero_badge_foreign.png` and
`hero_badge_people_choice.png` at their exact JAR paths. The Linux overlay now
replaces them; their bytes are forbidden at every packaged path, like the other
originals. Upstream builds still ship them unchanged.
