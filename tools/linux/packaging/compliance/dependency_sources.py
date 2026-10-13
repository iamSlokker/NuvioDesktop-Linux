#!/usr/bin/env python3
"""Recheck retained Maven source receipts offline; never execute or extract JARs.

This verifies local retention, not complete Corresponding Source, legal rights,
reproducibility, or public availability. In particular, native sources are not
established by a sources.jar (which can itself contain precompiled binaries).
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked_file(root, name, expected):
    relative = PurePosixPath(name)
    if (not name or relative.is_absolute() or '..' in relative.parts or
            '\\' in name or relative.as_posix() != name or not relative.parts):
        raise ValueError(f'unsafe source path: {name!r}')
    if not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError(f'missing/invalid SHA256: {name}')
    path = root
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError(f'symlink source input: {name}')
    if not path.is_file() or sha256(path) != expected:
        raise ValueError(f'missing/tampered source input: {name}')
    return path


def inspect_sources(path):
    code, natives, notices = [], [], []
    with zipfile.ZipFile(path) as archive:
        seen = set()
        for entry in archive.infolist():
            name = entry.filename
            relative = PurePosixPath(name)
            if (not relative.parts or name in seen or relative.is_absolute() or '..' in relative.parts or
                    '\\' in name or relative.as_posix() != name.rstrip('/') or
                    stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError(f'unsafe/duplicate source JAR member: {name!r}')
            seen.add(name)
            if entry.is_dir():
                continue
            data = archive.read(entry)  # Also checks each entry's CRC.
            digest = hashlib.sha256(data).hexdigest()
            if name.endswith(('.java', '.kt', '.c', '.cpp', '.h')):
                code.append(name)
            if name.endswith(('.so', '.dll', '.dylib', '.jnilib')):
                natives.append({'path': name, 'sha256': digest})
            if (relative.suffix.lower() not in ('.java', '.kt', '.c', '.cpp', '.h') and
                    re.fullmatch(r'(license|notice|copying|copyright)([._-].*)?', relative.name.lower())):
                notices.append({'path': name, 'sha256': digest})
    return {'code_file_count': len(code), 'embedded_native_binaries': natives, 'notices': notices}


def verify(inventory, receipt, root):
    if root.is_symlink():
        raise ValueError('source root must not be a symlink')
    if receipt['audit_package_commit'] != inventory['package_commit']:
        raise ValueError('receipt package identity mismatch')
    components = {k: v for k, v in inventory['components'].items() if k.startswith('maven:')}
    rows = receipt['maven']
    ids = [row['component'] for row in rows]
    if len(set(ids)) != len(ids) or set(ids) != set(components):
        raise ValueError('receipt must cover each inventoried Maven component exactly once')
    paths = set()
    for row in rows:
        component = components[row['component']]
        source = component['source']
        if (row['url'] != source['url'] or
                row['binary_sha256'] != component['upstream_binary_sha256']):
            raise ValueError('source URL/binary identity mismatch')
        if row['path'] in paths:
            raise ValueError('duplicate source receipt path')
        paths.add(row['path'])
        path = checked_file(root, row['path'], row['sha256'])
        if source['sha256'] and source['sha256'] != row['sha256']:
            raise ValueError('published Gradle source SHA256 mismatch')
        if row.get('repository_checksum'):
            checksum = row['repository_checksum']
            if checksum['algorithm'] not in ('sha1', 'sha256'):
                raise ValueError('unsupported repository checksum')
            with path.open('rb') as stream:
                actual = hashlib.file_digest(stream, checksum['algorithm']).hexdigest()
            if actual != checksum['digest']:
                raise ValueError('repository checksum mismatch')
        metadata = {item['url']: item for item in row['metadata']}
        if len(metadata) != len(row['metadata']):
            raise ValueError('duplicate metadata receipt')
        expected_metadata = {component['pom']['url']: component['pom']['sha256']}
        if source.get('metadata_sha256'):
            expected_metadata[source['metadata_url']] = source['metadata_sha256']
        if set(metadata) != set(expected_metadata):
            raise ValueError('missing/extra source metadata')
        for url, expected in expected_metadata.items():
            if metadata[url]['sha256'] != expected:
                raise ValueError('metadata receipt hash mismatch')
            checked_file(root, metadata[url]['path'], expected)
        parent = component['pom'].get('parent_license_evidence')
        expected_parents = {}
        while parent:
            coordinates = parent['coordinates']
            if coordinates in expected_parents:
                raise ValueError('cyclic parent license evidence')
            expected_parents[coordinates] = parent['sha256']
            parent = parent.get('parent_license_evidence')
        parents = row.get('parent_metadata', [])
        if (len({p['coordinates'] for p in parents}) != len(parents) or
                {p['coordinates']: p['sha256'] for p in parents} != expected_parents):
            raise ValueError('missing/changed parent license metadata')
        for parent in parents:
            checked_file(root, parent['path'], parent['sha256'])
        actual = inspect_sources(path)
        for key, value in actual.items():
            if row[key] != value:
                raise ValueError(f'source content receipt mismatch: {key}')
    additional = receipt.get('additional_retained_inputs', [])
    additional_paths = [item['path'] for item in additional]
    if len(set(additional_paths)) != len(additional_paths) or set(additional_paths) & paths:
        raise ValueError('duplicate additional input path')
    for item in additional:
        checked_file(root, item['path'], item['sha256'])
    return {'verified_maven_source_artifacts': len(rows),
            'verified_additional_inputs': len(additional),
            'code_empty_artifacts': sum(row['code_file_count'] == 0 for row in rows),
            'source_artifacts_containing_native_binaries': sum(bool(row['embedded_native_binaries']) for row in rows),
            'assessment': 'LOCAL_SOURCE_RETENTION_VERIFIED; NOT_REDISTRIBUTION_APPROVAL'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--sources', type=Path, required=True)
    args = parser.parse_args()
    result = verify(json.loads(args.inventory.read_text()), json.loads(args.receipt.read_text()), args.sources)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
