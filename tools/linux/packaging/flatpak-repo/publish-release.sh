#!/usr/bin/env bash
# Release hook: publish a staged, verified stable bundle into the local Pages
# tree, swap the staged bundle for the signed repo-linked rebundle of the same
# OSTree commit, pack the whole site as source/<prefix>-flatpak-site.tar and
# refresh SOURCE-MAP.json/SHA256SUMS. Never pushes or uploads anything.
#
#   publish-release.sh <stage-dir> <pages-dir> <key-fingerprint> [flatpak_repo.py publish options]
#
# <stage-dir> is prepare-assets.sh output (release/ and source/). <pages-dir> holds
# the live site, unpacked from the previous release's -flatpak-site.tar (an empty
# directory for the first publish). The tar travels with the vX.Y.Z-source release;
# publishing vX.Y.Z runs flatpak-pages.yml, which deploys it to GitHub Pages as an
# Actions artifact. Run this after prepare-assets.sh and before the releases exist.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
STAGE=$(cd "${1:?usage: publish-release.sh <stage-dir> <gh-pages-checkout> <key-fingerprint>}" && pwd)
PAGES=$(cd "${2:?missing gh-pages checkout}" && pwd)
KEY=${3:?missing signing key fingerprint}
shift 3
SITE=$(jq -r .site_dir "$HERE/repo.json")

shopt -s nullglob
bundles=("$STAGE"/release/*.flatpak)
[[ ${#bundles[@]} -eq 1 ]] || { echo "Expected exactly one staged .flatpak" >&2; exit 1; }
bundle=${bundles[0]}
name=$(basename "$bundle")
map="$STAGE/source/SOURCE-MAP.json"
jq -e --arg n "$name" '.packages[$n].sha256' "$map" >/dev/null || { echo "$name is not in SOURCE-MAP.json" >&2; exit 1; }
[[ $(sha256sum "$bundle" | cut -d' ' -f1) == $(jq -r --arg n "$name" '.packages[$n].sha256' "$map") ]] \
    || { echo "Staged bundle does not match SOURCE-MAP.json" >&2; exit 1; }

init=()
[[ -f "$PAGES/$SITE/repo/config" ]] || init=(--init)

signed="$STAGE/.signed-$name"
report="$STAGE/flatpak-publish.json"
if ! python3 "$HERE/../flatpak_repo.py" publish --bundle "$bundle" --pages "$PAGES" --gpg-key "$KEY" \
        --signed-bundle-out "$signed" --report "$report" --work-dir "$STAGE" "${init[@]}" "$@"; then
    echo "Publishing failed; unpack the previous -flatpak-site.tar into a fresh $PAGES before retrying" >&2
    rm -f "$signed"
    exit 1
fi

ci_sha=$(sha256sum "$bundle" | cut -d' ' -f1)
# New inode: the staged name may be a hard link to the downloaded CI artifact.
mv -f "$signed" "$bundle"
new_sha=$(sha256sum "$bundle" | cut -d' ' -f1)
commit=$(jq -r .commit "$report")
fpr=$(jq -r .key_fingerprint "$report")
jq --arg n "$name" --arg sha "$new_sha" --arg ci "$ci_sha" --arg c "$commit" --arg k "$fpr" \
    '.packages[$n] |= (.ci_sha256 = $ci | .sha256 = $sha | .flatpak_commit = $c | .flatpak_signing_key = $k)' \
    "$map" > "$map.tmp"
mv "$map.tmp" "$map"
# The whole site, deterministic, for flatpak-pages.yml. Prefix and version follow the stage's names.
version=$(jq -r .version "$PAGES/$SITE/release.json")
prefix=$(basename "$name" .flatpak); prefix=${prefix%-"$version"-x86_64}
site_tar="$STAGE/source/$prefix-$version-flatpak-site.tar"
tar --sort=name --format=gnu --owner=0 --group=0 --numeric-owner --mtime=@"$(jq -r .source_date_epoch "$PAGES/$SITE/release.json")" \
    --exclude=./.git --exclude=./.gitignore --exclude="./$SITE/repo/.lock" \
    --exclude="./$SITE/repo/tmp" --exclude="./$SITE/repo/state" \
    -cf "$site_tar" -C "$PAGES" .
for dir in release source; do
    (cd "$STAGE/$dir" && find . -maxdepth 1 -type f ! -name 'SHA256SUMS*' ! -name '.*' -printf '%P\0' \
        | sort -z | xargs -0 sha256sum -- > SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS)
done
rm -f "$report"

echo "Signed bundle staged as $name (CI commit $commit, key $fpr)."
echo "Site packed as $(basename "$site_tar") ($(du -h "$site_tar" | cut -f1)); GitHub Pages deploys it when v$version is published."
