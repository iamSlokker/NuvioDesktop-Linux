#!/usr/bin/env python3
"""Verify and extract the pinned JDK/CMake inputs inside the builder."""
import hashlib
import json
import pathlib
import tarfile
import zipfile

p = pathlib.Path('/inputs')
cfg = json.loads((p / 'config.json').read_text())
for name, file in [('jdk', 'jdk.tar.gz'), ('cmake', 'cmake.whl')]:
    assert hashlib.sha256((p / file).read_bytes()).hexdigest() == cfg['tools'][name]['sha256'], name
with tarfile.open(p / 'jdk.tar.gz') as t:
    t.extractall('/opt/java')
with zipfile.ZipFile(p / 'cmake.whl') as z:
    z.extractall('/opt/cmake-wheel')
