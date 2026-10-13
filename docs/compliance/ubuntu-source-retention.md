# Ubuntu source retention evidence

**Status: both complete source-input sets retained and verified offline.** The
[retention receipt](evidence/ubuntu-source-retention.json) records the final
signature/index/lock/hash/`.dsc` checks: **1,314 inputs, 2,621,872,407 bytes** for
427 source versions, including the AppImage's 449 inputs / 140 versions.
No binary was built, installed, uploaded or changed by this work.

Snapshot retrieval initially hit HTTP 503 and low throughput. Completed inputs
were rehashed and reused; remaining inputs came from Ubuntu's official primary
archive under the **unchanged signed snapshot hashes and sizes**. Actual mirror
URLs are in the receipt. Neither substitution of a newer version nor a weaker
verification policy was permitted. Final verification was entirely offline.

## Signed source-to-binary mapping

The original packages are from run `38397205286`, application commit
`fce98dc1633b51f813c20965e05941c2745beb8c`. The immutable engine builder supplies
their recorded AppImage Ubuntu binary owners and exact source-package versions.
The source retrieval chain is:

1. Pinned Ubuntu snapshot `20261001T000000Z`, Jammy/main and universe across the
   base, updates and security suites.
2. Each InRelease verifies with Ubuntu archive fingerprint
   `F6ECB3762474EDA9D21B7022871920D1991BC93C`, using an isolated GPG context.
3. Signed release SHA256/size fields authenticate each plaintext Sources index.
4. Exact recorded source-package names and versions, including epochs, select
   the `.dsc`, original archives and Debian modification inputs. Newer versions
   cannot substitute for missing versions; conflicting records fail.
5. Retrieval verifies every input SHA256/size, then cross-checks the `.dsc`
   source identity and full referenced-input closure. No archive extraction or
   upstream build-script execution is necessary.

APT's validity-date check is disabled solely for the fixed historical snapshot;
signature, suite/codename and hash checks remain mandatory. The original builder
image identity is recorded in both locks. A source-package maintainer's optional
clear-sign is not the trust anchor: the signed Ubuntu index authenticates its
exact `.dsc` bytes. No separate maintainer signature claim is made.

## Separate package and container scopes

| Scope | Locked source versions | Unique inputs | Evidence |
| --- | ---: | ---: | --- |
| AppImage's recorded Ubuntu package owners | 140 | 449 | [AppImage source lock](evidence/ubuntu-source-lock.json) |
| Engine OCI installed Ubuntu packages | 427 | 1,314 | [OCI source lock](evidence/oci-ubuntu-source-lock.json) |

[OCI package evidence](evidence/oci-ubuntu-packages.json) records **587** installed
packages in the canonical seed and **847** in the engine. These are installed
dpkg-package counts, not a census of all files in their OCI layers. The larger
source lock maps the engine's installed packages; the canonical seed's version
mapping remains separately recorded and all 587 exact binary/source versions
were verified to be a subset of the engine map. This does not certify all deleted
layer contents, source files copied outside dpkg, the full downloaded JDK/CMake
toolchain, or every statically incorporated component.

The AppImage owner scope also includes resource/data packages; it is not a claim
that every locked package supplies an ELF. Conversely, build-only tools absent
from application packages may still be conveyed inside an OCI. Neither category
automatically creates a license exemption. Original package copyright/notices,
build modifications and exact artifact hashes remain in the original audit.

## Safe retry and verification

See [tool commands](../../tools/linux/packaging/compliance/README.md#ubuntu-signed-source-locks-and-retention).
The content-addressed store reuses completed files only after checking their
expected bytes. It rejects symlink roots/ancestors, existing partials, wrong
size/hash, unsafe paths and concurrent final-file replacement. Source locks and
output files are not overwritten to make a retry pass. A network retrieval error
must not be converted into a complete-retention receipt.

An offline verification of the AppImage lock alone does not prove the additional
OCI inputs are present. This audit also verified the OCI lock against the combined
`build/compliance-inputs/ubuntu-retention/all-archives/` store. Locks and retained archives
remain local review evidence; no recipient-facing offering or redistribution
permission follows from successful verification.

Still separate: incorporated native dependencies, non-dpkg tools/OCI layer
closure, applicable source/relink/install instructions, license/notice review and
a durable approved recipient source channel. See the
[delivery contract](source-delivery-plan.md) for that boundary.
