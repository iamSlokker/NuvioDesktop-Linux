#!/usr/bin/env python3
"""Exercise the actual pinned bitmap-paint body with a recording Skia adapter.

No WebKit compilation, display, network or runtime modification. The original
must fail the replacement contract; the backport must pass. GUI pixel acceptance
of a newly built engine remains a separate opt-in probe.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tarfile
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
RELATIVE = 'Source/WebCore/platform/graphics/skia/ShareableBitmapSkia.cpp'


def exercise(source, compiler, destination):
    start = source.index('void ShareableBitmap::paint(GraphicsContext& context, float scaleFactor,')
    end = source.index('\n}', start) + 2
    code = (HERE / 'test-bitmap-copy.cpp').read_text().replace('@@PAINT@@', source[start:end])
    destination.with_suffix('.cpp').write_text(code)
    subprocess.run([compiler, '-std=c++20', '-Wall', '-Wextra', '-Werror',
                    str(destination.with_suffix('.cpp')), '-o', str(destination)], check=True, timeout=30)
    return subprocess.run([str(destination)], timeout=10).returncode


def check(original, compiler, root):
    target = root / RELATIVE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(original)
    subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1', '-i',
                    str(HERE / 'patches/0002-skia-bitmap-copy.patch')], cwd=root, check=True,
                   stdout=subprocess.DEVNULL, timeout=10)
    if exercise(original, compiler, root / 'original') != 1:
        raise ValueError('Original engine did not reproduce the bitmap replacement defect')
    if exercise(target.read_text(), compiler, root / 'corrected') != 0:
        raise ValueError('Corrected bitmap paint failed Copy/SourceOver/scale contract')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=pathlib.Path)
    parser.add_argument('--compiler', default='c++')
    args = parser.parse_args()
    manifest = json.loads((HERE / 'manifest.json').read_text())
    with args.archive.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        if digest.hexdigest() != manifest['source']['sha256']:
            raise ValueError('Not the pinned WebKit source archive')
    with tarfile.open(args.archive) as archive, tempfile.TemporaryDirectory(prefix='nuvio-bitmap-contract-') as tmp:
        original = archive.extractfile('webkitgtk-' + manifest['version'] + '/' + RELATIVE).read().decode()
        check(original, args.compiler, pathlib.Path(tmp))
    print('Pinned bitmap paint: original FAILS, Copy/SourceOver and 1x/2x backport PASS')


if __name__ == '__main__':
    main()
