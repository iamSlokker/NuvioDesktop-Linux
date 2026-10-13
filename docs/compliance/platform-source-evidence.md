# AppImage runtime and Flatpak runtime source follow-up

Audit date: 2026-10-13. This supplements
[the redistribution audit](linux-redistribution-audit.md) and records additional
locally retained source evidence in
[platform-source-retention.json](evidence/platform-source-retention.json).
It does not certify licensing or change any package's **BLOCKED** assessment.
No binary was downloaded, built, installed or uploaded during this follow-up.

## AppImage outer runtime: two source gaps closed locally

The AppImage prefix is the pinned type2 runtime release `20251108`, source
revision `dd6cebedcbddde9c82f89b011e8e1d40b6e43868`. Its original binary SHA-256 is
`2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d`.
The earlier audit established the relationship between that input and the
distributed AppImage prefix, including the single permitted digest-field change.

The exact retained runtime source recipe pins these two static dependencies.
Both archives were retrieved from their original source URLs and independently
checked against the hashes in `scripts/common/install-dependencies.sh`:

| Component | Source and retained SHA-256 |
| --- | --- |
| libfuse 3.15.0 | [Source archive](https://github.com/libfuse/libfuse/releases/download/fuse-3.15.0/fuse-3.15.0.tar.xz): `70589cfd5e1cff7ccd6ac91c86c01be340b227285c5e200baa284e401eea2ca0` |
| squashfuse 0.5.2 | [Source archive](https://github.com/vasi/squashfuse/archive/0.5.2.tar.gz): `db0238c5981dabbd80ee09ae15387f390091668ca060a7bc38047912491443d3` |

The runtime source archive itself remains
`f5fec23be76e50e2445ed2d018bac49b367490fc483c62c4637e99ec705d27ba`.
It contains the recipes, runtime source and libfuse modification. The exact
upstream `patches/libfuse/mount.c.diff` was applied to the original `lib/mount.c`
in a disposable directory using `git apply --check` followed by `git apply`.
The patch applies without altering it:

- Patch SHA-256: `1c7fd9e26717545a476b226b083a9f9d05676c180edbd71a04bbd8a73599dc44`.
- Before: `dabd63d3e71166940aa52a06adad594bbb72508e446cf80098ef16fce78c9e1f`.
- After: `00deae39a39491642110fcbee871023054ed8d66f25825f8c18810d5e4cdb736`.

The upstream patch has one existing trailing-whitespace line; the test preserved
the patch bytes. No runtime or library was compiled. libfuse's `LICENSE`,
`LGPL2.txt` and `GPL2.txt`, and squashfuse's `LICENSE` are retained inside these
source archives with hashes recorded in the evidence. Review component/file
scope rather than applying the runtime's top-level MIT notice to everything.

### The remaining Alpine provenance gap is concrete

[Upstream build 20037536435](https://github.com/AppImage/type2-runtime/actions/runs/20037536435)
is a successful build at exactly `dd6cebed...`, created **2025-12-08**, despite the
release's `20251108` name. Its original Actions log endpoint returned **HTTP 410
Gone** on this audit date.

The retained workflow makes the release upload depend on its Docker build job.
That path uses **`FROM alpine:3.21` without an image digest** and unversioned
`apk add`, including `musl-dev`, `zstd-static`, `zlib-static` and `mimalloc-dev`.
The separate chroot recipe's `ALPINE_RELEASE=3.21.0` is not the release Docker
image identity. It must not be used to invent exact release package versions.

`src/runtime/Makefile` statically links squashfuse, zstd, zlib, libfuse and
**mimalloc**, plus libc/toolchain contributions. The root LICENSE lists several
of these but omits mimalloc. The source proves dependency names and build
commands; it does not prove which Alpine package revisions supplied the four
remaining static components to this binary. Current Alpine package contents or
a third-party source collection are not a substitute for that evidence.

To close this gap for the existing binary, obtain its original resolved Docker
image/APK versions and applicable sources/patches from upstream or another
verifiable retained build record. If that cannot be recovered, a separately
authorized reproducible runtime build with complete input/source/relink evidence
is the engineering alternative. No replacement build is attempted here. Review
the static-library license/relink requirements before claiming complete
Corresponding Source or permission to redistribute a new runtime.

Pinned recipe references:
[Dockerfile](https://github.com/AppImage/type2-runtime/blob/dd6cebedcbddde9c82f89b011e8e1d40b6e43868/scripts/docker/Dockerfile),
[dependency recipe](https://github.com/AppImage/type2-runtime/blob/dd6cebedcbddde9c82f89b011e8e1d40b6e43868/scripts/common/install-dependencies.sh),
[Makefile](https://github.com/AppImage/type2-runtime/blob/dd6cebedcbddde9c82f89b011e8e1d40b6e43868/src/runtime/Makefile),
[workflow](https://github.com/AppImage/type2-runtime/blob/dd6cebedcbddde9c82f89b011e8e1d40b6e43868/.github/workflows/build.yaml).

## Flatpak: exact runtime metadata now resolves to a source recipe

The packaging provenance names `org.gnome.Platform/x86_64/49` at OSTree commit
`2bb946ea4ebb050f6a32f90e979feb975b08280d1ff1aac881dec3d1361ad84d`.
The 3,948-byte [commit object](https://dl.flathub.org/repo/objects/2b/b946ea4ebb050f6a32f90e979feb975b08280d1ff1aac881dec3d1361ad84d.commit)
was fetched as metadata only. Its SHA-256 equals that exact commit ID.
This checks the content-addressed object, not a separate GPG verification.

Its subject is `Build of the GNOME Runtime from: 49.10-1-g75d7cf116`.
The official GNOME commit API resolves this to
[`75d7cf1166db3e0e938d2c12a3195b9e8d8f34fa`](https://gitlab.gnome.org/GNOME/gnome-build-meta/-/commit/75d7cf1166db3e0e938d2c12a3195b9e8d8f34fa),
dated 2026-10-02, updating GNOME's WebKitGTK recipe to 2.54.1.
The exact recipe's `.gitlab-ci/scripts/deploy-flatpak.sh` constructs the OSTree
subject using `git describe HEAD`. That establishes a specific metadata-to-build
recipe link. The `xa.from_commit` value
`6a1a2bbe72a60eedcab566b67febfa2513892e23945e03e26c7c6e12e831ab6c`
is another **OSTree commit**, not a Git source revision.

Two complete build-recipe archives were retained from their primary hosting
services. Their following SHA-256 values are observed retention digests, not
independently signed upstream archive checksums:

| Build project | Exact Git revision | Retained archive SHA-256 |
| --- | --- | --- |
| [gnome-build-meta](https://gitlab.gnome.org/GNOME/gnome-build-meta/-/archive/75d7cf1166db3e0e938d2c12a3195b9e8d8f34fa/gnome-build-meta-75d7cf1166db3e0e938d2c12a3195b9e8d8f34fa.tar.gz) | `75d7cf1166db3e0e938d2c12a3195b9e8d8f34fa` | `a261e4caba2fe95e0d5e3281e6e1bc2cd0030c0eb1874f0059c269d27b96b5e8` |
| [freedesktop-sdk](https://gitlab.com/freedesktop-sdk/freedesktop-sdk/-/archive/4535d0fa4894628fdf49999ac5c2aacd138a6035/freedesktop-sdk-4535d0fa4894628fdf49999ac5c2aacd138a6035.tar.gz) | `4535d0fa4894628fdf49999ac5c2aacd138a6035` | `dea5d9ed63b15d98c3e258fbcc9e1f23cb656957fa8491415d2ee2076bfbe02e` |

The Freedesktop revision is pinned by GNOME's `elements/freedesktop-sdk.bst`
junction as `freedesktop-sdk-25.08.17-0-g4535d0fa...`. The GNOME archive includes
its junction patches. The Freedesktop recipe confirms graphics/runtime extension
branches `25.08` and `25.08-extra`; GNOME additionally permits NVIDIA compatibility
branch `1.4`. This is compatible with the retained runtime metadata, and avoids
incorrectly treating GNOME's `49` as the graphics-extension version.

These archives retain recipes/refs/modifications, **not all upstream source
bytes**. GNOME's BuildStream project requires version >=2.5 and declares the
source cache `https://gbm.gnome.org:11003`; no cache payload was downloaded or
validated here. The build graph also includes plugins, bootstrap and further
junctions. Full per-component source/license closure remains outstanding.

The exact `flatpak-runtimes.bst` target enumerates Platform, SDK, debug, docs,
locale and architecture extensions, but no `.Sources` output. Guessed direct
Flathub `.Sources` ref URLs returned 404; this alone does not prove sources are
unavailable through other mechanisms. Flatpak's general `--bundle-sources`
facility must not be described as evidence that this BuildStream runtime
actually published such an extension.
[Flatpak source-extension documentation](https://docs.flatpak.org/en/latest/dependencies.html).

Nuvio's `.flatpak` carries its application payload and separately declares the
runtime dependency. Do not automatically classify all runtime code as bundled
Nuvio payload, or automatically exempt it. Review what is actually conveyed,
including any future offline bundle, mirror, OCI image or runtime extension.
GNOME's WebKitGTK recipe version is evidence about the GNOME runtime; it does
not replace the independent provenance of Nuvio's private patched WebKit engine.

## Retrieval, validation and remaining gate

Local source-only inputs live under
`build/compliance-inputs/platform-sources/`; the original runtime source and
Flatpak commit object retain their paths in the JSON. Nothing in these download
directories is staged for Git or uploaded. An offline reviewer can rehash them
against the JSON and read the exact recipes and notice members without running
their build scripts. To retrieve independently, use the recorded original URLs,
then verify each SHA-256 **before** extraction or use. Only the libfuse/squashfuse
hashes were already pinned by the original runtime build recipe; the two
Git-revision recipe archive hashes record this audit's retained bytes.

Completed checks: original-runtime recipe inspection; two pinned library source
hashes; original libfuse patch application in temporary data; exact Flatpak
commit-object hash; official GNOME revision resolution; pinned Freedesktop
junction/branch inspection; JSON and documentation whitespace validation.

Still required: exact remaining static AppImage input/source versions and
applicable relinking materials; complete applicable runtime component sources,
patches and notices; any conveyed extension identities; recipient-facing source
availability and review of distribution scope. Retention in one developer's
cache is not a source offer or durable recipient access. These improved source
links therefore reduce missing evidence without turning any format **READY**.

## Bundled shader and artwork evidence

A bounded source/package inspection distinguishes existing notices from specific
missing grants; it does not treat every image as unattributed. In the audited
DEB application JAR from run `38397205286`, all 16 files under `player-shaders/`
and their `LICENSE.md` are byte-identical to the repository resources. Fourteen
GLSL files contain the full MIT grant and bloc97 copyright notice. The two
`Anime4K_AutoDownscalePre_x2.glsl` / `_x4.glsl` files instead contain complete
Unlicense/public-domain text. The directory notice identifies Anime4K v4.0.1
and includes MIT text, but overgeneralizes that license to all files; the
file-level notices establish the distinction and must remain bundled. This is
concrete notice coverage, not an unresolved generic shader-authorship question.
The notice's statement that Stremio-Kai's developer encouraged the port is an
attribution statement, not independent evidence of a license grant for any
separately copied code.

Seven bundled drawable SVGs carry an SVG Repo generator comment without an
asset identifier, author, asset-specific license or retained grant:
`library_add_plus.svg`, `ic_player_aspect_ratio.svg`,
`ic_player_audio_filled.svg`, `ic_player_pause.svg`, `trailer_play_button.svg`,
`ic_player_subtitles.svg` and `ic_player_play.svg`. A website generator comment
alone does not establish their redistribution terms. Resolve these seven
specific origins/licenses instead of assuming the repository's GPL covers the
imports. Provider/rating marks (for example Trakt, IMDb, TMDB and Rotten
Tomatoes) and festival/award badges also exist in the drawable directory;
service attribution is present in the app, but no asset-specific rights record
was identified by this bounded inspection. Copyright/license evidence and any
applicable trademark/usage conditions need separate review. This is missing
evidence, not a determination that use is prohibited.

The application's own login wordmark has recorded source history, including
the dedicated compact SVG added by commit
`fce98dc1633b51f813c20965e05941c2745beb8c`. It is packaged in the audited JAR;
there is no basis here to replace the approved design. Source history does not
independently certify the rights to all earlier brand artwork. Setup preview
images are also packaged, so any embedded third-party poster/artwork rights
must be assessed on those actual images rather than on API attribution alone.

The bundled `anime-list-mini.json` has an explicit in-app attribution in
`composeApp/src/commonMain/composeResources/values/strings.xml`: Fribb/anime-lists,
anime-offline-database (cedya77/manami-project) and Anime-Lists/anime-lists,
with ODbL 1.0 / Database Contents License 1.0 and a derivative-database notice.
That is useful existing evidence, but this inspection did not locate retained
full database license texts, exact upstream dataset revision, or the
generation/source record needed to close its distribution evidence. Similarly,
the IMDb attribution says “Used with permission”; this statement is not itself
the underlying permission record. Models and fonts are covered by their
separate inventories and were not re-audited here. No artwork, shader or
application behavior was changed.
