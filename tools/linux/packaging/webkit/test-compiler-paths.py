#!/usr/bin/env python3
"""Catch compiler source-path leaks before the expensive engine build. No GUI."""
import pathlib
import shlex
import subprocess
import sys
import tempfile

values = {}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    if ':' in line and '=' in line and not line.startswith(('//', '#')):
        key, value = line.split('=', 1)
        values[key.split(':', 1)[0]] = value
with tempfile.TemporaryDirectory(prefix='nuvio compiler paths ') as tmp:
    root = pathlib.Path(tmp)
    source = root / 'source/webkitgtk-2.50.4/Source'
    source.mkdir(parents=True)
    objects = root / 'objects'
    objects.mkdir()
    for language, extension in [('C', 'c'), ('CXX', 'cpp')]:
        name = 'fixture.' + extension
        (source / name).write_text('#include <stdio.h>\nint main(void) { puts(__FILE__); }\n')
        binary = objects / ('probe-' + language)
        subprocess.run([values['CMAKE_' + language + '_COMPILER'],
                        *shlex.split(values['CMAKE_' + language + '_FLAGS_RELEASE']),
                        '../source/webkitgtk-2.50.4/Source/' + name, '-o', str(binary)],
                       cwd=objects, check=True)
        actual = subprocess.check_output([str(binary)], text=True).strip()
        assert actual == 'Source/' + name, (language, actual)
        assert b'/source/' not in binary.read_bytes(), language
print('Configured production C/C++ compiler source-path probes: 2 PASS')
