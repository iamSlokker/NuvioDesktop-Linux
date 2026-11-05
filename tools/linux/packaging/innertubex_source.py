#!/usr/bin/env python3
"""Corresponding source of InnerTubeX and its QuackJS (both GPL-3.0), compiled into the native image from JitPack.

Gradle fetches the JARs (-Pnuvio.innertubex=true in native-build.sh); this keeps the matching tag
archives, verified against pinned hashes, in one of the release's source assets.
"""
import gzip
import os
import pathlib
import re
import sys
import tarfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from fetch import fetch  # noqa: E402

VERSION = 'v0.11.2'
SOURCE = {'url': 'https://codeload.github.com/MetrolistGroup/innertubex/tar.gz/refs/tags/' + VERSION,
          'sha256': '43f570ba65671497841e9698c3835ffc23aa53cf9ea0e70361ae25bd98285be2'}
QUACKJS_VERSION = '0.1.2'
QUACKJS_SOURCE = {'url': 'https://codeload.github.com/MetrolistGroup/quackjs/tar.gz/refs/tags/' + QUACKJS_VERSION,
                  'sha256': '7c7c99620dcff114d0466bafbd64afcb04cb095c97f2afea9e38dd9f94259061'}
ARCHIVE = 'Nuvio-InnerTubeX-source.tar.gz'


def gradle_version(build_script):
    """The version composeApp/build.gradle.kts compiles in; the two must never drift apart."""
    match = re.search(r'innertubex:innertubex:([^"]+)"', pathlib.Path(build_script).read_text())
    if not match:
        raise ValueError('InnerTubeX dependency not found in ' + str(build_script))
    return match.group(1)


def gradle_quackjs_version(build_script):
    """The QuackJS the build script pins; InnerTubeX brings it in, and the JAR check names its version."""
    match = re.search(r'"quackjs-jvm-([^"]+)\.jar"', pathlib.Path(build_script).read_text())
    if not match:
        raise ValueError('QuackJS pin not found in ' + str(build_script))
    return match.group(1)


def archive(output, tarballs, epoch):
    with output.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as tar:
        for tarball in tarballs:
            info = tarfile.TarInfo(tarball.name)
            info.size, info.mode, info.mtime = tarball.stat().st_size, 0o644, epoch
            with tarball.open('rb') as stream:
                tar.addfile(info, stream)


def main(work):
    repository = HERE.parents[2]
    build_script = repository / 'composeApp/build.gradle.kts'
    version = gradle_version(build_script)
    if version != VERSION:
        raise ValueError(f'build.gradle.kts compiles InnerTubeX {version}, source pin is {VERSION}')
    quackjs = gradle_quackjs_version(build_script)
    if quackjs != QUACKJS_VERSION:
        raise ValueError(f'build.gradle.kts compiles QuackJS {quackjs}, source pin is {QUACKJS_VERSION}')
    out = pathlib.Path(work) / 'innertubex'
    tarballs = [fetch(SOURCE, out / f'innertubex-{VERSION}.tar.gz'),
                fetch(QUACKJS_SOURCE, out / f'quackjs-{QUACKJS_VERSION}.tar.gz')]
    archive(out / ARCHIVE, tarballs, int(os.environ.get('SOURCE_DATE_EPOCH', '0')))


if __name__ == '__main__':
    main(sys.argv[1])
