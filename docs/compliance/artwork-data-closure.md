# Artwork and anime mapping closure

**2026-10-13; baseline `805797ed42103f931f3723c4f83547c249057687`.**
Seven missing-grant UI imports are replaced. The exact anime dump import revision
is established. Other artwork permissions and the full database-generation and
rights scope remain **BLOCKED**; this is evidence for review, not legal certification.

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

## Remaining packaged artwork

[The machine-readable review](evidence/artwork-data-review.json) classifies each
of **66** remaining resources by exact SHA256, introduction commit, actual code
references, category and **UNRESOLVED** status. None was excluded or replaced
silently. Unused resources still count as packaged; no claim that an absent call
site removes redistribution obligations is made.

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
- Dolby/DTS and other format/quality marks remain separately inventoried; a codec
  implementation or API grant does not establish rights to the mark artwork.
- All four setup previews contain movie posters/backdrops/title artwork (including
  Joker and Shutter Island). Repository screenshots do not establish rights to
  their embedded content. They remain unchanged and unresolved; synthetic previews
  would be a separate bounded, approved presentation correction.

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
Compilation and **33 focused JVM tests passed**, with zero failures or skips.
The final development JAR passes the new artwork/data gate and the existing Inter
font gate. Its exact SHA256, resource hashes, test classes and limits are in the
machine-readable review. These are local development artifacts; no live GUI or
final package acceptance is claimed.

DEB, Flatpak and AppImage remain **BLOCKED** by the enumerated artwork/data rights,
required native/static source/relink and recipient-delivery gates. The finite
[release closure](release-closure.md) still applies. No binary publication, HDR,
WebKit rebuild or SDR renderer change is part of this correction.
