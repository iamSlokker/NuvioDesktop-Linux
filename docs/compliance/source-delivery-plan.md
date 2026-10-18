# Binary-to-source delivery contract

**Status: READY FOR REVIEW as a design and local evidence set; BLOCKED for
recipient delivery.** No source or binary artifact was uploaded by this mission.
This is an engineering contract for review, not a legal opinion or permission to
redistribute. A license notice, upstream URL or successful download is not a
complete source offer.

The [current release-preparation supplement](release-closure.md) records the
licensed font correction, notice closure, validation and remaining finite gates.

## Identities and retained materials

Use the binary's own `release-info.json`, never the current Git branch name.
The audited run **38397205286** packages
`fce98dc1633b51f813c20965e05941c2745beb8c`; the later `linux` baseline and these
Draft PR changes are not represented by those artifacts.

The immutable review set consists of these linked records:

[The review index](evidence/source-review-index.json) binds the original artifact
identities, these evidence records and the verifier scripts by SHA256. It always
reports local review only, not source-delivery or redistribution approval.

| Record | Binding and coverage |
| --- | --- |
| [Original inventory](linux-third-party-inventory.json) | Actual DEB/Flatpak/AppImage hashes, all inspected payloads, original/distributed JAR bytes, exact original application Git blobs, WebKit/GTK sources and patches |
| [Original CI receipt](evidence/run-38397205286.json) | Outer artifact digest, run/source identity, existing source archive hashes, local media source archive, FFmpeg signer and source-access limits |
| [Dependency retention](evidence/dependency-source-retention.json) | Exact original Maven binary → POM/module → source SHA256, signed full Temurin source/SBOM and build scripts (builder OCI and JVM releases), GraalVM CE 25.0.2 binary and graal/labs-openjdk/mx source pins (native-image packages), native root sources, model and font evidence |
| [Ubuntu source lock](evidence/ubuntu-source-lock.json) | Actual AppImage package owner → exact binary/source version → Ubuntu-signed source index → each `.dsc`, original and Debian source input SHA256 |
| [Platform source evidence](evidence/platform-source-retention.json) | Exact AppImage runtime source/static input pins and GNOME runtime commit → upstream build recipe, with unresolved gaps |
| [OCI package evidence](evidence/oci-ubuntu-packages.json) | Separately scoped full installed-package inventory in the immutable engine and canonical seed; does not certify every OCI layer/file |
| [OCI source lock](evidence/oci-ubuntu-source-lock.json) | Engine's 847 installed Ubuntu packages map to 427 exact source versions and 1,314 signed-index inputs; separate from non-dpkg/layer closure |
| [Ubuntu retention receipt](evidence/ubuntu-source-retention.json) | Both full input sets rechecked offline, including every descriptor's closure; canonical seed's 587 package versions match the engine map |
| [Inter replacement](inter-font-replacement.md) | Superseded 2026-10-18: Linux bundles upstream's JetBrains Sans again (owner decision); historical record of the Inter pins and OFL |
| [Modified-build instructions](modified-build-and-install.md) | Concrete private-library/repack entry points and per-format installation boundaries; incomplete native/static rebuild and recipient-delivery gates remain explicit |

Historical findings in the original inventory describe the **original CI
artifact**, whose bytes were not altered. Supplemental receipts record later
local retention. Their availability must never be reported as if those materials
had already accompanied the original binaries. Source archives containing
precompiled JNI or binary test fixtures are not automatically complete preferred
source forms; retain the source/build mapping separately.

The original 18 media-manifest inputs/signature/key were reused, not downloaded or compiled
again. This comprises **17 source archives and one data-only CA bootstrap `.deb`**,
plus the signature/key. The existing 20-input bundle remains intact; it must not
be described as twenty source archives. The collector now labels that bootstrap
binary explicitly, and its corresponding Ubuntu source is covered separately
in the OCI source lock. WebKit's original
source, both reviewed patches, GTK source/Ubuntu patches and generated-resource
recipes are retained. No engine recipe/version/security setting changed.

## Concrete future recipient layout

After rights and the required source/relink scope are approved, prepare a
versioned, read-only directory keyed by the **full application source SHA** and
the exact engine/media identities:

```text
<full-source-commit>/<artifact-set-sha256>/
  BINARY-SOURCE-MAP.json          exact original filenames, binary hashes and scope
  SHA256SUMS                     every distributed source/notice/input below
  SOURCE-README.txt              recipient instructions and approved contact/channel
  application/                  exact snapshot + verified inventory/Git identity
  recipes/                      exact application/media/engine/package build inputs
  media/                        existing strict source collection
  jvm/                          verified source JARs, POM/module evidence, notices
  native-image/                 exact input JARs, arguments and metadata hashes (relink)
  jdk/                          GraalVM CE source (graal, labs-openjdk, mx) for packages;
                                Temurin source/scripts/SBOM for the builder OCI
  native/                       required Skia/ONNX/QuickJS/Jansi source/build closure
  webkit-gtk/                   existing verified source collection and patches
  ubuntu/                       signed-index evidence, source lock and original inputs
  appimage-runtime/              runtime source, static libraries and relink materials
  notices/                      complete applicable grants/copyright/NOTICE texts
  REBUILD.md                    tested modified-source instructions and prerequisites
```

DEB/Flatpak/AppImage may reference a shared immutable source set with an explicit
per-format applicability map. External host libraries, separately supplied
Flatpak runtime/driver extensions and build tools must be identified distinctly.
Do not assert a System Library, aggregation, compiler or linking exception merely
from placement. Sources for a separately redistributed OCI require their **own**
map covering its layers, full JDK, CMake wheel and other non-dpkg tools; an app's
140-source Ubuntu lock is not a container-source certification.

Generate the final map only after rehashing the actual selected source files and
binary artifacts. Record whether a hash came from a signed publisher/index,
published SHA256, weaker historical checksum or first local retrieval. Reject
missing/changed/duplicate inputs; never update pins to make verification pass.
The existing tools provide that local verification boundary. Required materials
that remain missing must keep the offer incomplete, even if every retained file
matches its digest.

## Retrieval and offline review

See [tool commands](../../tools/linux/packaging/compliance/README.md),
[JVM/native retention](dependency-source-retention.md) and
[platform evidence](platform-source-evidence.md). Download source/text only;
do not execute downloaded build scripts or expand archives into a working source
tree during verification. The Ubuntu collector obtains only source inputs and
uses exact signed versions; it never installs packages or substitutes newer
sources. Its full archive set includes Debian packaging/modifications through
the `.dsc` and signed `Sources` checksums.

Recipient rebuilding needs the matching source commit, Gradle wrapper/dependency
graph, pinned JDK and media/engine recipes. The current application tar omits Git
metadata required by the packaging orchestrator. Until a reviewed snapshot-only
bootstrap exists, obtain the exact Git objects independently and compare every
snapshot byte before building. The additional source receipt does not solve
offline bootstrap, all external toolchain inputs or whole-package bit-for-bit
reproducibility. Do not reconstruct a different commit and label it original.

Concrete current entry points, per-format installation boundaries and the
remaining untested/static-link gaps are recorded in
[modified build and installation instructions](modified-build-and-install.md).
They do not claim a completed offline rebuild or authorize publication.

For modified shared media/JNI libraries, preserve their ABI and private image
layout, rebuild using the retained options/patches, and repackage through the
normal integrity gates. Do not disable sandboxing, forge receipts or directly
mutate a verified image and reuse its old audit. AppImage static runtime and other
static/native combinations need reviewed relink materials/instructions under
their applicable licenses; a runtime source tar alone does not prove this.
Native-image packages compile the application, Maven JARs and the JDK class library
into `lib/nuvio`; `Nuvio-native-image-inputs-*.tar` carries the exact inputs and
arguments needed to rebuild it with a modified library, but a modified-library
rebuild has not been tested. Building a modified GraalVM from the pinned graal,
labs-openjdk and mx sources is upstream's documented mx procedure, likewise untested
here. Temurin's retained `doc/building.md` and build scripts cover the build JDK.

Each CI release run's `compliance/release_sources.py` binds the Ubuntu source lock
to the built AppImage by **exact binary version**, not by builder image ID. For
every package in the AppImage's `share/licenses/Ubuntu-AppImage/packages.json`, the
version installed in the engine builder must equal a locked binary
`{package, version}`. Those versions come from
`engine/webkit-builder-packages.tsv` in the `Nuvio-WebKit-source-*` archive shipped
next to the AppImage. Assembly already requires that file to equal the assembling
container's own dpkg inventory. Its `engine-build.json` must match the AppImage's
`runtime.json` engine identity and the file's recorded SHA256. A rebuilt builder
from the same pinned snapshot therefore keeps the lock valid. A missing package or
any different version fails the run until the lock is regenerated. The lock's
`builder_image_id` stays historical provenance for its original derivation.

## Availability and existing recipients

The proposed channel is a durable, versioned source download, explicitly linked
where each binary is offered, with access/cost no more restrictive than the
applicable source obligation allows. Owner approval is required before selecting
hosting, publishing material or making any written offer. The current GitHub
Actions bundle is authenticated and expires **2027-01-11 12:25:44 UTC**; caches
are not a recipient-facing source channel. Do not delete, alter or republish it
to conceal a gap. Review obligations to existing recipients now, even without a
GitHub Release.

Proposed engineering retention policy: retain the immutable source maps and
required materials for as long as the associated binaries are available, plus
at least three years after the last conveyance, with a second verified copy and
periodic accessibility/hash checks. This is a conservative **proposed operational
policy**, not a statement that all license clauses impose the same period.
Counsel/maintainers must select the actual applicable delivery method and its
duration; do not advertise an unapproved written source offer.

Review [GPLv3 §§1, 5–6](https://www.gnu.org/licenses/gpl-3.0.html),
[LGPLv2.1 §6](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) and
[LGPLv3 §4](https://www.gnu.org/licenses/lgpl-3.0.html) for the actual combinations.
GPL/LGPL source and modification duties, LGPL replacement/relink options,
permissive copyright/license/NOTICE conditions, Classpath/compiler exceptions and
GPLv3 Installation Information applicability are separate decisions. Retaining
all available sources is useful engineering evidence, not a claim that every
permissive library requires public source or that every prebuilt needs an
independent bit-identical rebuild as a legal condition.

## Exit criteria

1. The owner-authorized Inter replacement resolves the future payload's missing
   Sans grant. Validate its actual package bytes and typography before acceptance;
   it does not resolve historical font distribution. Review the remaining
   model/artwork rights and applicable license elections/exceptions.
2. Finish required native/static/build/relink source and notice closure; obtain
   absent upstream input identities instead of guessing. Recheck the source map.
3. Approve and test a durable recipient channel and instructions for both future
   distributions and existing recipients, including separately conveyed OCIs.
4. Only then authorize a new candidate package build/source upload, verify its
   own immutable identities, perform the pending targeted acceptance, and make a
   separate merge/release decision.

No new public binary-producing workflow is authorized by completion of this
document or a passing local source check.
