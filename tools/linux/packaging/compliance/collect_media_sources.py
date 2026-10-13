#!/usr/bin/env python3
"""Collect only manifest-verified source inputs and tracked recipes; no fetch/build.

The result covers private media, not JDK/JAR/AppImage/Ubuntu source closure.
An incomplete or tampered cache fails before producing any archive.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def required_inputs(manifest):
    result = []
    for source in manifest['sources']:
        # The manifest also contains a data-only CA bootstrap .deb. Retain it as
        # an exact build input without mislabelling a binary package as source.
        kind = 'bootstrap-data-package' if source['url'].endswith('.deb') else 'source'
        result.append(dict(name=source['name'], version=source['version'], kind=kind,
                           url=source['url'], sha256=source['sha256']))
        if source.get('signature'):
            sig = source['signature']
            result += [dict(name=source['name'] + '-signature', kind='signature', url=sig['url'], sha256=sig['sha256']),
                       dict(name=source['name'] + '-key', kind='public-key', url=sig['key_url'], sha256=sig['key_sha256'])]
    for row in result:
        if not re.fullmatch('[A-Za-z0-9_.+-]+', row['name']) or not re.fullmatch('[a-f0-9]{64}', row['sha256']) or not row['url'].startswith('https://'):
            raise ValueError('Unsafe source input')
    if len({r['name'] for r in result}) != len(result):
        raise ValueError('Duplicate source input name')
    return result


def collect(recipe, cache, output, recipe_files=None):
    manifest = json.loads((recipe / 'manifest.json').read_text())
    rows = required_inputs(manifest)
    for row in rows:
        path = cache / (row['sha256'] + '.archive')
        if not path.is_file() or path.is_symlink() or sha(path) != row['sha256']:
            raise ValueError('Missing/tampered source input: ' + row['name'])
    if recipe_files is None:
        names = subprocess.check_output(['git', '-C', str(recipe), 'ls-files', '-z', '--', '.'], text=True).split('\0')
        recipe_files = [n for n in names if n]
    for name in recipe_files:
        relative = Path(name)
        path = recipe / relative
        if relative.is_absolute() or '..' in relative.parts or path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(recipe.resolve()):
            raise ValueError('Unsafe/missing tracked recipe input: ' + name)
    receipt = {'schema_version': 1, 'scope': 'private media source inputs and recipes only; not complete application corresponding source',
               'inputs': rows, 'recipes': {n: sha(recipe / n) for n in sorted(recipe_files)},
               'signature_policy': 'signature/key bytes retained by pinned SHA256; separately verify FFmpeg signature as build-runtime.sh does'}
    # Exclusive output: never replace existing evidence. Validate every input before opening it.
    with output.open('xb') as stream:
        with tarfile.open(fileobj=stream, mode='w:gz') as tar:
            data = (json.dumps(receipt, indent=2, sort_keys=True) + '\n').encode()
            entry = tarfile.TarInfo('source-inputs.json'); entry.size = len(data); entry.mode = 0o644
            tar.addfile(entry, io.BytesIO(data))
            for row in rows:
                tar.add(cache / (row['sha256'] + '.archive'), 'archives/' + row['name'] + '.archive', recursive=False)
            for name in sorted(recipe_files):
                tar.add(recipe / name, 'recipe/' + name, recursive=False)
    return receipt


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    recipe = Path(__file__).resolve().parents[2] / 'media-runtime'
    result = collect(recipe, args.cache, args.output)
    print(json.dumps({'archive': args.output.name, 'sha256': sha(args.output), 'inputs': len(result['inputs']), 'scope': result['scope']}, indent=2))


if __name__ == '__main__':
    main()
