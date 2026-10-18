# Release artwork authoring

This optional, headless authoring recipe creates the four original synthetic setup
illustrations (`composeApp/src/desktopMain/resources/setup/setup_mode_*.webp`). It
is not an application or packaging runtime dependency. No previous image is an
input. The fictitious titles, abstract shapes and preview layouts are authored in
`GenerateReleaseArtwork.java`. No film posters, system fonts, network requests or
external artwork are used.

The previews retain the four names, RGB encoding and 1920×1082 dimensions. Basic
uses two card rails; Adaptive adds a hero; Ambient adds a colored wash; TV enlarges
the hero and emphasizes remote focus. They illustrate modes, not actual films.

## Owner decision 2026-10-17: upstream artwork

The Linux build ships Umbra's original badges, icons, logos and marks unchanged,
the same as upstream. The recipe no longer authors the six quality badges or the
47-file Linux package overlay (provider/rating marks, award/hero badges, TMDB
attribution logo), and `source_policy.py` no longer substitutes any artwork. Only
the setup previews remain fork-made. See
[the compliance record](../../../../docs/compliance/artwork-data-closure.md#owner-decision-2026-10-17-upstream-artwork).

### Restoring the fork-made artwork

If a DMCA or trademark notice arrives, revert the commit
`fix(linux): ship upstream artwork instead of fork-made replacements` (the last
commit that still had the replacements is `e860388fa`) and refresh
`docs/compliance/evidence/source-review-index.json` with
`tools/linux/packaging/compliance/review_index.py`. That restores
`tools/linux/packaging/artwork/overlay/` and `vendor/tmdb/`, the replacement
source drawables and `files/licenses/NuvioIcons/`, the full ReleaseArtwork
provenance/notice, the overlay logic in `source_policy.py`, the gate in
`artwork_contract.py`, this recipe's badge/overlay code and the related tests.

## Reproduce and review

Use JDK 17 and Pillow/libwebp, only as authoring tools. Exact versions, input font
hashes and output hashes are recorded in the bundled
[provenance](../../../../composeApp/src/commonMain/composeResources/files/licenses/ReleaseArtwork/PROVENANCE.json).
JDK rasterization or encoder versions on other hosts can change bytes; compare
outputs before updating pins rather than claiming universal byte reproducibility.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  python3 tools/linux/packaging/artwork/generate.py \
  --java-home "$JAVA_HOME" --output build/artwork-review
```

The destination must be fresh. Review generated PNGs at actual UI sizes, then
compare the four lossless WebP hashes with the manifest. Generated PNGs and the
authoring receipt stay outside tracked source; the recipe is the preferred
editable form.

Original illustrations: Copyright (c) 2026 NuvioDesktop-Linux contributors, MIT.
Inter 4.1 remains under SIL OFL 1.1, with the unchanged font license/notice
bundled separately.
