#!/usr/bin/env python3
"""Import a trusted completed CI engine; never substitute compilation for failure."""
import argparse
import json
import os
import pathlib
import posixpath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from webkit.build import recipe_identity, valid_cache
from webkit.builder_seed import digest, image_id, inspect_image


def extract_engine(archive, destination, identity):
    """Extract only engine payloads, without following archive links or owners."""
    with tarfile.open(archive, 'r:gz') as tar:
        members = tar.getmembers()
        seen, links = set(), set()
        for member in members:
            path = pathlib.PurePosixPath(member.name)
            if (path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != identity
                    or str(path) in seen or member.mode & 0o6000):
                raise ValueError('Unsafe/duplicate engine archive path: ' + member.name)
            seen.add(str(path))
            relative = path.parts[1:]
            allowed = (not relative or relative[0] in ['install', 'corresponding-source']
                       or relative == ('build-complete.json',) or relative == ('objects', 'CMakeCache.txt'))
            if not allowed or not (member.isdir() or member.isfile() or member.issym()):
                raise ValueError('Unexpected engine archive entry: ' + member.name)
            if member.issym():
                target = pathlib.PurePosixPath(posixpath.normpath(str(path.parent / member.linkname)))
                if pathlib.PurePosixPath(member.linkname).is_absolute() or target.parts[0] != identity:
                    raise ValueError('Escaping engine archive symlink: ' + member.name)
                links.add(path)
        for member in members:
            path = pathlib.PurePosixPath(member.name)
            if any(parent in links for parent in path.parents):
                raise ValueError('Engine archive writes through a symlink: ' + member.name)
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True)
            if member.isdir():
                target.mkdir(exist_ok=True)
            elif member.isfile():
                with tar.extractfile(member) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(member.mode & 0o777)
        for member in members:
            if member.issym():
                (destination / member.name).symlink_to(member.linkname)


def import_candidate(source, expected_sha256, work, base):
    if not re.fullmatch('[0-9a-f]{64}', expected_sha256 or '') or digest(source / 'SHA256SUMS') != expected_sha256:
        raise ValueError('Engine checksum manifest does not match trusted run')
    checksums = {}
    for line in (source / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch('([0-9a-f]{64})  (engine.tar.gz|engine-builder.oci)', line)
        if not match or match[2] in checksums:
            raise ValueError('Invalid engine checksum manifest')
        checksums[match[2]] = match[1]
    if set(checksums) != {'engine.tar.gz', 'engine-builder.oci'}:
        raise ValueError('Incomplete engine checksum manifest')
    for name, sha in checksums.items():
        if (source / name).is_symlink() or digest(source / name) != sha:
            raise ValueError('Engine artifact checksum mismatch: ' + name)
    image_id(base)
    identity = recipe_identity(base)
    generations = work / 'generations'
    generations.mkdir(parents=True, exist_ok=True)
    target = generations / identity
    if target.exists():
        raise ValueError('Refusing to replace an existing engine generation')
    with tempfile.TemporaryDirectory(prefix='.import-', dir=generations) as tmp:
        staging = pathlib.Path(tmp)
        extract_engine(source / 'engine.tar.gz', staging, identity)
        generation = staging / identity
        if not valid_cache(generation, identity):
            raise ValueError('Incomplete engine candidate')
        record = json.loads((generation / 'build-complete.json').read_text())
        if record['packaging_builder_image_id'] != base:
            raise ValueError('Engine canonical builder mismatch')
        image_id(record['builder_image_id'])
        subprocess.run(['podman', 'load', '--input', str(source / 'engine-builder.oci')], check=True, stdout=sys.stderr)
        inspect_image(record['builder_image_id'])
        generation.rename(target)
    return target


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=pathlib.Path, required=True)
    parser.add_argument('--sha256', required=True, help='SHA256SUMS digest from the trusted producing run')
    parser.add_argument('--packaging-work-dir', type=pathlib.Path, required=True)
    parser.add_argument('--work-dir', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Unset display/live-test variables')
    base = json.loads((args.packaging_work_dir / 'canonical/release-info.json').read_text())['builder_image_id']
    generation = import_candidate(args.candidate.resolve(), args.sha256, args.work_dir.resolve(), base)
    print(generation)
