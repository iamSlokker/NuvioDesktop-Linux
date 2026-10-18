# Bundled Inter font replacement

**Superseded 2026-10-18:** by owner decision Linux bundles upstream's JetBrains Sans again, like Umbra's Nuvio; the Inter fonts, notice and font gate described here were removed. To restore them, revert `fix(linux): use upstream's bundled JetBrains Sans instead of Inter` (`f4f18836a`); see [Restoring a fork-made version](artwork-data-closure.md#restoring-a-fork-made-version). This record is historical.

The release correction replaces the three JetBrains Sans UI font binaries with
unmodified **Inter 4.1** regular (400), semibold (600), and bold (700) TTFs from
the official [rsms/inter v4.1 release](https://github.com/rsms/inter/releases/tag/v4.1).
This is the owner's authorized font replacement, not a change to the approved
Nuvio logo or wordmark artwork. It applies to the existing shared Compose theme
and desktop HTML player controls. Installed-font selection and missing-font
fallback retain their existing behavior.

## Exact inputs and modifications

The machine-readable [Inter manifest](../../tools/linux/packaging/fonts/inter-manifest.json)
pins each packaged font and notice, the upstream commit
`e3a3d4c57d5ecc01453a575621882a384c1995a3`, and both retained upstream archives:

| Input | SHA-256 |
| --- | --- |
| Official `Inter-4.1.zip` release, 33,707,794 bytes | `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e` |
| Source tar archive for the pinned commit, 9,235,065 bytes | `57e167f8e56829618a96f61080f05ae843d9881f11a32cfcf78cca7aa5845520` |

These hashes were computed after retrieval from the official HTTPS endpoints;
the GitHub release asset metadata did not supply a published digest or signature.
Do not describe this as a signed font release or claim locally reproducible font
compilation. The source archive includes the upstream font sources, Makefile,
Pipfile.lock, and identical license. No font build or binary modification was
performed. The packaged fonts are byte-for-byte copies of
`extras/ttf/Inter-Regular.ttf`, `Inter-SemiBold.ttf`, and `Inter-Bold.ttf`; only the
resource filenames use lowercase names required by Compose identifiers.

The full SIL Open Font License 1.1 and upstream copyright text are preserved in
`composeResources/files/licenses/Inter/LICENSE.txt`, alongside a distribution
`NOTICE.txt`. The OFL permits redistribution under its conditions; the exact
license is the controlling text. This evidence resolves the previous missing
font permission for new payloads. It does not retroactively license old
JetBrains Sans payloads or certify the whole application's distribution.

## Source retrieval and verification

The manifest provides independently retrievable HTTPS URLs and SHA-256 values.
For each archive, fetch the manifest's URL into a new local file, then compare its
SHA-256 and length before using it. Retained local source inputs are under the
ignored `build/font-inputs/` directory; they are not Git-tracked binaries or a
claim of recipient delivery. A recipient source bundle must include these inputs
or another documented, verified source-delivery arrangement. If an upstream
archive is regenerated with different bytes, reject it and investigate; do not
silently update the pin.

The committed application source contains the exact redistributable TTFs and
their full OFL/notice, so the correction does not require recipients to execute
the font build. Fonts can be replaced and the application repackaged using the
same Compose resource names and existing canonical packaging pipeline. The
manifest and package verifier must be deliberately updated for any replacement.

## Verification boundary

`font_contract.py` inspects the actual application JARs at the canonical image
gate (used by DEB/AppImage) and the exported Flatpak gate. All three fonts and the
two notice files must match the manifest, with no missing or duplicate entries.
The old JetBrains Sans filenames and original TTF hashes are rejected, including
old font bytes renamed to another TTF/OTF name. This is an application font
payload contract, not an audit of fonts supplied by the host or Flatpak runtime.

The focused tests cover real resource hashes and weights, complete OFL presence,
the native HUD CSS/resource list without loading JNI, and actual offscreen
Compose text at 100%, 125%, 150%, 175%, and 200% scale. They exercise wrapping and
the existing missing-system-font fallback. The approved login logo's existing
offscreen tests are retained unchanged. No AWT window, GTK/WebKit process,
audio/video player, or live display is required.

Actual DEB/Flatpak/AppImage font acceptance must be reported against newly built
artifact identities. Passing source/offscreen tests alone does not establish
that historical packages contain this correction.
