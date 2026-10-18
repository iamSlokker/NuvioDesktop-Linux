#!/usr/bin/env python3
"""Bind this audit's immutable artifact evidence to local review supplements.

No download, upload, archive extraction, cache completeness or legal assessment
is performed. Run each referenced verifier separately against retained inputs.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath

INVENTORY = 'docs/compliance/linux-third-party-inventory.json'
INVENTORY_SHA256 = '3e01d1cb3618173a284a0e3246513f56a11ed929a93806c5694f5c7b5a0d50d3'
PACKAGE_COMMIT = 'fce98dc1633b51f813c20965e05941c2745beb8c'
ARTIFACTS = {
    'deb': ('nuvio-desktop_1.15.0~nightly+git.fce98dc1633b_amd64.deb', '997ce148d14565d775fde74ed196da9686571badb1421d220ab22c69889f67be'),
    'flatpak': ('Nuvio-Desktop-1.15.0-fce98dc1633b-x86_64.flatpak', '687dddba1e5f94b2969b1482ea450775b5f5dfe9346f7a15bf0b6cb6b1fe66d0'),
    'appimage': ('Nuvio-Desktop-1.15.0~nightly+git.fce98dc1633b-x86_64.AppImage', '946c8d8d04f1b691604dc324267462ddeb11743820a39422e033fff8f3ff104a'),
}
REFERENCES = {
    'docs/compliance/evidence/ubuntu-source-retention.json': 'Offline verified local Ubuntu source inputs; not complete OCI/source obligations or delivery.',
    'docs/compliance/evidence/validation-2026-10-13.json': 'Scoped local test results; not runtime, hardware or legal acceptance.',
    'docs/compliance/evidence/run-38397205286.json': 'Original run/source/access evidence; authenticated expiring artifacts are not a durable complete source offer.',
    'docs/compliance/evidence/engine-ubuntu-provenance.json': 'Original AppImage file owners/source versions; not complete native static dependencies.',
    'docs/compliance/evidence/dependency-source-retention.json': 'Local Maven/JDK/native/model evidence; JNI/static build closure and rights/notice review remain separate.',
    'docs/compliance/evidence/ubuntu-source-lock.json': 'Exact AppImage Ubuntu source inputs; retained bytes and dsc closure require the separate offline verifier.',
    'docs/compliance/evidence/platform-source-retention.json': 'AppImage/Flatpak source evidence; missing static dependencies/relink materials remain blockers.',
    'docs/compliance/evidence/oci-ubuntu-packages.json': 'Separate installed-package inventory; not all OCI layers, non-dpkg tools or complete source closure.',
    'docs/compliance/evidence/oci-ubuntu-source-lock.json': 'Exact source input lock for installed engine Ubuntu packages; not proof all source bytes are retained or all OCI layers/non-dpkg tools are covered.',
    'docs/compliance/source-delivery-plan.md': 'Proposed delivery/retention design, not an approved offer or publication.',
    'docs/compliance/inter-font-replacement.md': 'New licensed font correction; historical binaries remain unchanged.',
    'docs/compliance/modified-build-and-install.md': 'Concrete current entry points, not tested complete offline rebuild or installation acceptance.',
    'docs/compliance/release-closure.md': 'Current font/notice correction and remaining gates; all formats remain blocked.',
    'docs/compliance/artwork-data-closure.md': 'Owner decision 2026-10-17 to ship upstream artwork (four setup-preview replacements remain), superseded replacement audit and exact anime import; upstream artwork and data rights unresolved.',
    'docs/compliance/evidence/artwork-data-review.json': 'Current resource hashes and unresolved rights inventory, not grants or publication approval.',
    'tools/linux/packaging/artwork_contract.py': 'Future artwork/data payload gate; not legal clearance.',
    'docs/compliance/evidence/release-closure-validation-2026-10-13.json': 'Local development JAR and focused validation; not final package or release acceptance.',
    'tools/linux/packaging/fonts/inter-manifest.json': 'Official Inter release/source/font hashes; locally retained, not recipient delivery.',
    'tools/linux/packaging/font_contract.py': 'Future application JAR font/notice gate; not proof existing artifacts contain Inter.',
    'composeApp/src/desktopMain/resources/anime-mapping/PROVENANCE.json': 'Exact database/import identity and attribution notices; original generation and merged-data rights unresolved.',
    'composeApp/src/desktopMain/resources/player-shaders/LICENSE.md': 'Corrected MIT/Unlicense classification and added the AMD CAS MIT notice; all original shader bytes unchanged.',
    'tools/linux/packaging/compliance/inventory.py': 'Original package/source inventory verifier.',
    'tools/linux/packaging/compliance/collect_media_sources.py': 'Strict private-media source collector; not whole-application source closure.',
    'tools/linux/packaging/compliance/dependency_sources.py': 'Offline retained dependency input verifier; no public availability claim.',
    'tools/linux/packaging/compliance/ubuntu_sources.py': 'Signed Ubuntu index/input/dsc verifier; no package install or build.',
    'tools/linux/packaging/compliance/review_index.py': 'Review-index byte bindings only; not a source cache or compliance verifier.',
}


def regular_file(root, relative):
    name = PurePosixPath(relative)
    if (not name.parts or name.is_absolute() or '..' in name.parts or
            '\\' in relative or name.as_posix() != relative):
        raise ValueError('Unsafe review input path')
    for parent in (root.absolute(), *root.absolute().parents):
        if parent.is_symlink():
            raise ValueError('Symlinked review root')
    path = root
    for part in name.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError('Symlinked review input')
    if not path.is_file():
        raise ValueError('Missing review input: ' + relative)
    return path


def file_record(root, relative):
    path = regular_file(root, relative)
    data = path.read_bytes()
    return {'path': relative, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}


def build_index(root, *, inventory_sha256=INVENTORY_SHA256, package_commit=PACKAGE_COMMIT,
                artifacts=ARTIFACTS, references=REFERENCES):
    original = file_record(root, INVENTORY)
    if original['sha256'] != inventory_sha256:
        raise ValueError('Original immutable inventory changed')
    inventory = json.loads(regular_file(root, INVENTORY).read_text())
    if inventory['package_commit'] != package_commit:
        raise ValueError('Original package commit mismatch')
    if set(artifacts) != {'deb', 'flatpak', 'appimage'}:
        raise ValueError('Unknown or missing package format')
    selected = {}
    for name, digest in inventory['artifacts'].items():
        if name.endswith(('.deb', '.flatpak', '.AppImage')):
            selected[name] = digest
        elif not name.endswith(('.json', '.ini', '.tsv', '.tar.gz')):
            raise ValueError('Unknown original artifact format: ' + name)
    if selected != {name: digest for name, digest in artifacts.values()}:
        raise ValueError('Original artifact set/hash mismatch')
    bound = [dict(file_record(root, name), scope_limit=scope) for name, scope in sorted(references.items())]
    return {
        'schema_version': 1,
        'status': 'LOCAL_REVIEW_ONLY',
        'redistribution_assessment': {kind: 'BLOCKED' for kind in ('deb', 'flatpak', 'appimage', 'oci')},
        'publication_authorized': False,
        'source_availability': 'LOCAL_RETENTION_ONLY; NO_RECIPIENT_OFFER',
        'scope': 'Binds review records/scripts, not source cache completeness, binary reproducibility or legal permission. Run their separate verifiers.',
        'package_commit': package_commit,
        'original_inventory': original,
        'original_artifacts': inventory['artifacts'],
        'packages': {kind: {'name': name, 'sha256': digest} for kind, (name, digest) in artifacts.items()},
        'supplements': bound,
    }


def check_index(root, index, **kwargs):
    expected = build_index(root, **kwargs)
    if index != expected:
        raise ValueError('Review index differs from current pinned evidence/scripts; review changes before regenerating')
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--output', default='docs/compliance/evidence/source-review-index.json')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = build_index(args.root)
    if args.check:
        check_index(args.root, json.loads(regular_file(args.root, args.output).read_text()))
    else:
        output = PurePosixPath(args.output)
        if not output.parts or output.is_absolute() or '..' in output.parts or '\\' in args.output or output.as_posix() != args.output:
            raise ValueError('Unsafe review output path')
        # Existing parent must be safe; never replace any prior index.
        parent = args.root
        for part in output.parts[:-1]:
            parent = parent / part
            if parent.is_symlink() or not parent.is_dir():
                raise ValueError('Unsafe/missing review output directory')
        with (args.root / args.output).open('x') as stream:
            stream.write(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print('LOCAL_REVIEW_ONLY; redistribution BLOCKED; evidence/script bindings verified')


if __name__ == '__main__':
    main()
