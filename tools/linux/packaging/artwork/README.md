# Release artwork authoring

This optional, headless authoring recipe creates six plain typographic SVG badges
and four original synthetic setup illustrations. It is not an application or
packaging runtime dependency. No previous image is an input. The fictitious titles,
abstract shapes and preview layouts are authored in `GenerateReleaseArtwork.java`.
No external artwork, film posters, system fonts or network requests are used.

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

## Narrow retained-byte exception

The owner explicitly chose to retain Umbra's four active originals:
`hero_badge_win.png`, `hero_badge_nom.png`, `hero_badge_foreign.png` and
`hero_badge_people_choice.png`. They are byte-identical at the pinned original
and WIP revisions in the manifest. Eight deleted resources share those bytes.
The gate therefore allows these hashes **only at the four exact original JAR
resource paths**, rejects every deleted filename even with different contents,
and rejects copies at other paths. The four originals remain **UNRESOLVED**.
This exception preserves upstream behavior; it is not a redistribution grant.
