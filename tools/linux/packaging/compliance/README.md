# Corresponding-source review tools

These tools collect application-package evidence and source inputs. Full builder/engine
OCI file/license inventories remain a separate BLOCKED review scope. They do not certify legal
compliance, change licenses, rebuild binaries or publish artifacts. Current
format assessments and exact blockers are in
[the redistribution report](../../../../docs/compliance/linux-redistribution-audit.md).
The JSON inventory is an intentional review artifact, not executable build output.

## Offline inventory

`inventory.py` reads extracted existing packages, verifies their artifact/file
hashes, compares application source to Git objects, checks retained WebKit/GTK
sources/patches, identifies actual Maven bytes/POMs, verifies known JAR trimming,
and inventories embedded native objects and notices. It never runs the app,
loads JNI, performs a network request or reads user Nuvio configuration.

Prepare an ignored input directory with this layout:

```text
artifacts/                         exact existing Actions bundle files + SHA256SUMS
source/                            extracted Nuvio-source-*.tar.gz
webkit-source/                     extracted Nuvio-WebKit-source-*.tar.gz
deb/opt/nuvio-desktop/              ar/zstd/tar readback of DEB data
appimage/squashfs-root/usr/lib/nuvio-desktop/  AppImage --appimage-extract only
flatpak/files/lib/nuvio-desktop/    isolated OSTree checkout of Flatpak app ref
flatpak/metadata                   actual exported Flatpak metadata
deb-control.txt                    actual control.tar.zst control text
```

The reviewed run is 38397205286. Its artifact archive digest is recorded in
`docs/compliance/evidence/run-38397205286.json`. Use authenticated `gh run download`
for that existing bundle; check the outer ZIP against GitHub's artifact digest
and every inner `SHA256SUMS` before extracting. Reject absolute/traversing paths,
links outside the extraction root and special files (Python tar `filter='data'`
is suitable). Extracting AppImage is not authorization to run AppRun/Nuvio.
Use a new OSTree repo and isolated HOME/XDG/FLATPAK_USER_DIR for Flatpak readback;
do not use an installed application or its data. No sources/binaries are uploaded
by these tools.

Example (replace only the explicit input paths):

```sh
python3 tools/linux/packaging/compliance/inventory.py \
  --audit-base a96ce242f084a41ecf433c206958f1cbffa53345 \
  --ci-evidence docs/compliance/evidence/run-38397205286.json \
  --inputs build/compliance-inputs/38397205286 \
  --maven-cache /path/to/gradle/caches/modules-2/files-2.1 \
  --ubuntu-packages docs/compliance/evidence/engine-ubuntu-provenance.json \
  --ubuntu-owners docs/compliance/evidence/engine-ubuntu-provenance.json \
  --appimage-runtime /path/to/verified/pinned/appimage-runtime \
  --output build/inventory.json --check
```

`--check` exits **2** for the documented BLOCKED assessment. It is not a
replacement for the existing package integrity gates or a general legal policy
engine. Null source hashes mean missing evidence, never approval. Maven source
URLs are coordinate-derived candidates until retrieved and hash-locked. POM
license declarations/inheritance and notice hashes are evidence, not conclusions
about complete copyright/NOTICE coverage. Retained Maven cache bytes must match
the package, or a specifically verified entry-removal/extraction transformation.
Unknown payloads remain explicit gaps rather than being silently ignored.

The engine provenance JSON can be rechecked against the same immutable builder:

```sh
podman run --rm --pull=never --network=none --read-only --userns=keep-id \
  --cap-drop=all --security-opt label=disable --security-opt no-new-privileges \
  a465827efdede9029b1560ce793085a115508500bad83889f167530efcd81ed2 \
  dpkg-query -W '-f=${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n'
```

For each recorded original container file, query `dpkg-query -S PATH` and
`sha256sum PATH` in that builder; compare its source/binary package version with
`engine/webkit-builder-packages.tsv` in the retained WebKit source archive.
The OCI hash is pinned in the evidence JSON. Do not substitute a mutable host
image or infer provenance from a similar filename on the host. The source
archive inventories and POM hashes permit independent rechecking; metadata alone
is not proof of source completeness or a reproducible native build.

## Media source retention without compilation

The existing media manifest is the sole source/version/hash authority. Reuse an
existing verified download cache, or run its source-only fetcher in a disposable
audit directory (this downloads upstream source/signature inputs; it does not
compile):

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  bash tools/linux/media-runtime/scripts/fetch.sh build/media-source-inputs
python3 tools/linux/packaging/compliance/collect_media_sources.py \
  --cache build/media-source-inputs --output build/Nuvio-media-source.tar.gz
sha256sum build/Nuvio-media-source.tar.gz
```

The collector is offline and requires **all** pinned source/signature/key inputs.
It verifies SHA256 again, retains exact tracked media recipes/patches and writes
`source-inputs.json` with checksums/URLs/scope. It does not overwrite output or
accept symlinked inputs. FFmpeg's signature must also be verified against the
manifest fingerprint as `build-runtime.sh` does; the collector does not infer a
valid signature just because signature bytes match their pinned hash.

The audited local bundle's hash is recorded in the evidence JSON; it was not
uploaded. It covers media only. JDK/JAR/native/Ubuntu/runtime source acquisition,
full notices, relinking/install material, a recipient-facing source channel and
legal review remain separate blockers. Never upload binaries or declare a
release ready merely because this source collector succeeds.

## Application archive and notice changes

`formats.archive_application_source()` preserves the existing archive name,
application bytes, modes and inventories, and adds an explicit scope/rebuild
README at the archive root. It rejects unsafe/private/escaping inputs. Packaging
still uses the same canonical application image and pinned recipes.

AppImage assembly now copies Ubuntu common-license texts from its immutable
builder, including resolved aliases, with an explanatory note and file hashes
in the existing runtime inventory. It does not copy host configuration or change
native binaries, sandbox policy or the WebKit engine recipe. Existing artifacts
remain unchanged; these additions take effect only in a future authorized build.

## Safe checks

```sh
env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
  JAVA_HOME=/path/to/temurin-17 PYTHONDONTWRITEBYTECODE=1 \
  python3 -m unittest discover -s tools/linux/packaging/tests -v
git diff --check
```

The audit used a network namespace for this suite; tests use temporary files and
mocks. No package, WebKit or GUI build/launch is necessary. Include actual
artifact inspection, source receipts, tool test results and unresolved questions
in review; none of these commands constitutes legal certification.
