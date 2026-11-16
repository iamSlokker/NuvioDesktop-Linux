#!/usr/bin/env python3
"""A single-file .AppImage around the jpackage app image, for local development.

The release AppImage (formats.build_appimage) wraps the GraalVM native image plus a private WebKit
generation, and is built in the pinned Ubuntu package builder; it is the artifact verify.py checks
and CI requires. This is the fast local counterpart: it wraps the *JVM* app image that
createReleaseDistributable leaves in composeApp/build/compose/binaries/main-release/app/nuvio, so
it needs no native image, no private media runtime and no WebKit build -- seconds, not hours.

Two differences follow from that, and they are why nothing here is named or checked like a release
artifact:

  * the image ships a Java runtime and the application JARs, and uses the host's WebKitGTK rather
    than a bundled private engine (as it uses the host's libmpv rather than the private media
    runtime, which is why the image carries lib/native at all);
  * it uses the pinned, prebuilt type2 runtime binary, where the release builds that runtime from
    pinned sources (appimage_runtime.py) because a downloaded binary cannot be corresponding
    source.

The wrapped app image is what carries that runtime: createDistributable and
createReleaseDistributable stage lib/native (libplayer_bridge.so, libmpv.so.2,
libquickjs.so) into it from the local bridge build -- composeApp/linux-native-runtime.gradle.kts.
Wrapping an image without it produces an app that starts and never plays.

Everything else is deliberately the release recipe, so the two stay in step: the same pinned
appimagetool, the same AppDir layout (usr/lib/nuvio-desktop), the same AppRun shape, the same
canonical .desktop and the same icon resizing. verify.py has no -dev format and never sees this
artifact; nothing here may be published as a package.

Usage (the packageReleaseAppImage Gradle task runs exactly this):

  python3 tools/linux/packaging/dev_appimage.py \
      --app-image composeApp/build/compose/binaries/main-release/app/nuvio \
      --version 1.27.0 --work-dir composeApp/build/appimage-linux \
      --output composeApp/build/appimage-linux/Nuvio-Desktop-1.27.0-dev-x86_64.AppImage
"""
import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE))

from fetch import CONFIG, fetch  # noqa: E402

# The application image, laid out where the release packages put it too.
PORTABLE_DIR = pathlib.Path('usr/lib/nuvio-desktop')
APP_DIR_NAME = 'Nuvio.AppDir'
APP_RUN = '''#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$root/{portable}/bin/{launcher}" "$@"
'''


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def check_app_image(app_image: pathlib.Path, launcher: str) -> None:
    binary = app_image / 'bin' / launcher
    if not app_image.is_dir() or not binary.is_file():
        raise ValueError(
            f'No app image at {app_image} (expected {binary}). Build it first: '
            './gradlew :composeApp:createReleaseDistributable')


def assemble_app_dir(app_image: pathlib.Path, app_dir: pathlib.Path, launcher: str,
                     java: str, icon_source: pathlib.Path) -> pathlib.Path:
    shutil.rmtree(app_dir, ignore_errors=True)
    portable = app_dir / PORTABLE_DIR
    portable.parent.mkdir(parents=True)
    shutil.copytree(app_image, portable, symlinks=True)
    app_run = app_dir / 'AppRun'
    app_run.write_text(APP_RUN.format(portable=PORTABLE_DIR.as_posix(), launcher=launcher))
    app_run.chmod(0o755)
    # The canonical entry is copied verbatim because verify.py and test_desktop_entry.py both pin
    # its Exec=nuvio-desktop; the AppDir therefore carries that name too, pointing at the image's
    # own launcher, so the menu entry resolves when the AppDir root is on PATH.
    (app_dir / 'nuvio-desktop').symlink_to((PORTABLE_DIR / 'bin' / launcher).as_posix())
    desktop = app_dir / (CONFIG['application_id'] + '.desktop')
    shutil.copy2(HERE / desktop.name, desktop)
    if shutil.which('desktop-file-validate'):
        run('desktop-file-validate', desktop)
    icon = app_dir / (CONFIG['application_id'] + '.png')
    run(java, '-Djava.awt.headless=true', HERE / 'ResizeIcon.java', icon_source, icon)
    (app_dir / '.DirIcon').symlink_to(icon.name)
    return app_dir


def pack(app_dir: pathlib.Path, output: pathlib.Path, work: pathlib.Path, version: str) -> None:
    # The pinned appimagetool is run from its extracted tree, never as a mounted AppImage: FUSE is
    # not available in every build environment. Mirrors formats.build_appimage.
    executable = fetch(CONFIG['tools']['appimagetool'], work / 'inputs/appimagetool')
    runtime = fetch(CONFIG['tools']['appimage-runtime'], work / 'inputs/appimage-runtime')
    tool = work / 'appimagetool'
    shutil.rmtree(tool, ignore_errors=True)
    tool.mkdir(parents=True)
    executable.chmod(0o755)
    run(executable, '--appimage-extract', cwd=tool, stdout=subprocess.DEVNULL)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    run(tool / 'squashfs-root/AppRun', '--runtime-file', runtime,
        '--mksquashfs-opt', '-processors', '--mksquashfs-opt', '2', app_dir, output,
        env=dict(os.environ, ARCH='x86_64', VERSION=version))


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--app-image', required=True, help='jpackage app image to wrap')
    parser.add_argument('--version', required=True, help='version stamped into the AppImage')
    parser.add_argument('--output', required=True, help='the .AppImage file to write')
    parser.add_argument('--work-dir', required=True, help='fetched tools and the AppDir')
    parser.add_argument('--launcher', default='nuvio', help='launcher name inside the app image')
    parser.add_argument('--java', default='java', help='java for the icon resize; no display access')
    parser.add_argument('--icon-source', default=str(REPO / 'composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png'))
    args = parser.parse_args()

    app_image = pathlib.Path(args.app_image).resolve()
    work = pathlib.Path(args.work_dir).resolve()
    output = pathlib.Path(args.output).resolve()
    icon_source = pathlib.Path(args.icon_source).resolve()
    if not icon_source.is_file():
        parser.error(f'icon source is missing: {icon_source}')
    if output == work or work.is_relative_to(output) or output.is_relative_to(HERE):
        parser.error('Unsafe output path')
    check_app_image(app_image, args.launcher)
    work.mkdir(parents=True, exist_ok=True)
    app_dir = assemble_app_dir(app_image, work / APP_DIR_NAME, args.launcher, args.java, icon_source)
    pack(app_dir, output, work, args.version)
    size = output.stat().st_size / (1024 * 1024)
    print(f'{output}\n{size:.1f} MiB  sha256 {hashlib.sha256(output.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    main()
