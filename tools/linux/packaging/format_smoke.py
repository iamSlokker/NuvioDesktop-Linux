#!/usr/bin/env python3
"""The native self-test in each real package, where a user would run it.

native_smoke.py gates the staged image in the builder, which has the packages' build closure and
none of their installers, launchers or sandboxes. This installs what was actually published and
runs composeApp/scripts/linux-native-smoke.py against it:

  deb      --release 22.04|24.04   apt installs the .deb on a stock Ubuntu container
  appimage                         a stock Ubuntu container with no GTK, WebKit or libva
  flatpak                          this machine, under a private Xvfb (CI runner only)

Each format gets three launches. "lavapipe" names the software Vulkan device, so every probe must
pass. "gnome" adds XDG_CURRENT_DESKTOP=GNOME for the paths that switch on it. "no-device" names no
device, as a desktop session does: on a machine with no GPU playback may fail, cleanly, and nothing
else may. None of them sees a desktop session or a GPU, so a pass says nothing about real hardware.
"""
import argparse
import inspect
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from native_smoke import software_vulkan_device

HERE = Path(__file__).resolve().parent
# Inside a container the repository is not mounted, only this directory and that script's.
SMOKE = Path(os.environ.get('NUVIO_FORMAT_SMOKE_SCRIPT') or HERE.parent.parent.parent / 'composeApp/scripts/linux-native-smoke.py')
FLATPAK_APP = 'com.nuvio.media.desktop'
PROBE_NEEDS_DEVICE = 'linux playback'
# Harness only (private X server and bus, gdbus for the MPRIS probe, lavapipe). Nothing here may
# pull in what a package has to bring itself: check_clean() fails if it does for the AppImage.
HARNESS_PACKAGES = ['python3', 'xvfb', 'dbus', 'libglib2.0-bin', 'libx11-6', 'libvulkan1', 'mesa-vulkan-drivers', 'fontconfig', 'fonts-dejavu-core']
# What the AppImage deliberately leaves to the host (test_graphics_drivers_and_platform_abi_are_never_portable_payload):
# libva and the GL stack follow the host's drivers. A stock container has none of it, and without
# them libplayer_bridge.so does not load, so they are installed the way any desktop already has them.
APPIMAGE_HOST_PACKAGES = ['libgl1', 'libegl1', 'libgbm1', 'libdrm2', 'libva2', 'libva-drm2', 'libva-x11-2']
APPIMAGE_MUST_BRING = ['libgtk-3-0', 'libwebkit2gtk-4.1-0', 'libmpv2', 'libavcodec58', 'libavcodec60']
# Software X server: as linux-native-smoke.Containerfile.
BASE_ENV = {'WEBKIT_DISABLE_COMPOSITING_MODE': '1'}
RUN_TIMEOUT = 300


def launches(device):
    """(name, extra environment, probes allowed to fail) of every launch of one format."""
    named = {'NUVIO_NATIVE_SELFTEST_MPV_OPTIONS': 'vulkan-device=' + device}
    return [
        ('lavapipe', named, []),
        ('gnome', {**named, 'XDG_CURRENT_DESKTOP': 'GNOME'}, []),
        ('no-device', {'NUVIO_NATIVE_SELFTEST_MPV_OPTIONS': ''}, [PROBE_NEEDS_DEVICE]),
    ]


def smoke_command(out, name, env, allowed, command, script=SMOKE):
    args = [sys.executable, '-I', str(script), 'smoke', '--runs', '1', '--out', os.path.join(out, name)]
    args += ['--env=%s=%s' % item for item in sorted(env.items())]
    args += ['--allow-failed-probe=' + probe for probe in allowed]
    return args + ['--', *command]


def run_launches(out, command, device, extra_env=None, environ=None):
    """All launches, none skipped after a failure, so one report shows every problem."""
    environ = dict(os.environ if environ is None else environ)
    for name in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS', 'LD_LIBRARY_PATH', 'LD_PRELOAD'):
        environ.pop(name, None)
    results = {}
    for name, env, allowed in launches(device):
        args = smoke_command(out, name, {**BASE_ENV, **env, **(extra_env or {})}, allowed, command)
        print('FORMAT_SMOKE launch', name, flush=True)
        try:
            results[name] = subprocess.run(args, env=environ, timeout=RUN_TIMEOUT).returncode == 0
        except subprocess.TimeoutExpired:
            print('FORMAT_SMOKE launch %s hung past %d s' % (name, RUN_TIMEOUT), flush=True)
            results[name] = False
    return results


def report(fmt, results, out):
    print(json.dumps({'format': fmt, 'launches': results}, sort_keys=True), flush=True)
    Path(out, 'format-smoke.json').write_text(json.dumps({'format': fmt, 'launches': results}, sort_keys=True) + '\n')
    return 0 if results and all(results.values()) else 1


# ---- Inside the stock container ---------------------------------------------------------------
def apt_install(*packages):
    env = dict(os.environ, DEBIAN_FRONTEND='noninteractive')
    subprocess.run(['apt-get', 'install', '-y', '--no-install-recommends', *packages], check=True, env=env)


def check_clean():
    """Fails when the harness itself installed what the AppImage claims to bring."""
    listed = subprocess.run(['dpkg-query', '-W', '-f=${Package} ${Status}\n'], stdout=subprocess.PIPE, text=True, check=True)
    installed = {line.split()[0] for line in listed.stdout.splitlines() if line.endswith(' installed')}
    present = sorted(installed.intersection(APPIMAGE_MUST_BRING))
    if present:
        raise SystemExit('Not a clean container: ' + ', '.join(present))


def inside(fmt, package, out):
    if fmt == 'deb':
        apt_install(str(package))
        command = ['/usr/bin/nuvio-desktop']
        extra = {}
    else:
        apt_install(*APPIMAGE_HOST_PACKAGES)
        check_clean()
        # A container has no FUSE; the runtime's own fallback still goes through AppRun and the wrapper.
        command = [str(package)]
        extra = {'APPIMAGE_EXTRACT_AND_RUN': '1'}
    device = software_vulkan_device('libvulkan.so.1')
    return report(fmt, run_launches(str(out), command, device, extra), out)


# ---- Host side ----------------------------------------------------------------------------------
def container_command(fmt, package, release, out, engine='podman'):
    """Everything is mounted read-only but the report directory; only apt writes to the container."""
    package = Path(package).resolve()
    bootstrap = ('set -eu; export DEBIAN_FRONTEND=noninteractive; apt-get update -qq; '
                 'apt-get install -y -qq --no-install-recommends %s >/dev/null; '
                 'exec python3 /packaging/format_smoke.py inside --format %s --package /package/%s --out /out'
                 % (' '.join(HARNESS_PACKAGES), fmt, package.name))
    # The AppImage's own bubblewrap has to make its nested namespaces, or its WebKit aborts on purpose
    # at the first HUD (the sandbox patch). Capabilities of rootless podman's namespace, not the host's;
    # --privileged would also work but hands over the host's GPU, which the launches must not see.
    nesting = ['--cap-add=ALL', '--security-opt', 'seccomp=unconfined', '--security-opt', 'systempaths=unconfined'] if fmt == 'appimage' else []
    return [engine, 'run', '--rm', *nesting, '--security-opt', 'label=disable',
            '--volume', '%s:/package/%s:ro' % (package, package.name),
            '--volume', '%s:/packaging:ro' % HERE,
            '--volume', '%s:/composeApp-scripts:ro' % SMOKE.parent,
            '--volume', '%s:/out' % Path(out).resolve(),
            '--env', 'NUVIO_FORMAT_SMOKE_SCRIPT=/composeApp-scripts/' + SMOKE.name,
            '--env', 'PYTHONDONTWRITEBYTECODE=1',
            'docker.io/library/ubuntu:' + release, 'sh', '-c', bootstrap]


def flatpak_device():
    """The name as the sandbox's own Vulkan loader and GL extension report it."""
    code = 'import ctypes\n%s\nprint(software_vulkan_device("libvulkan.so.1"))\n' % inspect.getsource(software_vulkan_device)
    code = code.replace('CPU_DEVICE', '4')
    # flatpak run needs a session bus, and a CI runner or container has none outside the smoke's own.
    result = subprocess.run(['dbus-run-session', '--', 'flatpak', 'run', '--user', '--command=python3', FLATPAK_APP, '-I', '-c', code],
                            stdout=subprocess.PIPE, text=True, check=True, timeout=120)
    return result.stdout.strip().splitlines()[-1]


def flatpak(package, out):
    subprocess.run(['flatpak', 'install', '--user', '--noninteractive', '--no-related', '--bundle', str(package)], check=True)
    environ = dict(os.environ, NUVIO_NATIVE_SMOKE_HOST_XVFB='1')
    # linux-native-smoke.py replaces HOME and the XDG directories, so the install has to be named.
    # An empty data directory leaves the app on its sandboxed default; --out is not visible in there.
    extra = {'FLATPAK_USER_DIR': environ['FLATPAK_USER_DIR'], 'NUVIO_NATIVE_SELFTEST_DATA_DIR': ''}
    command = ['flatpak', 'run', '--user', FLATPAK_APP]
    return report('flatpak', run_launches(str(out), command, flatpak_device(), extra, environ), out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('where', choices=('host', 'inside'))
    parser.add_argument('--format', required=True, choices=('deb', 'appimage', 'flatpak'))
    parser.add_argument('--package', required=True, type=Path)
    parser.add_argument('--release', default='22.04', choices=('22.04', '24.04'))
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    if args.where == 'inside':
        return inside(args.format, args.package, args.out)
    if any(os.environ.get(name) for name in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS')):
        raise SystemExit('Refusing: a display or live-test variable is set')
    if args.format == 'flatpak':
        if not os.environ.get('FLATPAK_USER_DIR'):
            raise SystemExit('Flatpak: set FLATPAK_USER_DIR to the isolated CI installation')
        return flatpak(args.package.resolve(), args.out.resolve())
    if not shutil.which('podman'):
        raise SystemExit('podman is required')
    return subprocess.run(container_command(args.format, args.package, args.release, args.out)).returncode


if __name__ == '__main__':
    sys.exit(main())
