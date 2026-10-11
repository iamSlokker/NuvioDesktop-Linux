#!/usr/bin/env python3
"""Headless host namespace/mount probe, not whole-WebKit sandbox acceptance."""
import argparse
import json
import os
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('helper', type=pathlib.Path)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
    parser.error('Unset display/live-test variables')
with tempfile.TemporaryDirectory(prefix='nuvio sandbox probe ') as tmp:
    root = pathlib.Path(tmp)
    image = root / 'private image'
    image.mkdir()
    (image / 'sentinel').write_text('private resource\n')
    outside = root / 'outside'
    outside.write_text('must not be visible\n')
    child = '''import pathlib, os, json, sys
image = pathlib.Path(sys.argv[1])
assert (image / 'sentinel').read_text() == 'private resource\\n'
try: pathlib.Path(sys.argv[2]).stat()
except FileNotFoundError: pass
else: raise AssertionError('outside sentinel visible')
try: (image / 'forbidden-write').write_text('no')
except OSError as error: assert error.errno == 30, error
else: raise AssertionError('image writable')
status = pathlib.Path('/proc/self/status').read_text()
assert 'NoNewPrivs:\\t1' in status
assert os.readlink('/proc/self/ns/net') != sys.argv[3]
assert os.readlink('/proc/self/ns/pid') != sys.argv[4]
print(json.dumps(dict(private_resource=True, outside_hidden=True, read_only=True, status=status)))
'''
    command = [str(args.helper.resolve()), '--ro-bind', '/usr', '/usr', '--symlink', 'usr/bin', '/bin',
               '--symlink', 'usr/lib', '/lib', '--symlink', 'usr/lib64', '/lib64', '--proc', '/proc',
               '--dev', '/dev', '--tmpfs', '/tmp', '--dir', '/etc', '--unshare-all', '--die-with-parent',
               '--ro-bind', str(image), str(image), '/usr/bin/python3', '-c', child, str(image), str(outside),
               os.readlink('/proc/self/ns/net'), os.readlink('/proc/self/ns/pid')]
    result = subprocess.run(command, text=True, capture_output=True, timeout=20)
    evidence = dict(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr,
                    scope='private Bubblewrap helper only; no WebKit/display/seccomp acceptance')
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    if result.returncode:
        raise RuntimeError('Sandbox helper failed; no unsandboxed fallback: ' + result.stderr)
    print('Private helper namespace, read-only mount and outside-sentinel checks PASS')
