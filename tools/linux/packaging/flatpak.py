#!/usr/bin/env python3
"""Export canonical source-built output; no host installation or remote changes.

The GNOME Platform supplies GTK3/WebKitGTK4.1 and generic graphics/audio ABI.
Native source/private media are built on the older Jammy ABI and verified here
against this runtime. No DEB or AppImage is an input.
"""
import datetime
import json
import os
import pathlib
import shlex
import shutil
import subprocess
import sys

from fetch import CONFIG
from client_config import verify_native_backend, verify_packaged_backend
import native_evidence

HERE = pathlib.Path(__file__).resolve().parent


def launcher_text(image):
    # Flatpak injects GNOME graphics/codec extension paths into LD_LIBRARY_PATH.
    # Keep them after our private runtime; the sandbox owns this namespace.
    return '''#!/bin/sh
set -eu
image=%s
unset LD_PRELOAD
export LD_LIBRARY_PATH="$image/lib/native:$image/lib/runtime/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export GDK_BACKEND=x11
exec "$image/lib/nuvio" "$@"
''' % shlex.quote(str(image))


def build(work):
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        raise ValueError('Unset display/live-test environment')
    canonical = work / 'canonical'
    # Only the native image that passed the smoke gate, with its retained inputs.
    native = native_evidence.verify(canonical, work / 'native-image')
    native_evidence.check_smoke(work / 'native-image', canonical)
    verify_packaged_backend(canonical, native.jars)
    verify_native_backend(native.executable)
    info = json.loads((canonical / 'release-info.json').read_text())
    expected = json.loads((work / 'source-info.json').read_text())
    if any(info[key] != expected[key] for key in ['git_commit', 'source_sha256', 'channel']):
        raise ValueError('Refusing stale canonical image from a different source snapshot')
    app_id, branch = CONFIG['application_id'], CONFIG['flatpak_branch']
    runtime = CONFIG['flatpak_runtime']
    # Uses an already installed runtime; never installs into user/system Flatpak.
    runtime_commit = subprocess.check_output(['flatpak', 'info', '--show-commit', runtime + '//' + branch], text=True).strip()
    if runtime_commit != CONFIG['flatpak_runtime_commit']:
        raise ValueError('Installed GNOME runtime differs from pinned commit; use an isolated packaging installation')
    root = work / 'flatpak-build'
    shutil.rmtree(root, ignore_errors=True)
    # No compilation occurs in this sandbox: Platform is sufficient as build SDK.
    subprocess.run(['flatpak', 'build-init', str(root), app_id, runtime, runtime, branch], check=True)
    image = root / 'files/lib/nuvio-desktop'
    shutil.copytree(canonical, image, symlinks=True)
    # Use the SDK/runtime Vulkan loader and driver extensions, never a second loader.
    for p in (image / 'lib/native').glob('libvulkan.so*'):
        p.unlink()
    bin_dir = root / 'files/bin'
    bin_dir.mkdir()
    launcher = bin_dir / 'nuvio-desktop'
    launcher.write_text(launcher_text('/app/lib/nuvio-desktop'))
    launcher.chmod(0o755)
    for directory in ['applications', 'icons']:
        shutil.copytree(image / 'share' / directory, root / 'files/share' / directory, dirs_exist_ok=True)
    permissions = json.loads((HERE / 'flatpak-permissions.json').read_text())
    provenance = dict(app_id=app_id, runtime=runtime, runtime_version=branch,
                    runtime_commit=runtime_commit, sdk=runtime, command='nuvio-desktop',
                    finish_args=permissions, source_commit=info['git_commit'], source_sha256=info['source_sha256'])
    (image / 'flatpak-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    # Static ELF/loader checks inside an isolated build sandbox; no application,
    # GTK initialization, audio device, display socket or external service used.
    subprocess.run(['flatpak', 'build', '--unshare=network', '--nofilesystem=host', str(root),
                    '/usr/bin/sh', '-eu', '-c', '''
unset DISPLAY WAYLAND_DISPLAY NUVIO_RUN_LIVE_DISPLAY_TESTS LD_PRELOAD
# lib/ last: libjawt.so finds libawt.so there only because the executable already loaded it.
export LD_LIBRARY_PATH=/app/lib/nuvio-desktop/lib/native:/app/lib/nuvio-desktop/lib/runtime/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}:/app/lib/nuvio-desktop/lib
test -x /app/lib/nuvio-desktop/lib/native/nuvio-hdr-probe
test -x /app/lib/nuvio-desktop/lib/nuvio
{ find /app/lib/nuvio-desktop/lib/native /app/lib/nuvio-desktop/lib/runtime -type f \\( -name '*.so*' -o -name nuvio-hdr-probe \\)
  find /app/lib/nuvio-desktop/lib -maxdepth 1 -type f \\( -name '*.so' -o -name nuvio \\); } | xargs sh -c '
 for f do
   result=$(ldd "$f") || exit 1
   case "$result" in *"not found"*) printf "%s\\n%s\\n" "$f" "$result"; exit 1;; esac
 done
' sh
'''], check=True)
    subprocess.run(['flatpak', 'build-finish', '--command=nuvio-desktop', *permissions, str(root)], check=True)
    repository = work / 'flatpak-repo'
    # Branch contains channel and revision; stable and development are explicit.
    export_branch = info['channel']
    timestamp = datetime.datetime.fromtimestamp(info['source_date_epoch'], datetime.timezone.utc).isoformat()
    subprocess.run(['flatpak', 'build-export', '--timestamp=' + timestamp,
                    str(repository), str(root), export_branch], check=True)
    output = work / 'artifacts' / f'Nuvio-Desktop-{info["version"]}-{info["git_commit"][:12]}-x86_64.flatpak'
    # The CI commit is unsigned, so no --repo-url/--gpg-keys here: an embedded
    # key makes Flatpak refuse an unsigned install. The owner-side signed
    # rebundle (flatpak_repo.py) adds both; the runtime source is shared.
    runtime_repo = json.loads((HERE / 'flatpak-repo/repo.json').read_text())['runtime_repo']
    subprocess.run(['flatpak', 'build-bundle', '--arch=x86_64', '--runtime-repo=' + runtime_repo,
                    str(repository), str(output), app_id, export_branch], check=True)
    shutil.copy2(image / 'flatpak-provenance.json', work / 'artifacts/flatpak-provenance.json')
    # Standard Flatpak application manifest, generated by build-init/build-finish.
    # The JSON above is provenance, not a parallel flatpak-builder build system.
    shutil.copy2(root / 'metadata', work / 'artifacts/flatpak-metadata.ini')


if __name__ == '__main__':
    build(pathlib.Path(sys.argv[1]).resolve())
