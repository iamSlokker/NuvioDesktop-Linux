#!/usr/bin/env python3
"""Prepare Ubuntu's GTK source once and reject modified incremental inputs."""
import json
import pathlib
import subprocess
import sys

sys.path.insert(0, '/recipe')
from build import inventory

root = pathlib.Path('/work/gtk-source')
record = pathlib.Path('/work/gtk-source-ready.json')
if record.exists():
    if json.loads(record.read_text()) != inventory(root):
        raise ValueError('Prepared GTK source was modified; refusing incremental reuse')
else:
    if root.exists():
        raise ValueError('Unrecorded GTK source; use a fresh engine generation')
    subprocess.run(['dpkg-source', '-x', '/gtk-inputs/gtk+3.0_3.24.33-1ubuntu2.2.dsc', str(root)], check=True)
    subprocess.run(['python3', '/recipe/clean-gtk-resources.py', str(root)], check=True)
    subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1', '-i',
                    '/recipe/gtk-private-resources.patch'], cwd=root, check=True)
    subprocess.run(['env', 'NOCONFIGURE=1', './autogen.sh'], cwd=root, check=True)
    record.write_text(json.dumps(inventory(root)) + '\n')
