#!/usr/bin/env python3
"""Prepare pinned WebKit source inside the builder, never with host patch tools."""
import json
import os
import pathlib
import shutil
import subprocess

from build import MANIFEST, digest, inventory

archive = pathlib.Path('/source-archive.tar.xz')
if digest(archive) != MANIFEST['source']['sha256']:
    raise ValueError('Pinned WebKit source digest mismatch')
identity = os.environ['NUVIO_WEBKIT_RECIPE_ID']
source = pathlib.Path('/work/source')
record = pathlib.Path('/work/source-ready.json')
if record.is_file():
    if json.loads(record.read_text()) != dict(recipe_identity=identity, files=inventory(source, source=True)):
        raise ValueError('Prepared WebKit source was modified; refusing incremental reuse')
else:
    # Only this recipe-keyed generation's unrecorded extraction is replaceable.
    shutil.rmtree(source, ignore_errors=True)
    source.mkdir()
    subprocess.run(['tar', '--extract', '--file', str(archive), '--directory', str(source),
                    '--no-same-owner', '--no-same-permissions'], check=True)
    for patch in sorted(pathlib.Path('/recipe/patches').glob('*.patch')):
        subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1', '-i', str(patch)],
                       cwd=source / ('webkitgtk-' + MANIFEST['version']), check=True)
    record.write_text(json.dumps(dict(recipe_identity=identity, files=inventory(source, source=True))) + '\n')
