#!/usr/bin/env python3
"""Opt-in private Xvfb A/B probe. Never connects to the active desktop or launches Nuvio.

Requires an already verified AppImage tree, existing isolated probe tools, the
native paint probe and compiled JVM test classpath. Never builds WebKit/packages.
"""
import argparse
import importlib.util
import os
import pathlib
import resource
import secrets
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from webkit.contract import check_runtime

spec = importlib.util.spec_from_file_location('isolated', HERE / 'probe-isolated.py')
isolated = importlib.util.module_from_spec(spec)
spec.loader.exec_module(isolated)


def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=pathlib.Path, required=True)
    parser.add_argument('--tools', type=pathlib.Path, required=True)
    parser.add_argument('--paint-probe', type=pathlib.Path, required=True)
    parser.add_argument('--classpath', type=pathlib.Path, required=True)
    parser.add_argument('--java-home', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--run-isolated-display-probes', action='store_true')
    args = parser.parse_args()
    if not args.run_isolated_display_probes or any(os.environ.get(k) for k in
            ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Explicit private-display opt-in and unset desktop/live-test variables required')
    image, tools = args.image.resolve(), args.tools.resolve()
    check_runtime(image)
    args.output.mkdir(parents=True, exist_ok=True)
    icds = list(pathlib.Path('/usr/share/vulkan/icd.d').glob('lvp_icd.x86_64.json'))
    if len(icds) != 1:
        raise RuntimeError('Exactly one software lavapipe ICD required; never substitute a hardware device')
    with tempfile.TemporaryDirectory(prefix='nuvio-private-render-') as tmp:
        base = pathlib.Path(tmp)
        env = dict(PATH='/usr/bin:/bin', LANG='C.UTF-8', LC_ALL='C.UTF-8', TZ='UTC')
        for key, name in [('HOME', 'home'), ('XDG_CONFIG_HOME', 'config'), ('XDG_DATA_HOME', 'data'),
                          ('XDG_CACHE_HOME', 'cache'), ('XDG_STATE_HOME', 'state'),
                          ('XDG_RUNTIME_DIR', 'runtime'), ('TMPDIR', 'tmp')]:
            directory = base / name
            directory.mkdir(mode=0o700)
            env[key] = str(directory)
        auth, client_auth = base / 'server.auth', base / 'client.auth'
        cookie = secrets.token_bytes(16)
        isolated.authority(auth, cookie, '0')
        read_fd, write_fd = os.pipe()
        server = native = hud = None
        with (args.output / 'xvfb.log').open('w') as server_log:
            try:
                server_env = dict(env, LD_LIBRARY_PATH=str(tools / 'lib'))
                server = subprocess.Popen([str(tools / 'bin/Xvfb'), '-displayfd', str(write_fd),
                    '-auth', str(auth), '-screen', '0', '1280x720x24', '-nolisten', 'tcp',
                    '-noreset', '-xkbdir', str(tools / 'xkb')], env=server_env,
                    pass_fds=[write_fd], stdout=server_log, stderr=server_log, start_new_session=True)
                os.close(write_fd)
                with os.fdopen(read_fd) as stream:
                    number = isolated.line_with_timeout(stream, 15)
                if not number.isdigit():
                    raise RuntimeError('Private Xvfb did not allocate a display')
                isolated.authority(client_auth, cookie, number)
                env.update(DISPLAY=':' + number, XAUTHORITY=str(client_auth),
                           NUVIO_RUN_LIVE_DISPLAY_TESTS='1', NUVIO_ISOLATED_VIDEO_PROBE='1',
                           NUVIO_PLAYER_RENDERING_PROBE='1',
                           VK_DRIVER_FILES=str(icds[0]), VK_ICD_FILENAMES=str(icds[0]))
                with (args.output / 'native-video.log').open('w') as log:
                    # No WebKit view in this JVM probe: actual JNI/gpu-next/x11vk,
                    # local 16:9 source, null audio, real output readback.
                    native = subprocess.Popen([str(args.java_home / 'bin/java'),
                        '-Djava.awt.headless=false', '-Djava.io.tmpdir=' + env['TMPDIR'],
                        '-Dnuvio.playerProbe.output=' + str(args.output.resolve()),
                        '-Duser.home=' + env['HOME'], '-cp', args.classpath.read_text().strip(),
                        'org.junit.runner.JUnitCore',
                        'com.nuvio.app.features.player.desktop.LinuxPlayerRenderingProbeTest'],
                        env=env, stdout=log, stderr=log, start_new_session=True)
                    native_passed = native.wait(timeout=90) == 0
                # Reuse the verified engine; never execute or replace Nuvio or
                # its application launcher, even in this disposable copy.
                root = base / 'private image'
                shutil.copytree(image, root, symlinks=True)
                fixture = root / 'bin/controls-paint-probe'
                shutil.copy2(args.paint_probe, fixture)
                controls = HERE.parents[3] / 'composeApp/src/desktopMain/resources/player-ui'
                exported = base / 'tmp/controls'
                shutil.copytree(controls, exported)
                # Explicit fixture environment uses the same private engine,
                # module and sandbox inputs as the package. No app wrapper runs.
                hud_env = dict(env, GDK_BACKEND='x11', WEBKIT_FORCE_SANDBOX='1',
                    LD_LIBRARY_PATH=f'{root}/lib/native',
                    GSETTINGS_SCHEMA_DIR=f'{root}/share/glib-2.0/schemas',
                    XDG_DATA_DIRS=f'{root}/share:/usr/local/share:/usr/share',
                    GIO_MODULE_DIR=f'{root}/lib/native/gio/modules',
                    GIO_EXTRA_MODULES=f'{root}/lib/native/gio/modules',
                    GDK_PIXBUF_MODULEDIR=f'{root}/lib/native/gdk-pixbuf-2.0/2.10.0/loaders',
                    GTK_PATH=f'{root}/lib/native/gtk-3.0',
                    GST_PLUGIN_SYSTEM_PATH_1_0=f'{root}/lib/native/gstreamer-1.0',
                    GST_PLUGIN_PATH_1_0='', GST_PLUGIN_SCANNER=f'{root}/bin/nuvio-gst-plugin-scanner',
                    GDK_PIXBUF_MODULE_FILE=f'{base}/cache/pixbuf.cache',
                    GTK_IM_MODULE_FILE=f'{base}/cache/immodules.cache',
                    GST_REGISTRY=f'{base}/cache/registry.bin')
                for helper, directory, variable in [
                    ('nuvio-pixbuf-query', pathlib.Path(hud_env['GDK_PIXBUF_MODULEDIR']), 'GDK_PIXBUF_MODULE_FILE'),
                    ('nuvio-gtk-query', root / 'lib/native/gtk-3.0/3.0.0/immodules', 'GTK_IM_MODULE_FILE')]:
                    with pathlib.Path(hud_env[variable]).open('w') as cache:
                        subprocess.run([str(root / 'bin' / helper), *map(str, sorted(directory.glob('*.so')))],
                                       env=hud_env, stdout=cache, check=True, timeout=15)
                with (args.output / 'hud-paint.log').open('w') as log:
                    # The session-bus orchestrator uses host libraries; only our
                    # non-application native fixture receives private libraries.
                    command = ['dbus-run-session', '--', '/usr/bin/env',
                        *[f'{key}={value}' for key, value in hud_env.items()], str(fixture),
                        (exported / 'controls.html').as_uri()]
                    hud = subprocess.Popen(command, env=env, stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, stderr=log, text=True, start_new_session=True)
                    line = isolated.line_with_timeout(hud.stdout, 55)
                    if not line.startswith('ISOLATED_HUD_PAINT_PASS:'):
                        raise RuntimeError('HUD-only paint probe failed; see hud-paint.log')
                    # Existing strict sandbox/process checks, without disabling
                    # namespaces/seccomp or accepting host WebKit substitution.
                    import json
                    evidence = isolated.process_evidence(root)
                    (args.output / 'sandbox.json').write_text(json.dumps(evidence, indent=2) + '\n')
                    log.write(line + '\n')
                    hud.stdin.write('q\n')
                    hud.stdin.flush()
                    if hud.wait(timeout=15) != 0:
                        raise RuntimeError('HUD cleanup failed')
                if not native_passed:
                    raise RuntimeError('HUD-only probe passed; native-only probe failed; see native-video.log and mpv.log')
                print('Native video and private WebKit HUD isolated probes PASS (software Vulkan; not NVIDIA acceptance)')
            finally:
                for process in [hud, native, server]:
                    isolated.terminate(process)


if __name__ == '__main__':
    main()
