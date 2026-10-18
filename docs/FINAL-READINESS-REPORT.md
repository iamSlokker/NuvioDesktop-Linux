# Final engineering and release-readiness report

## 2026-10-14 update (supersedes the status lines below)

**2026-10-17 owner decision:** the Linux build now ships Umbra's original artwork unchanged, as upstream; only the four setup previews remain fork replacements. See [Owner decision 2026-10-17](compliance/artwork-data-closure.md#owner-decision-2026-10-17-upstream-artwork). The artwork statements below are historical.

This is a technical/compliance-engineering review, not legal certification or
authority to publish. The 2026-10-13 report below is kept unchanged as the
evidence of that date.

**Engineering and packaging gates: closed for DEB, Flatpak and AppImage at the
evidence commit below.**
**Redistribution: PENDING MANUAL PUBLICATION** (the owner decisions are resolved;
see the end of this section); no release has been created or published.
**HDR10: unchanged, deferred and optional.**

### Evidence build

- Run [38538334113](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38538334113),
  channel nightly, commit `78cf22ef2d7ba62567056951f63775ddb868d98d`
  (Umbra snapshot `b54972abf` integrated). Every step succeeded: canonical image and
  DEB, DEB verification in its pinned builder, DEB install smoke on Ubuntu 22.04,
  Flatpak export and verification, AppImage with the accepted HUD-corrected WebKit
  engine run 38327066209, corresponding third-party source, checksums.
- The current `linux` head `32e42f5d0` adds only test-fixture changes (#24) on top.
- Owner acceptance on Bazzite/Wayland of this build: installation and normal use
  reported working. One UI issue was found in shared upstream code (the docked home
  hero source list does not close when another poster is hovered in TV mode); it is
  byte-identical to Umbra `b54972abf` and left for upstream.

| Asset | SHA256 |
| --- | --- |
| `nuvio-desktop_1.15.0~nightly+git.78cf22ef2d7b_amd64.deb` | `c84d90197b1809b3b2031b7981e77f63f6ee5b510abe541751fdf4c6aae86213` |
| `Nuvio-Desktop-1.15.0-78cf22ef2d7b-x86_64.flatpak` | `e83c91b8cc7884d2b163c85b4f5e676298061aae7c2211acc559c79a5f88564f` |
| `Nuvio-Desktop-1.15.0~nightly+git.78cf22ef2d7b-x86_64.AppImage` | `19f0b5aa33ed192e3c63903bc695c2ddd95957e5dad332ebbcce4d91834c9a78` |
| `Nuvio-source-78cf22ef2d7b.tar.gz` (application) | `d1fc8a74b36715c84913f7042f3a0bc95ede21e4e895e24382c93cf63c7ae06d` |
| `Nuvio-WebKit-source-b6e4ab22c0b2dd58-430db445474d.tar.gz` | `08e1393103e9c24ac5a6c19dd038c7e8bce0248e282d8b5bf90ba33d00bc86b9` |
| `Nuvio-AppImage-runtime-source-6ee696672e2a.tar.gz` | `74c79ac35e631513e1862408f5c5cfecb05b37df3e90952eb82a212446776917` |
| `Nuvio-media-source-78cf22ef2d7b.tar.gz` | `0d7b9156fc27f1e893b5ba2a537f4ad58760167428d54157ba479df7bfda077d` |
| `Nuvio-AppImage-Ubuntu-source-78cf22ef2d7b.tar` | `30a6b24e4ff0eb165bec1b5294499c55032ad8097c01b77a04a8917a295a3c08` |
| `OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz` | `21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214` |
| `SOURCE-MAP.json` | `2bbb74d887b4311523317fe6240347b530a718a81a4d55b6ca875072fb008761` |

Builds after the bundled JDK moved to Temurin 21.0.12.1+1 emit
`OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz`
(`573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234`) in place of the
Temurin 17 source asset listed above for this evidence build.

The Actions artifact containing these files expires on 2027-01-12; it is evidence,
not the recipient delivery channel.

### Blockers closed since 2026-10-13

| Former blocker | Resolution | PRs |
| --- | --- | --- |
| Packaging verifier crashed when only `tools/linux/packaging` was mounted | Lazy repository resolution; reviewed source mounted for DEB and AppImage verification | #9, #21 |
| Unclassified template brand mark | Removed and forbidden in JARs | #10 |
| Anime mapping dataset rights chain | Not redistributed: excluded from the Linux snapshot, JAR and source tarball; the app downloads the pinned revision and verifies size and SHA256 | #11 |
| Native notices (Skiko/Skia and its static set, ONNX Runtime, QuickJS, c-vector, Jansi) | 24 pinned texts shipped in `share/licenses/third-party/` in all formats; verified per format | #12 |
| 47 provider/rating and award/hero artwork resources | Replaced in Linux packages (overlay) by 46 original MIT artworks and, for `rating_tmdb`, TMDB's official attribution logo; upstream files and other platforms unchanged; originals forbidden in JARs | #13 |
| AppImage runtime with unrecoverable static inputs; LGPL libfuse relink | Runtime rebuilt reproducibly from pinned sources (`6ee69667…`), notices shipped, source and relink kit published with each AppImage | #14, #18, #19, #20 |
| No recipient source delivery | Every build emits application, WebKit/GTK, AppImage runtime, media, Temurin and AppImage-Ubuntu source plus `SOURCE-MAP.json`, gated in CI. Owner decision: deliver as assets of the same GitHub Release | #15, #16, #22 |
| WebKit engine reuse on `linux` | `linux` may consume only the accepted engine run 38327066209 (recipe byte-identical) | #17 |
| Stale Linux storage test fixtures | 523 Linux JVM tests pass (52 display/native-flag gated tests skipped by design) | #24 |

Trusted builder seed inputs (`build.py`, `config.json`, Containerfile, media
runtime) were kept byte-identical throughout.

### Owner decisions before publication

Resolved on 2026-10-14:

1. **TMDB attribution — resolved.** TMDB's terms require applications using its
   data or images to attribute TMDB, and TMDB provides logos for that purpose
   ([logos & attribution](https://www.themoviedb.org/about/logos-attribution)).
   Linux packages now carry TMDB's official "Primary full (blue)" attribution logo
   as `rating_tmdb.png`, rendered unmodified from the vendored, hash-pinned SVG
   (`d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b`) and recorded
   as `TMDB-attribution-terms`, not MIT. See
   [artwork-data-closure.md](compliance/artwork-data-closure.md#linux-package-artwork-overlay).
   The evidence build above predates this change (it carries the former text mark),
   so publish only a build that includes it.
2. **Final review — accepted.** The owner accepted the final review of model-file
   terms and of plain provider names used in text on 2026-10-14.

Remaining:

3. **Publication:** create the version tag, let CI build the stable channel, and
   publish the packages together with every source asset listed in that build's
   `SOURCE-MAP.json` in the same GitHub Release. There is no automated release job;
   publication is a manual, separately approved step.
4. **Earlier artifacts:** packages from before these changes (for example run
   38397205286) did not carry this source set; do not redistribute them.

---

## 2026-10-13 report

Evidence date: **2026-10-13**. This is a technical/compliance-engineering review,
not legal certification or authority to merge or publish.

**Development use: technically ready to continue from the accepted baseline.**
**Redistribution: BLOCKED for DEB, Flatpak, AppImage and separate OCI artifacts.**
**Genuine Linux HDR10 output: not implemented or hardware-accepted; optional
architectural work, not an additional redistribution prerequisite.**

## Repository and artifact state

- GitHub `linux`: `a96ce242f084a41ecf433c206958f1cbffa53345`, rechecked before work.
  PR #2 and PR #3 are integrated; neither history was changed or replayed.
- PR #4: [corresponding-source audit](https://github.com/iamSlokker/NuvioDesktop-Linux/pull/4),
  branch `audit/linux-corresponding-source-2026-10-13`, initially
  `ef221b72d6f759387d6542521ce3c8e0588c9128`, open/Draft/mergeable at review.
  Reviewed engineering/evidence commit: **`a85a9fec3bf333f3a89eff16ac4c734182879b0b`**.
  This final report is a subsequent documentation-only commit on the same branch.
  Its enclosing Git commit/PR `headRefOid` identifies the report revision; neither
  commit is claimed to have been packaged by the old CI run.
- New [Draft PR #5](https://github.com/iamSlokker/NuvioDesktop-Linux/pull/5):
  `fix/windows-update-manifest-boundary-2026-10-13`, based directly on `a96ce242`.
  Commit `91f7abd1944d85990fed63e7493780dff72bd53a` contains only the updater cleanup correction and seven tests.
  It is not merged into `linux` or the compliance branch.
- No additional upstream snapshot was integrated. The authorized Umbra pin
  remains `18537e3c85d7f365487abb6f7e7edb8a4432a081`, a WIP snapshot, not an upstream
  stable-release claim. HDR is a design proposal only; no renderer branch/code
  was introduced.
- The existing main checkout remains on local `linux` at `d9bfcdad8`, deliberately
  untouched. Remote `linux` is the authoritative baseline above. The untracked
  file named `linux`, installed applications and personal configuration were
  untouched. Work used the existing audit worktree and a separate updater fix
  worktree; no reset, force-push, merge, tag or release.

The latest inspected successful three-format run is
[38397205286](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38397205286),
which packages **`fce98dc1633b51f813c20965e05941c2745beb8c`**. It predates `a96ce242`
and both Draft PR changes. Existing artifacts were inspected, not altered,
deleted, extended or republished. No new binary-producing workflow was dispatched.

| Existing artifact | SHA256 |
| --- | --- |
| DEB | `997ce148d14565d775fde74ed196da9686571badb1421d220ab22c69889f67be` |
| Flatpak | `687dddba1e5f94b2969b1482ea450775b5f5dfe9346f7a15bf0b6cb6b1fe66d0` |
| AppImage | `946c8d8d04f1b691604dc324267462ddeb11743820a39422e033fff8f3ff104a` |
| Application source | `af04e1559ae2a83e5d4fdca015374df78f2a1a27750a4a9347db69d4640c8fdf` |
| WebKit/GTK source | `2e6b4e73731695ec59d7590fedba3567566ae1a0afbd10a329e0d294ed82bb7b` |

## Completion matrix

The status describes the evidence boundary, not a blanket guarantee.

| Area | Status | Evidence and remaining limit |
| --- | --- | --- |
| Core Linux application | READY FOR REVIEW | Existing integrated development baseline; selected owner acceptance preserved. No claim of complete desktop/provider QA. |
| Umbra feature integration | READY FOR REVIEW | Authorized history already merged; no new snapshot. Shared Calendar, filters, catalog grouping, hero framing, Ambient Adaptive, defaults, profile input, artwork prewarming and subtitle sync remain present. |
| Video playback | READY FOR REVIEW | Accepted NVIDIA/Bazzite paths preserved; no media/player changes in this mission. AMD/Intel, receivers and other display/session combinations remain unaccepted. |
| Automatic subtitles | IMPLEMENTED / NOT MANUALLY ACCEPTED | Existing local HTTP/repository tests and integrated feature; full real-provider/player matrix is not established by this mission. |
| HDR10 output | BLOCKED | Current mpv has `HAVE_WAYLAND=0`; X11 Canvas/GTK HUD lack native HDR negotiation. Separate surface/HUD architecture and authorized hardware/build acceptance required. Optional. |
| Account/profile storage | READY FOR REVIEW | Account switching has owner acceptance; isolated account/settings tests pass. Backup → Import → Restart GUI remains pending by prior owner choice. |
| Windows updater | IMPLEMENTED / NOT MANUALLY ACCEPTED | PR #3's authenticated full ZIP flow preserved; PR #5 corrects stale-manifest cleanup. Real Windows locking/copy/shutdown/restart still pending. |
| DEB packaging | BLOCKED | Existing build/static/smoke-install evidence passes; source/rights/availability and broader runtime acceptance are separate unresolved gates. |
| Flatpak packaging | BLOCKED | Existing export/content/runtime/ABI checks pass; application source/rights/channel and separately supplied runtime scope require review. Sandbox limitations explicit. |
| AppImage packaging | BLOCKED | Existing private patched engine/static/runtime evidence preserved; shared gaps plus remaining static runtime provenance/relink obligations. |
| Third-party source closure | BLOCKED | Substantial exact local retention completed; incorporated native/build/relink and recipient delivery gaps remain. A source JAR is not proof of native source completeness. |
| License/notice coverage | BLOCKED | All 86 JAR flags classified; exact Sans redistribution grant is absent and authoritative restriction evidence exists. Native/model/artwork/file-level terms need final review. |
| CI | READY FOR REVIEW | Immutable builder/engine checks, pinned packaging actions, all-three-format outcome gate, bounded cleanup/cache isolation preserved. No new package CI run or redistribution approval. |
| Documentation | READY FOR REVIEW | Updated support/acceptance/HDR/source-delivery guidance, with historical artifact evidence preserved. |
| Release authorization | BLOCKED | No merge/public release/source offer/OCI redistribution approved by this mission. |

## Engineering corrections and validation

Two concrete security/integrity defects were reproduced and fixed:

1. **Windows stale-manifest cleanup:** old filtering recognized traversal only
   with backslashes. Windows also accepts forward slashes, and deletion wildcards
   or trailing-dot/space aliases could bypass the intended relative-file boundary.
   PR #5 normalizes separators and rejects unsafe components before cleanup.
   This requires a crafted/legacy local manifest; it is **not** a remote download
   digest bypass. Full ZIP SHA256, receipt binding, install reverification and
   rejection of legacy unauthenticated delta markers remain intact.
2. **Compliance JAR comparison:** duplicate archive member names collapsed under
   set/name lookup, allowing an earlier differing member to hide behind a final
   original member. Both original and repacked archives now reject duplicates.
   Eight baseline-failing subcases cover classes, licenses, native objects and
   directories. Actual 126 package JARs contain no duplicates; regenerating the
   original inventory is byte-identical, SHA256
   `3e01d1cb3618173a284a0e3246513f56a11ed929a93806c5694f5c7b5a0d50d3`.

One **test fixture**, not application behavior, was corrected: an empty existing
directory no longer means an existing account after the accepted account-scoped
storage integration. The test now creates a real disposable legacy store and
separately verifies new-account defaults. No settings implementation changed.

The source-retention tools additionally reject missing/tampered inputs, unsafe
paths, symlink outputs, partial/concurrent overwrite, ambiguous source identities
and incomplete Debian source descriptor closure. Ubuntu trust is signed Release
→ SHA256 Sources index → exact source version → every archive SHA256; the
maintainer `.dsc` is checked against that authenticated source set. No source
archive is executed or installed by these tools.

Validation performed:

- **86 focused JVM tests**, zero failures/errors/skips: 61 updater, 7 settings
  backup, 11 settings organization and 7 account slots. Seven updater tests
  exercise production extraction/manifest/helper functions; generated-script
  checks are not real Windows execution. Baseline failures were preserved before
  applying the fixes.
- **188 additional focused JVM tests**, zero failures/errors/skips: Calendar
  repository/persistence (12), bundled ONNX CPU inference (7), synthetic local
  HTTP subtitle sync/parsing/retiming (47), Escape/PiP policies (50), Linux native
  ownership/hero eligibility/geometry fakes (31), profile PIN isolation (11),
  stream filtering (22) and shader selection policy (8). These exercise actual
  repositories, models and local fixtures without creating a player window.
- `compileKotlinDesktop` and `compileTestKotlinDesktop`: PASS, Java 17, offline
  dependency cache, isolated test state. Only loopback fixture networking was
  enabled in a network namespace; display/live-test flags were unset.
- **135 packaging/source regression tests passed**, zero failures/errors/skips,
  with Java 17 and no external network. This includes the existing recording-Skia C++
  fixture, not a WebKit or distribution-binary rebuild.
- Actual original package/source inventory reverified; all 1,819 application
  inputs retain their packaged Git-blob identities. Source archives, original
  Maven bytes/transformations, WebKit/GTK patches, AppImage prefix and package
  provenance remain bound to the original artifacts.
- Actual dependency retention verifier: 125 Maven sources plus 21 additional
  JDK/native/text inputs; Temurin source/SBOM signatures and original FFmpeg
  signature verified. All source-retrieval trust limits are recorded.
- Ubuntu signatures, source-package locks and retained archives verified using
  isolated keyring/data directories; exact package/source versions queried
  read-only from immutable images. Final offline verification covers **427 source
  versions / 1,314 inputs / 2,621,872,407 bytes**, including the AppImage's 140
  source versions and the canonical seed's exact installed-package versions.
  No host package install. Evidence was also copied from temporary storage into
  an ignored persistent local evidence directory and rehashed.
- Python/JSON syntax, documentation links and `git diff --check`: PASS.

The [validation receipt](compliance/evidence/validation-2026-10-13.json) records
class counts, exact test scope and hashes of locally retained command/log/XML
summaries. The two JVM runs total **274 tests**. They are separate scoped runs,
not a claim that the entire desktop test suite ran or that Windows was launched.

No GUI/app launch, display/audio/HDR test, new WebKit build, full packaging build,
external account/service test or personal-data access occurred. There is no
new Windows runtime or physical-controller/receiver acceptance claim.

## Compliance matrix

Detailed evidence: [original audit](compliance/linux-redistribution-audit.md),
[dependency retention](compliance/dependency-source-retention.md),
[platform follow-up](compliance/platform-source-evidence.md),
[Ubuntu retention](compliance/ubuntu-source-retention.md),
[source-delivery contract](compliance/source-delivery-plan.md) and their linked
machine receipts. The original 322-component inventory remains immutable evidence
for the old packages; supplements describe later local retention, not changes
to the old artifact bundle.

| Target | Available source/notice evidence | Remaining material/rights/availability gates | Verdict |
| --- | --- | --- | --- |
| DEB | Exact application Git/source snapshot; retained 18 media inputs plus signature/key/recipes; full signed Temurin source/SBOM/build scripts; all 125 Maven sources/POMs/modules; native root sources; existing media/JDK/model notices | Required incorporated native/build/relink inputs and notice texts; exact Sans grant/replacement; model/artwork/file-level review; tested modified-source build/install instructions; recipient source channel. Host GTK/WebKit/driver dependencies are identified separately, no blanket exception assumed. | BLOCKED |
| Flatpak | Same application materials; all actual application JARs match DEB; exact GNOME OSTree hash resolves to GNOME/Freedesktop recipes; reviewed narrow sandbox | Same application gaps; responsibility for separately distributed runtime/driver extensions, any future mirror/offline bundle and source availability needs component-specific review. GNOME recipes are not all component source bytes. | BLOCKED |
| AppImage | Same application materials; original WebKit2.50.4/GTK sources, patches/resources/production flags; exact Ubuntu source locks/retention; outer runtime source plus pinned libfuse/squashfuse/patch | Same common gaps; original runtime's mutable Alpine inputs leave musl/zstd/zlib/mimalloc versions unproven; static linking/relink and complete notice/source offer require review. Future common-license copy fix is tested, old artifact unchanged. | BLOCKED |
| Builder/engine OCI | Verified immutable image IDs/OCI digest and exact installed-package/source maps; same Ubuntu signed source index; retained JDK/tool recipes | Full layers/non-dpkg tools (including full JDK/CMake), static/deleted-layer inputs and notice/source scope remain separate from application inventory. Build-only status is no exemption for actual OCI conveyance. | BLOCKED |

Full JDK source retention closes the earlier `src.zip`-only concern, but does not
attest independently to all toolchain/static inputs. Of 125 JARs, 39 contain
packaged notice text, 65 have source headers/POM declarations and 21 have POM-only
evidence. The 86 original flags are neither 86 violations nor 86 approvals.
Skiko's runtime source JAR is manifest-only; Jansi's source JAR contains 12 native
binaries. Root source tags alone do not close their native build graph.

Authoritative JetBrains statements restrict unreleased Sans to Writerside use;
no grant for the three bundled TTFs was established. Mono's license cannot be
substituted. A replacement must cover Compose and HUD, remove original bundled
bytes and undergo layout/scaling acceptance. No unreviewed font/branding change
was made. U2NetP actual upstream bytes and YuNet's pinned LFS identity/license
match the bundled models; applicable preferred-form/export/training obligations
remain a legal review question, not an invented requirement or exemption.

Shader notices are concrete: all 16 packaged files match the source, with 14 MIT
headers and two Unlicense texts. Specific remaining artwork/data questions are
the seven SVG Repo imports without asset-specific grants, provider/rating/award
marks and setup-preview artwork, plus the attributed anime mapping's exact
dataset/generation record and full database license texts. The platform supplement
lists these precisely; the approved login artwork and shader behavior were kept.

Sources retained privately are **not yet offered to recipients**. Existing Actions
artifacts require authentication and expire on 2027-01-11; authentication alone
is not called unlawful. Review current recipients' rights, complete source access,
retention and applicable delivery method now. The absence of a public release
does not establish that no conveyance occurred. The delivery design proposes
immutable per-version maps, complete applicable materials, modified-build/relink
instructions and a durable channel; it cannot approve hosting or legal terms.

## Remaining owner actions — finite decisions

| Priority / action | Why not completed automatically | Evidence that closes it | Blocks |
| --- | --- | --- | --- |
| 1. Resolve rights and applicable source/relink scope | Third-party permission and legal interpretation cannot be invented. Obtain the exact Sans grant **or** approve a licensed replacement with layout acceptance; review model/artwork terms, license elections/exceptions and native/static notice/relink scope together. | Retained grants/review decisions mapped to exact component bytes; accepted replacement if chosen; complete required notices and source/relink material list. | Redistribution; ordinary protected development can continue. |
| 2. Obtain absent native/runtime provenance or authorize a bounded replacement build | The original AppImage runtime's build logs expired; mutable Alpine inputs cannot be reconstructed honestly from a current image. Native prebuilt root sources alone do not identify every incorporated input. New expensive binary builds are outside this mission. | Upstream immutable build/input records and required corresponding materials, or separately approved reproducible replacements with source/relink evidence. No WebKit rebuild needed merely for documentation. | Affected redistribution formats/OCIs. |
| 3. Approve a durable source/recipient channel | Publishing materials, hosting obligations and any written source offer require an owner decision after rights review. Existing recipients need a reviewed remediation/delivery plan. | Working complete binary-to-source downloads with hashes, access/retention policy, instructions and applicable review approval; cover OCIs separately. | Further binary uploads/redistribution. |
| 4. Review/merge scoped Draft PRs, then authorize final candidate acceptance/publication | This mission cannot merge or release. Exact final binaries do not yet include the new changes. A real Windows VM and targeted desktop/device testers are required for those claims. | Reviewed commits; authorized build with all package/source gates; recorded target-specific runtime acceptance and separate release approval. Settings import remains pending without asking the owner to repeat the declined test. | Release authorization; Windows acceptance for that platform, hardware claims only where made. |
| Optional: approve native HDR research | Requires a new Wayland-capable media build, native surface/HUD contract and real HDR monitor/compositor/driver evidence; a player option is insufficient. | Negotiated BT.2020/PQ surface/output evidence plus SDR/PiP/HUD/input/lifecycle regressions on authorized hardware. | Optional HDR milestone only, not an SDR release. |

These actions do not require replaying snapshots, redesigning the working SDR
player or repeatedly rebuilding WebKit. The documented source/verifier work is
available for review now. Where permissive-license source publication is not
required after review, do not invent that obligation; where copyleft source or
relink conditions apply, notices/URLs alone are insufficient.

## Recheck commands and changed scope

From the audit worktree, with only disposable input/output directories:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  JAVA_HOME=/path/to/temurin-17 PYTHONDONTWRITEBYTECODE=1 \
  python3 -m unittest discover -s tools/linux/packaging/tests -v
python3 tools/linux/packaging/compliance/review_index.py --check
python3 tools/linux/packaging/compliance/dependency_sources.py \
  --inventory docs/compliance/linux-third-party-inventory.json \
  --receipt docs/compliance/evidence/dependency-source-retention.json \
  --sources build/compliance-inputs/dependency-sources
python3 tools/linux/packaging/compliance/ubuntu_sources.py fetch \
  --lock docs/compliance/evidence/ubuntu-source-lock.json \
  --output build/compliance-inputs/ubuntu-retention/archives --offline
git diff --check
git diff --stat a96ce242f084a41ecf433c206958f1cbffa53345...HEAD
```

The original full inventory command and signature/index retrieval procedures are
in the [tool README](../tools/linux/packaging/compliance/README.md). The review
index verifies evidence/script bindings only; it does not replace each input
verifier or clear a legal gate. The focused JVM command is recorded in PR #5;
run updater staging tests on that branch, and account/settings fixture tests on
PR #4. No real Windows helper is executed by those tests.

Changed scope: compliance source/notice tooling and regression tests; intentional
JSON evidence/locks; one settings test fixture; support/acceptance/HDR/delivery/
final documentation and narrow README corrections. PR #5 separately changes only
the updater cleanup filter/test. No player, renderer, package version, dependency
pin, WebKit source/security policy or installed application behavior was changed.

Exact PR #4 files relative to `linux` (including the original audit foundation):

```text
README.md
composeApp/src/desktopTest/kotlin/com/nuvio/app/features/settings/LinuxSettingsOrganizationTest.kt
docs/FINAL-READINESS-REPORT.md
docs/compliance/dependency-source-retention.md
docs/compliance/evidence/dependency-source-retention.json
docs/compliance/evidence/engine-ubuntu-provenance.json
docs/compliance/evidence/oci-ubuntu-packages.json
docs/compliance/evidence/oci-ubuntu-source-lock.json
docs/compliance/evidence/platform-source-retention.json
docs/compliance/evidence/run-38397205286.json
docs/compliance/evidence/source-review-index.json
docs/compliance/evidence/ubuntu-source-lock.json
docs/compliance/evidence/ubuntu-source-retention.json
docs/compliance/evidence/validation-2026-10-13.json
docs/compliance/linux-redistribution-audit.md
docs/compliance/linux-third-party-inventory.json
docs/compliance/platform-source-evidence.md
docs/compliance/source-delivery-plan.md
docs/compliance/ubuntu-source-retention.md
docs/linux-acceptance-protocol.md
docs/linux-hdr10-design.md
docs/linux-support-and-recovery.md
docs/linux-umbra-snapshot-integration.md
tools/linux/packaging/README.md
tools/linux/packaging/compliance/README.md
tools/linux/packaging/compliance/collect_media_sources.py
tools/linux/packaging/compliance/dependency_sources.py
tools/linux/packaging/compliance/inventory.py
tools/linux/packaging/compliance/review_index.py
tools/linux/packaging/compliance/ubuntu_sources.py
tools/linux/packaging/formats.py
tools/linux/packaging/tests/test_compliance.py
tools/linux/packaging/tests/test_dependency_sources.py
tools/linux/packaging/tests/test_review_index.py
tools/linux/packaging/tests/test_ubuntu_sources.py
tools/linux/packaging/webkit/stage.py
```

PR #5 changes exactly:

```text
composeApp/src/desktopMain/kotlin/com/nuvio/app/features/updater/AppUpdaterPlatform.desktop.kt
composeApp/src/desktopTest/kotlin/com/nuvio/app/features/updater/PortableUpdateStagingTest.kt
```

The new evidence JSON is intentional review data. No source cache, downloaded
archive, generated binary, personal path/configuration or raw runtime log is
committed. Local verified source/evidence copies are retained under the ignored
`build/compliance-readiness-2026-10-13/` directory in the main checkout, separate
from the temporary audit worktree; this private retention is not public delivery.

## Final engineering verdict

NuvioDesktop-Linux is **technically usable for continued protected development**
with the established Bazzite acceptance boundary. The engineering mission closed
concrete defects and substantial source-retention gaps without destabilizing that
baseline. It did not turn partial physical acceptance into complete QA.

**DEB, Flatpak and AppImage are not ready for redistribution.** The smallest
responsible release path is: resolve precise rights/required material scope;
close remaining native/static provenance and notice/relink gaps; approve and
verify recipient source delivery; review the Draft changes and authorize one
properly identified final candidate/targeted acceptance before a separate release
decision. Genuine native HDR remains pending and can be deferred independently.
