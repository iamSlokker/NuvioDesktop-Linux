#!/usr/bin/env python3
"""Verify and extract the pinned JDK/GraalVM/CMake inputs inside the builder.

Temurin is the build JDK (Gradle, Kotlin, jpackage); GraalVM CE compiles the
native image that the packages ship. Neither JDK is copied into a package.
"""
import hashlib
import json
import pathlib
import tarfile
import zipfile

p = pathlib.Path('/inputs')
cfg = json.loads((p / 'config.json').read_text())
for name, file in [('jdk', 'jdk.tar.gz'), ('graalvm', 'graalvm.tar.gz'), ('cmake', 'cmake.whl')]:
    digest = hashlib.sha256()
    with (p / file).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    assert digest.hexdigest() == cfg['tools'][name]['sha256'], name
with tarfile.open(p / 'jdk.tar.gz') as t:
    t.extractall('/opt/java')
with tarfile.open(p / 'graalvm.tar.gz') as t:
    t.extractall('/opt/graalvm-dist')
with zipfile.ZipFile(p / 'cmake.whl') as z:
    z.extractall('/opt/cmake-wheel')
