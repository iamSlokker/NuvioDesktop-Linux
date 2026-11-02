#!/usr/bin/env python3
"""Corresponding source of InnerTubeX (GPL-3.0), compiled into the native image from JitPack.

Gradle fetches the JAR (-Pnuvio.innertubex=true in native-build.sh); this keeps the matching tag
archive, verified against a pinned hash, for the release's source assets.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from fetch import fetch  # noqa: E402

VERSION = 'v0.7.0'
SOURCE = {'url': 'https://codeload.github.com/MetrolistGroup/innertubex/tar.gz/refs/tags/' + VERSION,
          'sha256': '003fed3569e4ea50b20fbcfd7366e5a9f13617d35d475da8f97eaf31efe77485'}
ARCHIVE = 'Nuvio-InnerTubeX-source.tar.gz'


def gradle_version(build_script):
    """The version composeApp/build.gradle.kts compiles in; the two must never drift apart."""
    match = re.search(r'innertubex:innertubex:([^"]+)"', pathlib.Path(build_script).read_text())
    if not match:
        raise ValueError('InnerTubeX dependency not found in ' + str(build_script))
    return match.group(1)


def main(work):
    repository = HERE.parents[2]
    version = gradle_version(repository / 'composeApp/build.gradle.kts')
    if version != VERSION:
        raise ValueError(f'build.gradle.kts compiles InnerTubeX {version}, source pin is {VERSION}')
    fetch(SOURCE, pathlib.Path(work) / 'innertubex' / ARCHIVE)


if __name__ == '__main__':
    main(sys.argv[1])
