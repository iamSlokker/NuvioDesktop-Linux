#!/usr/bin/env python3
"""DEB and AppImage wrappers around the same jpackage image (not each other)."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

from fetch import CONFIG

HERE = pathlib.Path(__file__).resolve().parent
# Platform ABI and graphics driver entry points stay outside the AppImage.
# Never copy the dynamic linker, glibc, C++ ABI, Mesa/NVIDIA ICDs or vendor libs.
PLATFORM = re.compile(r'^(ld-linux|lib(?:c\.so|m\.so|pthread|dl\.so|rt\.so|resolv|nss_|'
                      r'util\.so|anl\.so|BrokenLocale|stdc\+\+|gcc_s|GL(?:X|dispatch|\.so)|EGL|GLES|gbm|drm|va(?:-|\.)|'
                      r'cuda|nvcuvid|nvidia|cudart))')


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def portable_support(image):
    native = image / 'lib/native'
    webkit = pathlib.Path('/usr/lib/x86_64-linux-gnu/libwebkit2gtk-4.1.so.0')
    if b'WEBKIT_EXEC_PATH\x00' not in webkit.read_bytes():
        raise ValueError('AppImage blocked: release WebKit hardcodes helper paths; '
                         'a reviewed relocatable WebKit build is required. '
                         'Do not disable its sandbox or depend on host WebKit.')
    # WebKit executes these out of process; the .so closure alone is insufficient.
    helpers = pathlib.Path('/usr/lib/x86_64-linux-gnu/webkit2gtk-4.1')
    if not helpers.is_dir():
        raise ValueError('Missing WebKitGTK subprocess directory')
    shutil.copytree(helpers, image / 'lib/webkit2gtk-4.1', symlinks=False)
    for name in ['glib-2.0/schemas', 'mime']:
        shutil.copytree(pathlib.Path('/usr/share') / name, image / 'share' / name, dirs_exist_ok=True)
    pending = [p for p in image.rglob('*') if p.is_file() and not p.is_symlink() and p.open('rb').read(4) == b'\x7fELF']
    seen, packages = set(), set()
    env = dict(os.environ, LD_LIBRARY_PATH=f'{native}:{image}/lib/runtime/lib:{image}/lib/runtime/lib/server')
    while pending:
        elf = pending.pop()
        key = str(elf.resolve())
        if key in seen:
            continue
        seen.add(key)
        output = subprocess.check_output(['ldd', str(elf)], text=True, env=env)
        if 'not found' in output:
            raise ValueError('Unresolved portable input: ' + str(elf) + '\n' + output)
        for soname, path in re.findall(r'(\S+) => (/\S+)', output):
            if PLATFORM.match(soname) or pathlib.Path(path).resolve().is_relative_to(image.resolve()):
                continue
            src = pathlib.Path(path).resolve()
            target = native / src.name
            if target.exists():
                if target.read_bytes() != src.read_bytes():
                    raise ValueError('Conflicting private library: ' + soname)
                continue
            shutil.copy2(src, target)
            if soname != src.name:
                (native / soname).symlink_to(src.name)
            pending.append(target)
            for candidate in [src, pathlib.Path('/' + str(src).removeprefix('/usr/'))]:
                owner = subprocess.run(['dpkg-query', '-S', str(candidate)], text=True, capture_output=True)
                if owner.returncode == 0:
                    packages.update(line.split(': ', 1)[0].split(':')[0] for line in owner.stdout.splitlines())
                    break
            else:
                raise ValueError('No redistribution inventory owner: ' + str(src))
    notices = image / 'share/licenses/Ubuntu'
    notices.mkdir(parents=True)
    for package in sorted(packages):
        source = pathlib.Path('/usr/share/doc') / package / 'copyright'
        if not source.is_file():
            raise ValueError('Missing platform-library notice: ' + package)
        shutil.copy2(source, notices / (package + '.copyright'))
    (notices / 'packages.json').write_text(json.dumps(sorted(packages), indent=2) + '\n')
    # Relative lookup for copied libraries and subprocesses. JDK layout untouched.
    for p in list(native.iterdir()) + list((image / 'lib/webkit2gtk-4.1').rglob('*')):
        if p.is_file() and not p.is_symlink() and p.open('rb').read(4) == b'\x7fELF':
            run('patchelf', '--set-rpath', '$ORIGIN:$ORIGIN/../native:$ORIGIN/../../native:$ORIGIN/../runtime/lib', p)
    launcher = image / 'bin/nuvio-desktop'
    text = launcher.read_text().replace('exec "$image/bin/nuvio"', '''export WEBKIT_EXEC_PATH="$image/lib/webkit2gtk-4.1"
export WEBKIT_INJECTED_BUNDLE_PATH="$image/lib/webkit2gtk-4.1/injected-bundle"
export GSETTINGS_SCHEMA_DIR="$image/share/glib-2.0/schemas"
export XDG_DATA_DIRS="$image/share:/usr/local/share:/usr/share"
exec "$image/bin/nuvio"''')
    launcher.write_text(text)


def build(work):
    image = work / 'canonical'
    info = json.loads((image / 'release-info.json').read_text())
    suffix = '' if info['channel'] == 'stable' else '~nightly'
    version = info['version'] + suffix + '+git.' + info['git_commit'][:12]
    if info['dirty']:
        version += '.dirty.' + info['source_sha256'][:12]
    artifacts = work / 'artifacts'
    shutil.rmtree(artifacts, ignore_errors=True)
    artifacts.mkdir()
    deb = work / 'deb-root'
    shutil.rmtree(deb, ignore_errors=True)
    shutil.copytree(image, deb / 'opt/nuvio-desktop', symlinks=True)
    for directory in ['DEBIAN', 'usr/bin', 'usr/share/applications', 'usr/share/icons/hicolor/512x512/apps']:
        (deb / directory).mkdir(parents=True, exist_ok=True)
    (deb / 'usr/bin/nuvio-desktop').write_text('#!/bin/sh\nexec /opt/nuvio-desktop/bin/nuvio-desktop "$@"\n')
    (deb / 'usr/bin/nuvio-desktop').chmod(0o755)
    for category in ['applications', 'icons']:
        shutil.copytree(image / 'share' / category, deb / 'usr/share' / category, dirs_exist_ok=True)
    depends = ('libc6 (>= 2.35), libstdc++6 (>= 12), libgcc-s1, libatomic1, '
               'libgtk-3-0, libwebkit2gtk-4.1-0, libx11-6, libxext6, libxrandr2, libxss1, '
               'libxcb1, libwayland-client0, libva2, libva-drm2, libva-x11-2, libdrm2, '
               'libasound2, libpulse0, libfontconfig1, libfreetype6, libfribidi0, libharfbuzz0b, '
               'libssl3, zlib1g, liblcms2-2, libudev1, libgl1, ca-certificates, xdg-utils')
    (deb / 'DEBIAN/control').write_text(f'''Package: {CONFIG['package_name']}
Version: {version}
Architecture: amd64
Maintainer: Nuvio Media
Depends: {depends}
Section: video
Priority: optional
Description: {CONFIG['display_name']}
 Integrated desktop media player with a private libmpv and Java runtime.
''')
    run('dpkg-deb', '--root-owner-group', '--build', deb, artifacts / f'nuvio-desktop_{version}_amd64.deb')
    shutil.copy2(image / 'release-info.json', artifacts / 'release-info.json')
    # Inputs/recipes for corresponding-source review. Never copies host config.
    sources = work / 'corresponding-source'
    shutil.rmtree(sources, ignore_errors=True)
    for name, expected in json.loads((work / 'source-files.json').read_text()):
        src = pathlib.Path('/source') / name
        if hashlib.sha256(src.read_bytes()).hexdigest() != expected:
            raise ValueError('Source changed during packaging: ' + name)
        dst = sources / 'application' / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    shutil.copy2(work / 'source-files.json', sources / 'source-files.json')
    shutil.copy2(work / 'source-info.json', sources / 'source-info.json')
    shutil.make_archive(str(artifacts / ('Nuvio-source-' + info['git_commit'][:12])), 'gztar', sources)
    run('python3', HERE / 'verify.py', 'artifacts', work, '--formats', 'deb')
    appdir = work / 'Nuvio.AppDir'
    shutil.rmtree(appdir, ignore_errors=True)
    portable = appdir / 'usr/lib/nuvio-desktop'
    shutil.copytree(image, portable, symlinks=True)
    portable_support(portable)
    shutil.copy2(image / 'share/applications/com.nuvio.media.desktop.desktop', appdir / 'com.nuvio.media.desktop.desktop')
    shutil.copy2(image / 'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png', appdir / 'com.nuvio.media.desktop.png')
    (appdir / '.DirIcon').symlink_to('com.nuvio.media.desktop.png')
    (appdir / 'AppRun').write_text('#!/bin/sh\nset -eu\nroot=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec "$root/usr/lib/nuvio-desktop/bin/nuvio-desktop" "$@"\n')
    (appdir / 'AppRun').chmod(0o755)
    run('python3', HERE / 'verify.py', 'image', portable)
    tool = work / 'appimagetool'
    shutil.rmtree(tool, ignore_errors=True)
    tool.mkdir()
    executable = work / 'inputs/appimagetool'
    executable.chmod(0o755)
    run(executable, '--appimage-extract', cwd=tool, stdout=subprocess.DEVNULL)
    run(tool / 'squashfs-root/AppRun', '--runtime-file', work / 'inputs/appimage-runtime',
        '--mksquashfs-opt', '-processors', '--mksquashfs-opt', '2', appdir,
        artifacts / f'Nuvio-Desktop-{version}-x86_64.AppImage',
        env=dict(os.environ, ARCH='x86_64', VERSION=version))


if __name__ == '__main__':
    build(pathlib.Path(sys.argv[1]))
