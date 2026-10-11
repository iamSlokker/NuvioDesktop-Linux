#!/usr/bin/env python3
"""Compare patched release policy to the verified archive. No engine launch."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tarfile
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
manifest = json.loads((HERE / 'manifest.json').read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('archive', type=pathlib.Path)
args = parser.parse_args()
if hashlib.sha256(args.archive.read_bytes()).hexdigest() != manifest['source']['sha256']:
    raise ValueError('Not the pinned WebKit source archive')
patch = (HERE / 'patches/0001-private-release-paths.patch').read_text()
paths = re.findall(r'^\+\+\+ b/(.+)$', patch, re.M)
with tarfile.open(args.archive) as archive, tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    original = {}
    for relative in paths:
        try:
            data = archive.extractfile('webkitgtk-' + manifest['version'] + '/' + relative).read()
        except KeyError:
            continue  # Added relocation header.
        original[relative] = data.decode()
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1', '-i',
                    str(HERE / 'patches/0001-private-release-paths.patch')], cwd=root,
                   check=True, stdout=subprocess.DEVNULL)
    launcher = 'Source/WebKit/UIProcess/Launcher/glib/BubblewrapLauncher.cpp'
    before, after = original[launcher], (root / launcher).read_text()
    for start, end in [('static int setupSeccomp()', 'static std::optional<CString> directoryContainingDBusSocket'),
                       ('static void bindDBusSession', 'static void bindWayland'),
                       ('static void bindA11y', 'static bool bindPathVar')]:
        assert before[before.index(start):before.index(end)] == after[after.index(start):after.index(end)], start
    for flag in ['--unshare-pid', '--unshare-net', '--unshare-uts', '--seccomp']:
        assert before.count('"' + flag + '"') == after.count('"' + flag + '"'), flag
    proxy = 'Source/WebKit/UIProcess/Launcher/glib/XDGDBusProxy.cpp'
    # Only the include and executable path changed; every filtering argument is
    # byte-identical to upstream, including conditions controlling those rules.
    adjusted = (root / proxy).read_text().replace('#include <wtf/glib/NuvioRelocation.h>\n', '')
    adjusted = adjusted.replace('nuvioPrivatePath("bin/nuvio-dbus-proxy", true)', 'DBUS_PROXY_EXECUTABLE')
    assert adjusted == original[proxy]
    assert '"--ro-bind", nuvioPackageRoot(), nuvioPackageRoot()' in after
    print('Pinned patch applies without fuzz; seccomp, namespace flags and D-Bus filtering unchanged: PASS')
