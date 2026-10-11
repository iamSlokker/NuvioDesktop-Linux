#!/usr/bin/env python3
"""Prepare isolated acceptance tools without opening any window or installing host packages."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from formats import PLATFORM
from webkit.stage import linked_paths


def inside(output):
    import shutil
    output.mkdir(parents=True, exist_ok=True)
    for relative in ['bin/Xvfb', 'bin/xauth', 'bin/xkbcomp']:
        destination = output / relative
        destination.parent.mkdir(exist_ok=True)
        source = pathlib.Path('/usr') / relative
        shutil.copy2(source, destination)
        dependencies = subprocess.check_output(['ldd', str(source)], text=True)
        for name, path in linked_paths(dependencies):
            if PLATFORM.match(name):
                continue
            library = output / 'lib' / name
            library.parent.mkdir(exist_ok=True)
            shutil.copy2(path, library)
    shutil.copytree('/usr/share/X11/xkb', output / 'xkb', dirs_exist_ok=True)
    flags = subprocess.check_output(['pkg-config', '--cflags', '--libs',
                                    'webkit2gtk-4.1', 'gtk+-3.0', 'x11'], text=True).split()
    subprocess.run(['g++-12', '-std=c++17', '-O2', '-I/native', str(HERE / 'probe-overlay.cpp'),
                    '/native/controls_overlay.cpp', '/native/window_chrome.cpp',
                    '-o', str(output / 'probe-overlay'), *flags, '-pthread'], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine-generation', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--inside', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Unset display/live-test variables')
    if args.inside:
        inside(args.output)
        return
    if not args.engine_generation:
        parser.error('--engine-generation is required')
    builder = json.loads((args.engine_generation / 'build-complete.json').read_text())['builder_image_id']
    identity = hashlib.sha256((HERE / 'ProbeContainerfile').read_bytes() + builder.encode()).hexdigest()
    image = 'localhost/nuvio-webkit-probe:' + identity
    subprocess.run(['podman', 'build', '--pull=never', '--network=host', '--file', str(HERE / 'ProbeContainerfile'),
                    '--build-arg', 'WEBKIT_BUILDER_IMAGE=' + builder, '--tag', image, str(HERE)], check=True)
    args.output.mkdir(parents=True, exist_ok=True)
    native = HERE.parents[3] / 'composeApp/src/desktopMain/native/linux'
    subprocess.run(['podman', 'run', '--rm', '--network=none', '--userns=keep-id', '--cap-drop=all',
                    '--security-opt', 'label=disable', '--security-opt', 'no-new-privileges',
                    '--env', 'PYTHONDONTWRITEBYTECODE=1',
                    '--mount', f'type=bind,src={HERE.parent},dst=/packaging,ro',
                    '--mount', f'type=bind,src={native},dst=/native,ro',
                    '--mount', f'type=bind,src={args.output.resolve()},dst=/output', image,
                    'python3', '/packaging/webkit/prepare-probe.py', '--inside', '--output', '/output'], check=True)


if __name__ == '__main__':
    main()
