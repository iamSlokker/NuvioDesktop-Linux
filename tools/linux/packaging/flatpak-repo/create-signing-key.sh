#!/usr/bin/env bash
# One-time owner step: create the dedicated Flatpak repository signing key in
# the current GnuPG home ($GNUPGHOME or ~/.gnupg), export its public half for
# the repository and back up the secret key and revocation certificate.
#
#   create-signing-key.sh --backup-dir DIR [--public-key-out FILE]
#                         [--uid "NAME <EMAIL>"] [--no-passphrase]
#
# The key is RSA-4096, signing-only and does not expire: Flatpak clients keep
# the key they were given, so an expiring key would silently break updates.
# --no-passphrase exists for disposable test keyrings only. Nothing is uploaded.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PUBLIC_OUT="$HERE/nuvio-flatpak.pub.gpg"
UID_TEXT="Nuvio Desktop Linux Flatpak Repository <iamslokker@users.noreply.github.com>"
BACKUP=""
PASS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --backup-dir) BACKUP=$2; shift 2 ;;
        --public-key-out) PUBLIC_OUT=$2; shift 2 ;;
        --uid) UID_TEXT=$2; shift 2 ;;
        --no-passphrase) PASS=(--passphrase '' --pinentry-mode loopback); shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
[[ -n "$BACKUP" ]] || { echo "--backup-dir is required (use removable/offline storage)" >&2; exit 2; }
[[ ! -e "$PUBLIC_OUT" ]] || { echo "$PUBLIC_OUT already exists; refusing to replace a repository key" >&2; exit 1; }
if gpg --batch --list-secret-keys -- "=$UID_TEXT" >/dev/null 2>&1; then
    echo "A secret key with user ID \"$UID_TEXT\" already exists; refusing to create another" >&2
    exit 1
fi

umask 077
mkdir -p "$BACKUP"
gpg --batch "${PASS[@]}" --quick-generate-key "$UID_TEXT" rsa4096 sign never
FPR=$(gpg --batch --with-colons --list-secret-keys -- "=$UID_TEXT" | awk -F: '/^fpr:/ {print $10; exit}')
[[ "$FPR" =~ ^[0-9A-F]{40}$ ]] || { echo "Could not determine the new key fingerprint" >&2; exit 1; }

# Binary public key: embedded by flatpak build-bundle and base64'd into .flatpakrepo/.flatpakref.
gpg --batch --export -- "$FPR" > "$PUBLIC_OUT.tmp"
chmod 644 "$PUBLIC_OUT.tmp"
mv "$PUBLIC_OUT.tmp" "$PUBLIC_OUT"
gpg --batch "${PASS[@]}" --armor --export-secret-keys -- "$FPR" > "$BACKUP/nuvio-flatpak-$FPR.secret.asc"
REVOKE="${GNUPGHOME:-$HOME/.gnupg}/openpgp-revocs.d/$FPR.rev"
if [[ -f "$REVOKE" ]]; then cp "$REVOKE" "$BACKUP/nuvio-flatpak-$FPR.revocation.asc"; fi
gpg --batch --export -- "$FPR" > "$BACKUP/nuvio-flatpak-$FPR.pub.gpg"

python3 "$HERE/../flatpak_repo.py" check-key --public-key "$PUBLIC_OUT" --gpg-key "$FPR" >/dev/null
echo "Created signing key $FPR"
echo "Public key:  $PUBLIC_OUT (commit this file)"
echo "Backup:      $BACKUP (secret key + revocation certificate; store offline, never commit)"
echo "Sign with:   --gpg-key $FPR"
