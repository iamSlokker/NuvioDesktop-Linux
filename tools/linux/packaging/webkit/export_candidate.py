#!/usr/bin/env python3
"""Export a completed engine plus its immutable assembly image; no GUI audit."""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import tarfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from webkit.build import recipe_identity, valid_cache
from webkit.builder_seed import digest, inspect_image


def export(work):
    result = json.loads((work / 'engine-result.json').read_text())
    identity = result['recipe_identity']
    if not re.fullmatch('[0-9a-f]{64}', identity):
        raise ValueError('Invalid engine recipe identity')
    generation = work / 'generations' / identity
    record = json.loads((generation / 'build-complete.json').read_text())
    if identity != recipe_identity(record['packaging_builder_image_id']) or not valid_cache(generation, identity):
        raise ValueError('No completed, verified engine candidate for the current recipe to export')
    image = record['builder_image_id']
    inspect_image(image)
    output = work / 'export'
    output.mkdir(exist_ok=True)
    archive = output / 'engine.tar.gz'
    with tarfile.open(archive, 'w:gz') as tar:
        for name in ['build-complete.json', 'install', 'corresponding-source', 'objects/CMakeCache.txt']:
            tar.add(generation / name, arcname=identity + '/' + name)
    subprocess.run(['podman', 'save', '--format', 'oci-archive', '--output', str(output / 'engine-builder.oci'), image], check=True)
    (output / 'SHA256SUMS').write_text(''.join(digest(output / name) + '  ' + name + '\n'
                                             for name in ['engine.tar.gz', 'engine-builder.oci']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=pathlib.Path, required=True)
    export(parser.parse_args().work_dir.resolve())
