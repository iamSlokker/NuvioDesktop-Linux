# Retained JVM, native and asset source evidence

This supplements the [redistribution audit](linux-redistribution-audit.md).
It closes local retrieval gaps; **it does not authorize redistribution**, establish
complete native Corresponding Source, or make the existing Actions artifacts
complete. The audited packages remain those of `fce98dc1633b51f813c20965e05941c2745beb8c`.
No distribution binaries, installed applications or personal settings were changed.

## Maven sources and notice review

All **125** matching Maven source JARs are now retained locally (24,199,011 bytes).
The [receipt](evidence/dependency-source-retention.json) records each exact URL,
SHA256, original binary SHA256, POM/module metadata hashes, source-content counts,
license declarations, embedded-native hashes and notice evidence.

- **114** match the SHA256 published in already recorded Gradle module metadata.
- **2** match Maven repository SHA256 sidecars.
- **9** have only a published SHA1 sidecar. Those were checked and a SHA256 was
  independently calculated and locked for subsequent retrieval. This is weaker
  upstream authentication than a signed source or prior SHA256 pin; do not call
  SHA1 collision-resistant or treat these receipts as build attestations.
- Each original POM and available module metadata was copied from the existing
  cache and checked against the prior inventory SHA256. The verifier binds the
  source receipt to the actual original binary identity and package commit.
  SLF4J's two parent POMs were also retained and checked, preserving the exact
  inheritance chain that supplies its MIT declaration.

The prior **86** missing-embedded-notice flags were reviewed through a reproducible
classification of actual source/binary JAR contents, separately for every row:

| Category | JARs | Meaning |
| --- | ---: | --- |
| Packaged notice text present | 39 | Existing standalone text, with its path/hash retained |
| Source copyright/license headers plus POM declaration | 65 | Header examples/hashes retained; no standalone packaged notice established |
| POM declaration only | 21 | Sources have no detected license header/text; obtain exact upstream notices/grant |

These categories are **not infringement findings or compliance approvals**.
Headers and POM names do not replace full license/NOTICE obligations. Copyright
icon source files are specifically excluded from standalone notice detection.
Source archives are not universally source-only: Jansi's source JAR contains
**12 precompiled native files**. Skiko's Linux runtime source JAR is **manifest-only**;
it provides no Skia/native source. Empty forwarder source classes in several
Compose coordinates also require considering the separately inventoried provider
artifacts; a sources JAR existing is not enough to certify the complete build.

No source JAR has been added to Git or uploaded. A future recipient bundle must
retain the required original license/copyright/NOTICE text, applicable source
forms and build instructions, and cover incorporated native components. Do not
publish the local cache as a supposedly complete source offering.

## Temurin 17.0.20.1+1

Retained the **official full JDK source archive**, rather than just Java `src.zip`:

- Source SHA256: `21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214`.
- Source revision: `adoptium/jdk17u@79597447bd94ff9f8216e2e3a573d5261ee49f2e`.
- Build scripts: `adoptium/temurin-build@e6ba7dec3d07654074559310376a3ae89da5f4ac`,
  archive SHA256 `b1340378b9ed62b32acfacd012dd069ec2b9d940fc39d3c73f32142d64e89713`.
- Official binary metadata matches the already packaged JDK input SHA256
  `3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e` and
  identifies the same source/build revisions.
- Source archive and official SBOM signatures both verified with fingerprint
  `3B04D753C9050D9A5D343F39843C48A565F8F04B`, as documented by
  [Adoptium](https://adoptium.net/installation/gpg-verification/).
- Official release asset SHA256s also verified the source, signatures, metadata
  and SBOM. Source scope checks identify HotSpot, native implementation, jlink,
  jpackage, configure/make, root license and `doc/building.md` in the 82,049-entry
  archive. Hashes of representative scope files are retained in the receipt.

The upstream build used GCC 10.3.0, binutils 2.37, boot JDK 17.0.20+8 and the
immutable image digest recorded in the receipt. Metadata reports static
libstdc++ linking and bundled freetype/libjpeg/giflib/libpng/zlib/lcms/harfbuzz.
Their applicability must be assessed by actual terms and incorporated material;
no general runtime or compiler exception is presumed. Official metadata/signature
is publisher provenance, **not our independent bit-for-bit rebuild attestation**.

Still missing: complete external build/toolchain input and incorporated-runtime
closure as applicable, reviewed exception/source obligations, recipient retention,
and tested modified-jlink/package rebuild/install instructions. The full source,
SBOM and pinned build scripts materially improve the evidence but do not alone
clear those requirements. No JDK compilation was attempted.

## Native dependencies

Version-matched source archives and hashes are retained for:

| Component | Source revision | Remaining boundary |
| --- | --- | --- |
| ONNX Runtime 1.31.0 | `402979ac1bef89dcd55add41eaa7277c407599eb` | Dependency recipes and upstream SHA1 pins retained; resolved CPU build's incorporated archives, flags and binary provenance need closure |
| Skiko 0.144.6 | `9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0` | Recipe names Skia `m144-22f58c9fd4`; Skia DEPS, source/native toolchain and upstream prebuilt identity remain separate |
| QuickJS-KT 1.0.15 | `a081755bcdb18ee6afa7ffee25b8fa976f06d31f` | JNI build provenance/notice review remains; both exact native gitlinks are retained |
| Jansi 2.4.2 | `3d2a9788fa48e4cecbbe28279d01111a125d2f66` | Native C/Makefile/Maven recipes retained; distributed multi-platform native build closure still needs verification |

QuickJS gitlinks are Bellard QuickJS
`04be246001599f5995fa2f2d8c91a0f198d3f34c` and c-vector
`774773d4cb1e66dd736e90e7f482d4f22af464c6`. Their source archives were retained and
hashed, without executing upstream build scripts. The source receipt does not
assert that downloading root repositories resolves all nested dependencies or
that version-matched tags independently attest to prebuilt JNI bytes.

## Fonts: a specific unresolved permission issue

The official [JetBrains issue WRS-2431](https://youtrack.jetbrains.com/issue/WRS-2431/Question-JetBrains-Sans-Font)
contains a 2023-08-01 staff response limiting the unreleased JetBrains Sans to
Writerside use. Later staff responses do not supply a general redistribution
grant. In a 2024-07-05 response, its author recommends Poppins, Work Sans or Inter
as alternatives while awaiting a public release. The current retrieved issue
contains no later grant for the three packaged TTFs. The receipt retains the
API response hash and exact packaged font metadata. The
[JetBrains brand page](https://www.jetbrains.com/company/brand/) identifies Sans
as a trademark; that is not a general font redistribution license. The distinct
JetBrains Mono license must not be substituted.

This is stronger than simply failing to find a license file. **Obtain an exact
redistribution grant or review a replacement before distributing these TTFs.**
No font was replaced during this audit. A reversible replacement must cover the
Compose `NuvioTheme.kt` default and WebKit HUD font resources/CSS together, retain
400/600/700 weights and test width, wrapping, profile grid and HUD layouts at the
supported scaling factors. An already installed system-font choice does not
remove the original font bytes from packages and therefore cannot alone solve
redistribution. A system `sans-serif` default removes the extra font asset only
if the original assets are also excluded; it does not promise matching metrics.
No fallback is represented as a verified drop-in or silently applied to Windows,
macOS or the approved brand wordmark.

## Models

- Downloaded the original rembg U2NetP export for comparison only: its SHA256 is
  exactly `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`, matching
  the packaged model. The original U-2-Net Apache-2.0 license at the already
  pinned revision matches the packaged license bytes.
- OpenCV Zoo's pinned YuNet Git LFS pointer names exactly
  `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`, matching the
  packaged model. The model-directory MIT text at the same revision matches the
  packaged license. Both README/license texts and pointer are retained locally.

This closes the earlier uncertainty about matching each binary to an upstream
model distribution. The applicable grant, export/preferred form and any necessary
training/data/source materials still need component-specific review; no such
legal obligation or exemption is inferred merely from the ONNX format. No model
was modified, removed or trained. This source-retention step did not execute the
models; the separate headless CPU inference tests are recorded in the
[final readiness report](../FINAL-READINESS-REPORT.md).

## Offline recheck and retrieval

The untracked inputs live under `build/compliance-inputs/dependency-sources/`.
All required relative paths, exact URLs and SHA256s are in the receipt. Retrieval
must preserve those bytes; never update a pin merely to make a download pass.
Sources, POMs and Gradle metadata are verified by this network-free command:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  PYTHONDONTWRITEBYTECODE=1 \
  python3 tools/linux/packaging/compliance/dependency_sources.py \
  --inventory docs/compliance/linux-third-party-inventory.json \
  --receipt docs/compliance/evidence/dependency-source-retention.json \
  --sources build/compliance-inputs/dependency-sources
```

The tool rejects missing/tampered inputs, replaced published source pins, mismatched
binary/package identity, incomplete or duplicate component coverage, altered POM/
module metadata, source path traversal/symlinks and unsafe JAR members. It hashes
additional retained JDK/native/text inputs, without extracting or running them.
Its successful result is explicitly local-retention verification, not release
approval. The JDK signature can separately be rechecked with `gpgv` and a disposable
keyring containing only the documented fingerprint; never use personal keys.

Eleven deterministic fixture tests cover these contracts, including native-containing
and empty sources JARs and the Copyright-icon false positive. No network, GUI,
app launch, native compilation, binary upload or installed data is used by the tests.
