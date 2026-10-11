#!/usr/bin/env python3
"""Explicit isolated Xvfb acceptance, never the active desktop or Nuvio profile.

Copies the completed image, substitutes ONLY the fixture executable for Nuvio,
and runs the unchanged AppImage wrapper with the production controls overlay.
No resulting directory is an exportable application artifact.
"""
import argparse
import json
import os
import pathlib
import resource
import secrets
import selectors
import shutil
import signal
import struct
import subprocess
import tempfile

from contract import check_runtime


def authority(path, cookie, display):
    fields = [b'', display.encode(), b'MIT-MAGIC-COOKIE-1', cookie]
    path.write_bytes(struct.pack('!H', 65535) + b''.join(struct.pack('!H', len(x)) + x for x in fields))
    path.chmod(0o600)


def line_with_timeout(stream, timeout):
    with selectors.DefaultSelector() as selector:
        selector.register(stream, selectors.EVENT_READ)
        if not selector.select(timeout):
            raise TimeoutError('Isolated probe readiness deadline exceeded')
        return stream.readline().strip()


def process_evidence(root):
    result = []
    parent_status = dict(line.split(':', 1) for line in pathlib.Path('/proc/self/status').read_text().splitlines()
                         if ':' in line)
    for proc in pathlib.Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            executable = (proc / 'exe').resolve(strict=True)
            if not executable.is_relative_to(root):
                continue
            status = (proc / 'status').read_text()
            entry = dict(pid=int(proc.name), executable=str(executable.relative_to(root)),
                         status=status, mounts=(proc / 'mountinfo').read_text(),
                         command=(proc / 'cmdline').read_bytes().decode(errors='replace').rstrip('\0').split('\0'),
                         maps=(proc / 'maps').read_text(),
                         namespaces={name: os.readlink(proc / 'ns' / name) for name in ['user', 'pid', 'mnt', 'net']})
            result.append(entry)
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
    web = [p for p in result if p['executable'].endswith('/WebKitWebProcess')]
    if not web:
        raise AssertionError('No inspectable private WebProcess; sandbox acceptance is not established')
    engine_processes = [p for p in result if p['executable'] == 'bin/nuvio'
                        or p['executable'].startswith('lib/webkit2gtk-4.1/WebKit')]
    for entry in engine_processes:
        mapped = [line.split(maxsplit=5)[5] for line in entry['maps'].splitlines() if len(line.split(maxsplit=5)) == 6]
        for name in ['libwebkit2gtk-4.1.so', 'libjavascriptcoregtk-4.1.so', 'libgtk-3.so', 'libgdk-3.so']:
            paths = {p for p in mapped if pathlib.Path(p).name.startswith(name)}
            assert paths and all(p.startswith(str(root / 'lib/native') + '/') for p in paths), name
    for entry in web:
        status = dict(line.split(':', 1) for line in entry['status'].splitlines() if ':' in line)
        assert status['NoNewPrivs'].strip() == '1'
        assert status['Seccomp'].strip() == '2'
        if 'Seccomp_filters' in status and 'Seccomp_filters' in parent_status:
            # Mode 2 alone could be an inherited runner filter. Require WebKit
            # to add its own filter where the kernel exposes the filter count.
            added = int(status['Seccomp_filters']) - int(parent_status['Seccomp_filters'])
            assert added > 0, 'No additional WebProcess seccomp filter'
            entry['additional_seccomp_filters'] = added
        for namespace in ['pid', 'mnt', 'net']:
            assert entry['namespaces'][namespace] != os.readlink('/proc/self/ns/' + namespace), namespace
        escaped = str(root).replace(' ', '\\040')
        mounts = [line.split() for line in entry['mounts'].splitlines()]
        assert any(parts[4] == escaped and 'ro' in parts[5].split(',') for parts in mounts), 'private image not read-only'
    return result


def terminate(process):
    if process is None:
        return
    # Children may outlive their group leader after an early failure. Both
    # groups were created by this harness with start_new_session=True.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    if process.poll() is None:
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=pathlib.Path, required=True)
    parser.add_argument('--tools', type=pathlib.Path, required=True)
    parser.add_argument('--controls', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Start with display/live-test variables unset; only the dedicated Xvfb display is allowed')
    image, tools, controls = args.image.resolve(), args.tools.resolve(), args.controls.resolve()
    check_runtime(image)
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = []
    # One actual /tmp location exercises restoration after WebKit replaces /tmp
    # in its child namespace. Do not silently skip this case on a small tmpfs.
    image_bytes = sum(p.stat().st_size for p in image.rglob('*') if p.is_file() and not p.is_symlink())
    if shutil.disk_usage('/tmp').free < image_bytes + 512 * 1024**2:
        raise RuntimeError('Insufficient /tmp space for isolated relocation acceptance')
    for label, directory in [('first image', '/tmp'), ('unrelated relocated image', args.output)]:
        # XDG runtime sockets have Linux's sockaddr_un path-length limit. Keep
        # this separate from the deliberately long/space-containing image path,
        # as a real session does with /run/user/<uid>.
        with tempfile.TemporaryDirectory(prefix='isolated-webkit-', dir=directory) as tmp, \
                tempfile.TemporaryDirectory(prefix='wk-runtime-', dir='/tmp') as runtime_tmp:
            base = pathlib.Path(tmp).resolve()
            root = base / label
            shutil.copytree(image, root, symlinks=True)
            launcher = root / 'bin/nuvio-desktop'
            if label.startswith('unrelated'):
                alias = base / 'symlinked entry'
                alias.symlink_to(root.name, target_is_directory=True)
                launcher = alias / 'bin/nuvio-desktop'
            shutil.copy2(tools / 'probe-overlay', root / 'bin/nuvio')
            shutil.copytree(controls, root / 'share/probe-controls')
            (base / 'outside-sentinel').write_text('not an allowed WebProcess mount')
            profile = base / 'profile'
            for name in ['home', 'config', 'data', 'cache', 'tmp']:
                (profile / name).mkdir(parents=True, mode=0o700)
            # Match NativePlayerBridge's temporary controls-asset placement.
            # NetworkProcess must load these files; do not accidentally prove
            # only a page already inside the private image's read-only mount.
            exported_controls = profile / 'tmp/nuvio-player-ui'
            shutil.copytree(controls, exported_controls)
            env = dict(LANG='C.UTF-8', LC_ALL='C.UTF-8', TZ='UTC')
            env.update(HOME=str(profile / 'home'), XDG_CONFIG_HOME=str(profile / 'config'),
                       XDG_DATA_HOME=str(profile / 'data'), XDG_CACHE_HOME=str(profile / 'cache'),
                       XDG_RUNTIME_DIR=str(pathlib.Path(runtime_tmp).resolve()), TMPDIR=str(profile / 'tmp'),
                       PATH=str(tools / 'bin') + ':/usr/bin:/bin', LD_LIBRARY_PATH=str(tools / 'lib'))
            auth = base / 'server.auth'
            cookie = secrets.token_bytes(16)
            authority(auth, cookie, '0')
            read_fd, write_fd = os.pipe()
            server = probe = None
            log = args.output / (label.replace(' ', '-') + '.log')
            with log.open('w') as output:
                try:
                    server = subprocess.Popen([str(tools / 'bin/Xvfb'), '-displayfd', str(write_fd),
                        '-auth', str(auth), '-screen', '0', '800x600x24', '-nolisten', 'tcp', '-extension', 'GLX',
                        '-noreset', '-xkbdir', str(tools / 'xkb')], env=env, pass_fds=[write_fd],
                        stdout=output, stderr=output, start_new_session=True)
                    os.close(write_fd)
                    with os.fdopen(read_fd) as stream:
                        number = line_with_timeout(stream, 15)
                    if not number.isdigit():
                        raise RuntimeError('Private Xvfb did not allocate a display')
                    client_auth = base / 'client.auth'
                    authority(client_auth, cookie, number)
                    env.update(DISPLAY=':' + number, XAUTHORITY=str(client_auth),
                               NUVIO_WEBKIT_ISOLATED_PROBE='1', NUVIO_RUN_LIVE_DISPLAY_TESTS='1')
                    # The host's private D-Bus session uses host libraries. Only
                    # Xvfb needs the test-tool closure; the AppImage wrapper sets
                    # its own private library path for the production overlay.
                    env.pop('LD_LIBRARY_PATH')
                    probe = subprocess.Popen(['dbus-run-session', '--', str(launcher),
                        (exported_controls / 'controls.html').as_uri()], env=env, stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, stderr=output, text=True, start_new_session=True)
                    if line_with_timeout(probe.stdout, 55) != 'OVERLAY_ROUND_TRIP_PASS':
                        raise RuntimeError('Production overlay round trip failed; see ' + str(log))
                    processes = process_evidence(root)
                    # Inspect the actual child's mount namespace through procfs;
                    # do not ask NetworkProcess (trusted upstream) to read files.
                    for process in processes:
                        if process['executable'].endswith('/WebKitWebProcess'):
                            namespace_root = pathlib.Path('/proc') / str(process['pid']) / 'root'
                            inside = namespace_root / str(root / 'share/probe-controls/controls.html').lstrip('/')
                            assert inside.read_bytes() == (controls / 'controls.html').read_bytes()
                            outside = namespace_root / str(base / 'outside-sentinel').lstrip('/')
                            try:
                                outside.stat()
                            except FileNotFoundError:
                                pass
                            else:
                                raise AssertionError('Outside sentinel exposed to WebProcess')
                    probe.stdin.write('q\n')
                    probe.stdin.flush()
                    assert probe.wait(timeout=15) == 0
                    evidence.append(dict(location=label, overlay_round_trip=True, processes=processes,
                                         gpu_note='Production overlay disables hardware acceleration; GPU path covered separately'))
                    (args.output / (label.replace(' ', '-') + '.json')).write_text(
                        json.dumps(evidence[-1], indent=2) + '\n')
                finally:
                    terminate(probe)
                    terminate(server)
    (args.output / 'acceptance.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('Isolated production overlay, relocation and WebProcess sandbox checks PASS in two locations')


if __name__ == '__main__':
    main()
