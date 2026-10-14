#!/usr/bin/env python3
"""Author synthetic release artwork with existing Inter and headless JDK17.

Outputs a separate review directory. Never rewrites source or uses old images.
Pillow/libwebp is an optional authoring tool, not an application dependency.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, features, __version__ as pillow_version

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]


def generate(output, java_home):
    output = output.absolute()
    if output.exists() or any(p.is_symlink() for p in (output, *output.parents)):
        raise ValueError('Fresh output without symlinked ancestors required')
    manifest = json.loads((HERE.parent / 'fonts/inter-manifest.json').read_text())
    for row in manifest['files']:
        if hashlib.sha256((REPO / row['path']).read_bytes()).hexdigest() != row['sha256']:
            raise ValueError('Pinned Inter input changed')
    version = subprocess.check_output([str(java_home / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
    if 'version "17.' not in version:
        raise ValueError('Use the existing JDK17 authoring toolchain')
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='nuvio-artwork-authoring-') as scratch:
        env = {k: v for k, v in os.environ.items() if k in ('PATH', 'LANG')}
        env.update(HOME=scratch, XDG_CONFIG_HOME=scratch, XDG_CACHE_HOME=scratch)
        subprocess.run([str(java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', scratch,
                        str(HERE / 'GenerateReleaseArtwork.java')], env=env, check=True)
        subprocess.run([str(java_home / 'bin/java'), '-Djava.awt.headless=true', '-Duser.home=' + scratch,
                        '-cp', scratch, 'GenerateReleaseArtwork', str(REPO), str(output)], env=env, check=True)
    for source in output.glob('setup_mode_*.png'):
        with Image.open(source) as image:
            image.save(source.with_suffix('.webp'), format='WEBP', lossless=True, exact=True, method=6)
    overlay = output / 'linux-overlay'
    receipt = {'scope': 'Asset authoring only; no runtime, package or rights certification',
               'jdk': version.splitlines()[0], 'pillow': pillow_version, 'libwebp': features.version('webp'),
               'inputs': manifest['files'],
               'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.iterdir()) if p.is_file()},
               # Linux-only package overlay; mirrors tools/linux/packaging/artwork/overlay.
               'overlay_files': {p.relative_to(overlay).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in sorted(overlay.rglob('*')) if p.is_file()}}
    (output / 'authoring-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    generate(args.output, args.java_home)
