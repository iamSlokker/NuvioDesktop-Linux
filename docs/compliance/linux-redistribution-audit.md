# Linux redistribution audit — 2026-10-13

**Engineering assessment: BLOCKED for DEB, Flatpak and AppImage.** This is a
source/provenance and licensing-evidence review, not legal certification or
permission to publish. No distribution binaries were rebuilt or redistributed for this audit.

## Evidence boundary

- Reviewed repository baseline: `a96ce242f084a41ecf433c206958f1cbffa53345` (`linux`).
- Inspected [Actions run 38397205286](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38397205286),
  successful three-format packaging of **`fce98dc1633b51f813c20965e05941c2745beb8c`**.
  These packages predate the Windows updater corrections merged into the audit
  baseline; they are not represented as packages of `a96ce242`.
- Downloaded artifact ZIP: SHA256
  `575f3b365999dbbfa461e5676140be84badcb91cf7b9aba53f91d5a1b39262d0`,
  matched against GitHub's authenticated artifact metadata. All contained
  `SHA256SUMS` entries were independently checked.
- DEB extracted with ar/zstd/tar; AppImage runtime used only for extraction;
  Flatpak imported/read back in a new isolated OSTree repository. No application,
  GTK/WebKit window, installed Flatpak, personal profile or audio device was used.
- Immutable engine builder `a465827efdede9029b1560ce793085a115508500bad83889f167530efcd81ed2`
  was loaded from the already retained OCI, SHA256
  `d6c0c8b5658ce2a9faf724e78336ac0a07f95a9585fc2939583e6e7ae826424f`.
  Read-only, network-disabled dpkg queries supplied exact Ubuntu source-package
  names/versions and original file-owner evidence. No engine compilation occurred.

The [machine-readable inventory](linux-third-party-inventory.json) contains 322
component records, exact packaged file/JAR hashes, 22 embedded native binaries,
source URLs or explicitly missing source evidence, POM declarations, notice
hashes, modifications, and package-format scope. It maps all 125 third-party JARs
and all inspected ELF payloads without unresolved top-level owners. It is **not**
a complete inventory of every statically incorporated subcomponent of Skia,
ONNX, WebKit, Jansi or the AppImage runtime; that closure remains a blocker.
The 126 application/dependency JARs are byte-identical in all three formats.
There are 47 loose DEB ELF objects, 46 Flatpak ELF objects, and 408 AppImage payload
ELF objects plus its outer runtime. Non-Linux natives present inside JARs are
still redistributed and are inventoried; unused bytes are not exempted.

[CI/source-access evidence](evidence/run-38397205286.json) and
[engine Ubuntu provenance](evidence/engine-ubuntu-provenance.json) contain no
credentials or developer paths. Metadata observations and declared licenses are
evidence for review, not independent attestations of upstream legal rights.

| Artifact | SHA256 |
| --- | --- |
| DEB | `997ce148d14565d775fde74ed196da9686571badb1421d220ab22c69889f67be` |
| Flatpak | `687dddba1e5f94b2969b1482ea450775b5f5dfe9346f7a15bf0b6cb6b1fe66d0` |
| AppImage | `946c8d8d04f1b691604dc324267462ddeb11743820a39422e033fff8f3ff104a` |
| Application source | `af04e1559ae2a83e5d4fdca015374df78f2a1a27750a4a9347db69d4640c8fdf` |
| WebKit/GTK source | `2e6b4e73731695ec59d7590fedba3567566ae1a0afbd10a329e0d294ed82bb7b` |

## Confirmed findings

### CS01 — application source integrity is good; dependency closure is incomplete

`formats.py` retains the canonical `source-files.json`, `source-info.json` and
application tree. All **1,819** recorded files match their SHA256 and Git blob at
the packaged commit; no unlisted application file was accepted. Linux JNI source,
media manifests/recipes/patches, Gradle wrapper, public build defaults and the
application GPLv3 text are present. Private `local.properties` and developer
configuration are excluded. The archive also contains model/font object assets
and the Gradle wrapper binary; their presence is not proof of corresponding
source or redistribution rights.

This archive does not retain the source tarballs for private media, JDK sources,
Maven source artifacts or the source closure of embedded native dependencies.
It is therefore not a complete third-party Corresponding Source distribution.
The packaging entry point uses Git metadata that is absent from the tar. Gradle
can consume the application snapshot after dependencies are supplied, but a
fully offline, snapshot-only reproduction of the canonical packaging invocation
is not currently supplied. There is no retained, complete resolved dependency
source/binary lock with all required build inputs.

**Change proposed:** put a `SOURCE-README.txt` inside each future application
source archive, accurately stating its scope and rebuild prerequisites. The
archive routine is tested for byte/mode preservation, tampering, path traversal,
private configuration names and escaping links. This does not certify completeness.

### CS02 — private media sources were not exposed with the audited packages

The pinned media manifest covers 18 upstream inputs, including build tools and
incorporated SDK/data inputs. The packages retain license texts, original runtime
inventory, compile features, recipe/build identity and source patches. The CI
bundle retains their recipes, **not their downloaded source archives**.
glslang is statically incorporated into libplacebo; nv-codec/Vulkan headers and
hwdata can contribute compiled code/data even though their inputs are classified
as build-time. They must not be dismissed solely because no separate `.so` exists.

| Component | Exact input version | Observed license evidence / modification |
| --- | --- | --- |
| FFmpeg | 9.0.2 | Manifest LGPL-3.0-or-later; `--enable-version3`, OpenSSL, shared playback-focused configuration; strip/RPATH export |
| mpv | 0.41.0 | GPL-enabled build, upstream Copyright/license texts; safe-auto and CUDA mapper patches |
| libplacebo | 7.360.1 | LGPL-2.1-or-later; glslang static incorporation and configured third-party features |
| glslang | 14.3.0 | Mixed BSD/MIT/Apache declarations, statically linked into libplacebo |
| Vulkan loader/headers | 1.4.363.0 | Apache/MIT declarations and incorporated cJSON notice |
| dav1d | 1.5.4 | BSD-2-Clause |
| LuaJIT | 2.1.1788856981, commit c6ffc141a8762b41703f9287d63d93622a13dd8f | MIT |
| libass / libunibreak | 0.17.5 / 8.0 | ISC / Zlib |
| libdisplay-info / bzip2 | 0.3.0 / 1.0.8 | MIT / bzip2 terms |

All 18 exact URLs, SHA256s, classifications, options and patch records are in the
JSON inventory and existing media manifest, including remaining inputs omitted
from this table. The source collector uses that manifest without changing pins.
For this audit, all 18 archives plus FFmpeg's signature/public key were retrieved
and hash-verified; the FFmpeg signature also passed against fingerprint
`FCF986EA15E6E293A5644F10B4322F04D67658D8`.

A **local-only** source bundle was made, SHA256
`a2e77da1bb4757ebf5d313f53d1937ce231d6b3cc1afc834aea403d5916b64cb`.
It contains 20 verified inputs, exact tracked media recipes and a hashed receipt.
It has not been added to Git, uploaded to Actions, or offered as a completed
source distribution. Missing input, bad hash, symlink or existing destination
fails before producing/replacing an archive. Retention/source offering remains
unresolved. No binary recompilation was performed.

### CS03 — Temurin source and rebuild provenance remain missing

All formats bundle a jlink-selected runtime and jpackage launcher from Temurin
**17.0.20.1+1**, binary input SHA256
`3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e`.
Per-module GPLv2/ClassPath-exception and third-party legal files are retained.
The packaged release metadata identifies `adoptium/jdk17u` revision
`79597447bd94` (resolved through the official commit API to
`79597447bd94ff9f8216e2e3a573d5261ee49f2e`) and Temurin build scripts revision
`e6ba7dec3d07654074559310376a3ae89da5f4ac`.

No complete, hash-locked JDK/HotSpot/native third-party corresponding-source
bundle, Temurin build inputs or independently
verified source-to-prebuilt-binary attestation is offered in this run. A Java
`src.zip` alone would not establish coverage of JVM/native components. Determine
license/exception applicability and required materials with review; do not infer
an exemption merely because Java is a runtime.

### CS04 — JVM and embedded native source/notice coverage remains incomplete

The 125 third-party JARs are identified by actual cached Maven bytes and exact
coordinates, with POM hashes/declarations and candidate Maven source URLs.
Published Gradle module metadata provides source hashes for 114 of these JARs;
these declarations are recorded separately from verified source bytes. Remaining
candidate URLs have null hashes and are unresolved evidence.
No corresponding Maven source JAR was present in the inspected cache/artifact.
**86 JARs have no embedded license/notice text**; this is a coverage-review flag,
not a finding that all 86 necessarily violate their terms. POM license names and
the UI attribution page are not substitutes for required texts/NOTICE content.

Three modifications were checked against the original Maven bytes: ONNX Runtime
1.31.0 removes 13 non-Linux entries, material-icons-extended 1.7.3 removes 11,002
unused entries, and Compose extracts Skiko 0.144.6's native/sidecar from its runtime
JAR. Every retained entry is byte-identical; the loose Skiko JNI matches the
upstream JAR. Both original and distributed archive hashes are recorded.

ONNX Runtime MIT/license and ThirdPartyNotices are present, as are exact Linux
CPU JNI/core hashes. That does not prove full source/build provenance for its
statically incorporated libraries. Skiko/Skia native sources, exact incorporated
versions/notices, Jansi's multi-platform native closure, and QuickJS native
source/build inputs need separate review. JNI libraries inside JARs are not
excluded from source/notice analysis. Record the Gradle wrapper's own license and
source provenance as a build-only binary included in the source archive.

### CS05 — WebKit/GTK core sources are retained; the Ubuntu closure is not

AppImage uses WebKitGTK **2.50.4**, source SHA256
`d3bfa473845acfab72635bada5e0d134fda6792c5b95c5c5cd141b46125bd8e4`,
engine recipe `b6e4ab22c0b2dd58e8dcc85d1eb6801810bd9d89c4ce2256d87596d2b1aede60`.
The separate source archive retains the actual source tarball, both reviewed
patches, GTK **3.24.33-1ubuntu2.2** original/Debian/dsc inputs, generated resource
patches, notices, builder-package versions, production flags and modified Adwaita
resource sources. These hashes and the engine corresponding-source inventory
were checked. Developer mode remains OFF and Bubblewrap ON.

AppImage additionally copies Ubuntu native libraries, plugins, helpers, schemas,
icons and MIME data. Its 171 recorded package owners include **164 owners of
actual standalone ELF payloads** and resource/build-tool owners; the latter are
not automatically runtime binaries. All exact binary/source-package versions
are now recorded from the same immutable engine builder. The full source `.dsc`,
original archives, Debian patches and SHA256 closure for those packages are not
retained/offered. Launchpad source-record links and copyright files alone do not
close that gap. Original package-file hashes precede the documented RPATH rewrite;
the JSON separately records the actual distributed hashes.

### CS06 — Ubuntu common-license references are not self-contained

Many shipped copyright files refer to `/usr/share/common-licenses` (for example
50 to GPL-2, 43 to LGPL-2.1, and 20 to Apache-2.0). The inspected AppImage does not
bundle that directory's full text set. The host may have different paths or none;
other license texts somewhere in the image do not establish complete coverage.

**Change proposed:** future AppImage assembly copies the full common-license text
set from the same pinned builder, dereferences internal aliases, rejects escaping
links/ELF inputs, and records the copies in its existing runtime inventory.
An accompanying note explains where the referenced texts can be found. Engine
recipe/versions/sandbox and all native bytes are unchanged; assembly identity
changes as intended. This was fixture-tested, not applied to the existing artifact.

### CS07 — embedded AppImage runtime needs its own source/notice closure

The pinned type-2 runtime is **20251108**, input SHA256
`2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d`.
The embedded 944,632-byte prefix differs only in its named 16-byte `.digest_md5`
metadata section; code bytes outside it match the verified input. The embedded
prefix SHA256 is recorded separately from the input hash.

The tag resolves to `dd6cebedcbddde9c82f89b011e8e1d40b6e43868`. Its source was
retained locally for inspection, SHA256
`f5fec23be76e50e2445ed2d018bac49b367490fc483c62c4637e99ec705d27ba`.
The upstream runtime LICENSE is MIT and lists statically incorporated libraries;
it is not evidence that the entire executable has only MIT obligations.
The build recipe pins **libfuse 3.15.0** (including its mount patch) and
**squashfuse 0.5.2**, and links musl, zstd, zlib and **mimalloc**. The latter appears
in the Makefile beyond the LICENSE's dependency list. Exact Alpine package input
versions, sources, full notices and any required relinking materials are not
established by this retained runtime source alone. Review the applicable LGPL
static-linking conditions; no exemption is assumed. No runtime was rebuilt.

### CS08 — model and font rights/provenance need review

Both unchanged U2NetP/YuNet model bytes and their Apache-2.0/MIT license texts are
present, with pinned hashes and their exact Umbra snapshot origin. Confirm that
the licenses cover these particular exported weights, and what preferred form,
export/training scripts or other materials are required for the intended
redistribution. No training-data or preferred-form conclusion is inferred from
a model filename or upstream URL.

The three JetBrains Sans TTFs are bundled in the application JAR. Their SHA256s
and observed name-table copyright/name records are retained in the inventory.
They contain no name-table license/license URL, and no matching redistribution
grant was found in repository notices. This establishes **missing permission
evidence**, not a conclusion that no permission exists. Do not apply the license
of the distinct JetBrains Mono family. Obtain and retain the exact Sans grant or
make a separately reviewed asset decision before redistribution. Other app
artwork/data authorship and imported SVG/shader attribution also need closure;
existing player-shader notices are present and must remain intact.

### CS09 — Actions artifacts are an expiring, authenticated source channel

The run exposes a 646,103,339-byte package/source bundle and a separate builder
seed; both currently expire **2027-01-11 12:25:44 UTC**. Anonymous access to the
package ZIP API returned **HTTP 401**. GitHub documents that artifact download
requires sign-in/read access. Source archives share the package bundle and its
access/expiry; there is no separate durable, complete source offer for the missing
third-party inputs. Caches are not a recipient-facing source distribution.

The separate builder-seed artifact exports a full OCI packaging toolchain, not
just application sources. Build-only tools that are absent from DEB/Flatpak/
AppImage can nevertheless be redistributed in that OCI (Ubuntu packages,
compiler/toolchain, full JDK and CMake). Its source/notice closure needs a separate
container-distribution review; the 322-component application inventory does not
certify every file/layer of that seed. The standalone engine OCI has the same
separate scope concern. No build-tool exception is assumed for an actual OCI
binary distribution.

This audit did not delete, extend, replace, upload or publish an Actions artifact.
Review whether existing artifact access already constitutes conveying to
recipients and what obligations are already owed. A public release label is not
the sole trigger. Authentication alone is not declared noncompliant: GPLv3 §6(d)
allows equivalent access under its conditions, including clear directions when
source is elsewhere. Counsel/maintainers must review the actual access, cost,
retention, completeness and source-offer arrangement. Do not infer compliance
from a successful CI upload or an upstream source hyperlink.

## Package assessments and release checklist

| Format | Assessment | Blocking evidence |
| --- | --- | --- |
| DEB | **BLOCKED** | CS01–04, CS08–09: private media/JDK/JVM/native source closure, notice/font rights, rebuild/install information and source availability |
| Flatpak | **BLOCKED** | Same application/private-media/JDK/JAR gaps; additionally review exact GNOME Platform 49 commit/source closure and responsibility boundary |
| AppImage | **BLOCKED** | All shared gaps plus CS05–07: Ubuntu source closure, runtime static dependencies/relinking materials and notice coverage |
| Builder/engine OCI artifacts (separate scope) | **BLOCKED** | Full toolchain/container source and notice closure is not covered by the application inventory |

DEB host GTK/WebKit/graphics/audio libraries are external dependencies; Flatpak's
GNOME Platform is a separate runtime pinned to
`2bb946ea4ebb050f6a32f90e979feb975b08280d1ff1aac881dec3d1361ad84d`.
AppImage bundles many of those interfaces instead. The inventory distinguishes
these cases. General-purpose tools, System Libraries, independent aggregate
works, linking exceptions, and file-level/copyleft obligations require specific
license analysis; none is assumed exempt simply from its category.

Before a redistribution-ready assessment:

- [ ] Resolve the exact font/model/artwork grants and application GPLv3 version election.
- [ ] Retain/verify/offer media, JDK, required JVM/native and Ubuntu/runtime source closure with modifications and notices.
- [ ] Retain the exact dependency/build graph, checksums and source artifacts; resolve incorporated native subcomponents.
- [ ] Supply verified build, relink/repack and installation instructions for modified versions; decide whether GPLv3 User Product Installation Information applies. Preserve the sandbox/security policy.
- [ ] Review any LGPL static/relink conditions and applicable exceptions; review codec/patent and service/trademark permissions separately where relevant.
- [ ] Provide a durable, complete recipient-facing source mechanism and clear binary-to-source directions; review obligations for existing CI recipients.
- [ ] Re-run source/binary inventory checks on the actual future distribution artifacts and obtain maintainer/legal review. Functional package gates alone do not clear this list.

## Retrieval, reproduction and validation

See [tool instructions](../../tools/linux/packaging/compliance/README.md) for exact
inputs, offline inventory generation and source collection. Candidate source
URLs with a null SHA256 are unresolved evidence, not pinned source inputs.
The local media collector supplies only its stated scope; it cannot turn the
complete application's assessment green.

Validation: the complete packaging regression suite passes (104 tests, including
17 new compliance tests), with external networking isolated and all display/live
flags unset. Actual artifacts, package JAR equality, source Git objects, native
owner metadata, WebKit/GTK source inputs, runtime prefix and FFmpeg signature
were checked independently. Python syntax and `git diff --check` pass. No full
packaging run, binary rebuild, WebKit compilation, GUI launch or install occurred.

Primary references for review:
[repository GPLv3 text](../../LICENSE),
[GPLv3 §§1, 5–6](https://www.gnu.org/licenses/gpl-3.0.html),
[LGPLv3](https://www.gnu.org/licenses/lgpl-3.0.html),
[FFmpeg's source/configuration checklist](https://ffmpeg.org/legal.html),
[mpv Copyright](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright),
[Temurin licensing](https://adoptium.net/about/licensing/),
[pinned runtime LICENSE](https://github.com/AppImage/type2-runtime/blob/dd6cebedcbddde9c82f89b011e8e1d40b6e43868/LICENSE),
[GitHub artifact access/retention](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts).
