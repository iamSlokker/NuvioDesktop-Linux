#!/usr/bin/env python3
"""Transfer the canonical builder by immutable identity, never by a mutable tag.

The expected receipt digest must come from the trusted producing run, not from
the downloaded directory itself. A checksum is integrity, not an attestation.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def image_id(value):
    value = value.removeprefix('sha256:')
    if not re.fullmatch('[0-9a-f]{64}', value):
        raise ValueError('Expected an immutable Podman image ID')
    return value


def inputs():
    # These are builder inputs, not application/engine inputs. Engine-only fixes
    # must not require rebuilding Nuvio just to obtain the same base toolchain.
    names = ['tools/linux/packaging/' + name for name in
             ['build.py', 'Containerfile', 'config.json', 'apt-prefetch.py', 'install-builder-tools.py']]
    media = REPO / 'tools/linux/media-runtime'
    names += [str(p.relative_to(REPO)) for p in media.rglob('*')
              if p.is_file() and p.name != 'README.md' and '__pycache__' not in p.parts]
    return {name: digest(REPO / name) for name in sorted(names)}


def inspect_image(identity):
    actual = subprocess.check_output(['podman', 'image', 'inspect', '--format', '{{.Id}}', identity], text=True).strip()
    if image_id(actual) != image_id(identity):
        raise ValueError('Loaded builder does not match immutable image ID')


def export_seed(packaging, destination):
    release = json.loads((packaging / 'canonical/release-info.json').read_text())
    source_files = json.loads((packaging / 'source-files.json').read_text())
    if hashlib.sha256(json.dumps(source_files).encode()).hexdigest() != release['source_sha256']:
        raise ValueError('Canonical source inventory does not match release identity')
    expected = inputs()
    recorded = dict(source_files)
    if any(recorded.get(name) != sha for name, sha in expected.items()):
        raise ValueError('Canonical builder recipe differs from checkout; export from its matching revision')
    base = release['builder_image_id']
    image_id(base)
    inspect_image(base)
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / 'packaging-builder.oci'
    subprocess.run(['podman', 'save', '--format', 'oci-archive', '--output', str(archive), base], check=True)
    record = dict(schema_version=1, builder_image_id=base, archive_sha256=digest(archive),
                  inputs=expected, canonical_git_commit=release['git_commit'],
                  canonical_source_sha256=release['source_sha256'])
    receipt = destination / 'builder-seed.json'
    receipt.write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    print('Builder seed SHA256: ' + digest(receipt), flush=True)
    return digest(receipt)


def verify_seed(directory, expected_sha256):
    if not re.fullmatch('[0-9a-f]{64}', expected_sha256 or ''):
        raise ValueError('Supply the builder seed SHA256 from the trusted producing run')
    receipt = directory / 'builder-seed.json'
    if digest(receipt) != expected_sha256:
        raise ValueError('Builder seed receipt checksum mismatch')
    data = json.loads(receipt.read_text())
    if data['schema_version'] != 1 or data['inputs'] != inputs():
        raise ValueError('Builder seed recipe mismatch; a new canonical bootstrap is required')
    image_id(data['builder_image_id'])
    if digest(directory / 'packaging-builder.oci') != data['archive_sha256']:
        raise ValueError('Builder archive checksum mismatch')
    return data


def load_seed(directory, expected_sha256):
    data = verify_seed(directory, expected_sha256)
    subprocess.run(['podman', 'load', '--input', str(directory / 'packaging-builder.oci')], check=True)
    inspect_image(data['builder_image_id'])
    return data['builder_image_id']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packaging-work-dir', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    export_seed(args.packaging_work_dir, args.output)
