#!/usr/bin/env bash
# Release hook: publish a staged, verified stable bundle to the local Pages
# checkout, swap the staged bundle for the signed repo-linked rebundle of the
# same OSTree commit, refresh SOURCE-MAP.json/SHA256SUMS and commit gh-pages.
# Never pushes; prints the push command for when the GitHub release goes public.
#
#   publish-release.sh <stage-dir> <gh-pages-checkout> <key-fingerprint> [flatpak_repo.py publish options]
#
# <stage-dir> is prepare-assets.sh output (release/ and source/). Run it after
# prepare-assets.sh and before the draft release is created.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
STAGE=$(cd "${1:?usage: publish-release.sh <stage-dir> <gh-pages-checkout> <key-fingerprint>}" && pwd)
PAGES=$(cd "${2:?missing gh-pages checkout}" && pwd)
KEY=${3:?missing signing key fingerprint}
shift 3
BRANCH=${NUVIO_PAGES_BRANCH:-gh-pages}
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

[[ $(git -C "$PAGES" symbolic-ref --short HEAD) == "$BRANCH" ]] || { echo "$PAGES is not on $BRANCH" >&2; exit 1; }
[[ -z $(git -C "$PAGES" status --porcelain) ]] || { echo "$PAGES has uncommitted changes" >&2; exit 1; }
init=()
[[ -f "$PAGES/$SITE/repo/config" ]] || init=(--init)

signed="$STAGE/.signed-$name"
report="$STAGE/flatpak-publish.json"
if ! python3 "$HERE/../flatpak_repo.py" publish --bundle "$bundle" --pages "$PAGES" --gpg-key "$KEY" \
        --signed-bundle-out "$signed" --report "$report" --work-dir "$STAGE" "${init[@]}" "$@"; then
    echo "Publishing failed; restore the Pages tree with: git -C $PAGES checkout -- . && git -C $PAGES clean -fd" >&2
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
for dir in release source; do
    (cd "$STAGE/$dir" && find . -maxdepth 1 -type f ! -name 'SHA256SUMS*' ! -name '.*' -printf '%P\0' \
        | sort -z | xargs -0 sha256sum -- > SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS)
done
rm -f "$report"

version=$(jq -r .version "$PAGES/$SITE/release.json")
old=$(git -C "$PAGES" rev-parse HEAD)
# One commit on the branch: superseded OSTree objects must not accumulate in git history.
git -C "$PAGES" checkout -q --orphan "$BRANCH-next"
git -C "$PAGES" add -A
git -C "$PAGES" commit -q -m "Flatpak repository: Nuvio Desktop $version (${commit:0:12})"
git -C "$PAGES" branch -q -D "$BRANCH"
git -C "$PAGES" branch -q -m "$BRANCH"
echo "Signed bundle staged as $name (CI commit $commit, key $fpr)."
echo "When the GitHub release is published, push the repository with:"
echo "  git -C $PAGES push --force-with-lease=$BRANCH:$old origin $BRANCH"
