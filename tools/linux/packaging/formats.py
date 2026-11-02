#!/usr/bin/env python3
"""DEB and AppImage wrappers around the same native-image application image (not each other)."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

from appimage_runtime import ARCHIVE_PREFIX, NOTICE_DIR, verify_output
from fetch import CONFIG, fetch
import native_evidence

HERE = pathlib.Path(__file__).resolve().parent
# Platform ABI and graphics driver entry points stay outside the AppImage.
# Never copy the dynamic linker, glibc, C++ ABI, Mesa/NVIDIA ICDs or vendor libs.
PLATFORM = re.compile(r'^(ld-linux|lib(?:c\.so|m\.so|pthread|dl\.so|rt\.so|resolv|nss_|'
                      r'util\.so|anl\.so|BrokenLocale|stdc\+\+|gcc_s|GL(?:X|dispatch|\.so)|EGL|GLES|gbm|drm|va(?:-|\.)|'
                      r'cuda|nvcuvid|nvidia|cudart))')


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def portable_support(image, engine=None, resource_sources=None):
    if engine is None:
        raise ValueError('AppImage blocked: a verified production WebKit generation is required; '
                         'use --format webkit then --format appimage --webkit-generation PATH')
    from webkit.stage import stage
    stage(image, engine, resource_sources)


def build(work, deb_only=False):
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
    # The native executable needs zlib; the JDK libraries native-image emits need
    # FreeType (libfontmanager), ALSA (libjsound) and X11/Xext/Xi/Xrender/Xtst (libawt_xawt).
    depends = ('libc6 (>= 2.35), libstdc++6 (>= 12), libgcc-s1, libatomic1, '
               'libgtk-3-0, libwebkit2gtk-4.1-0, libx11-6, libxext6, libxi6, libxrender1, libxtst6, '
               'libxrandr2, libxss1, '
               'libxcb1, libwayland-client0, libva2, libva-drm2, libva-x11-2, libdrm2, '
               'libasound2, libpulse0, libfontconfig1, libfreetype6, libfribidi0, libharfbuzz0b, '
               'libssl3, zlib1g, liblcms2-2, libudev1, libgl1, ca-certificates, xdg-utils, '
               # The player only renders through Vulkan: without a driver there is no video.
               'mesa-vulkan-drivers | vulkan-icd')
    (deb / 'DEBIAN/control').write_text(f'''Package: {CONFIG['package_name']}
Version: {version}
Architecture: amd64
Maintainer: Nuvio Media
Depends: {depends}
Recommends: ffmpeg
Section: video
Priority: optional
Description: {CONFIG['display_name']}
 Integrated desktop media player with a private libmpv. Native executable;
 no Java runtime is installed or required.
''')
    run('dpkg-deb', '--root-owner-group', '--build', deb, artifacts / f'nuvio-desktop_{version}_amd64.deb')
    shutil.copy2(image / 'release-info.json', artifacts / 'release-info.json')
    archive_application_source(work, artifacts, info)
    # TorrServer's corresponding source (GPL-3.0): the pinned tag, its vendored Go modules and
    # the build steps (torrserver/build.py); SOURCE-MAP.json lists it for every package.
    shutil.copy2(work / 'torrserver/Nuvio-TorrServer-source.tar.gz',
                 artifacts / f'Nuvio-TorrServer-source-{info["git_commit"][:12]}.tar.gz')
    # Nuvio Engine's (GPL-3.0): the tag, the desktop patches, the patched libtorrent and Boost.
    shutil.copy2(work / 'nuvio-engine/Nuvio-Engine-source.tar.gz',
                 artifacts / f'Nuvio-Engine-source-{info["git_commit"][:12]}.tar.gz')
    # InnerTubeX's (GPL-3.0): the pinned tag archive of the JAR compiled into the executable.
    shutil.copy2(work / 'innertubex/Nuvio-InnerTubeX-source.tar.gz',
                 artifacts / f'Nuvio-InnerTubeX-source-{info["git_commit"][:12]}.tar.gz')
    # Exact native-image input JARs, manifest, native-image reports and the smoke
    # record; also the material needed to rebuild/relink the executable.
    native_evidence.archive(work, artifacts / f'Nuvio-native-image-inputs-{info["git_commit"][:12]}.tar',
                            info['source_date_epoch'])
    run('python3', HERE / 'verify.py', 'artifacts', work, '--formats', 'deb')
    if not deb_only:
        build_appimage(work)



SOURCE_README = """Nuvio application source snapshot — scope and rebuilding

application/ contains the recorded application inputs, Linux bridge sources,
interfaces, recipes and patches. source-files.json records their SHA256 values;
source-info.json binds the snapshot to its Git revision and build identity.

This is NOT a complete third-party Corresponding Source distribution. Media,
GraalVM (whose JDK class library, Substrate VM and emitted JDK libraries are in
the native executable), Maven/native dependencies and embedded AppImage runtime
need their own exact source inputs, notices and any required build/relink/install
materials; SOURCE-MAP.json names the release's GraalVM and media source archives.
The separate WebKit source artifact covers its pinned WebKit/GTK inputs and patches,
not every Ubuntu library copied into AppImage. See the compliance report when
present under application/docs/compliance/ and tools/linux/packaging/README.md.

Work only in a disposable copy. Use the pinned Temurin build JDK, the pinned
GraalVM CE (tools/linux/packaging/config.json) and public client properties,
never a developer local.properties. Gradle wrapper and dependency artifacts must
be retained separately for offline rebuilding; the exact JARs native-image
compiled, its arguments and metadata hashes are in Nuvio-native-image-inputs-*.tar.
The top-level packaging orchestrator currently requires Git metadata omitted from
this tar: check out the recorded Git commit and compare every recorded source byte
before using its packaging commands. A fully offline snapshot-only packaging
bootstrap is not supplied; do not represent this archive as a complete offline
rebuild kit.

Application build (after providing the documented verified dependencies):
  cd application
  cp tools/linux/packaging/public-client.properties local.properties
  env -u DISPLAY -u WAYLAND_DISPLAY -u NUVIO_RUN_LIVE_DISPLAY_TESTS \
    ./gradlew :composeApp:createLinuxNativeDistributable -Pnuvio.desktop.channel=nightly \
    -Pnuvio.graalvm.home=/path/to/graalvm-community-openjdk-25.0.2+10.1
(composeApp/native-image-linux/README.md; build in the Ubuntu 22.04 package builder
for the packages' glibc floor.)
Linux native/media build and installation layout are described in
application/tools/linux/media-runtime/README.md and
application/tools/linux/packaging/README.md. Preserve the sandbox when rebuilding
or installing modified versions. When present, consult
application/docs/compliance/modified-build-and-install.md for the exact shared
media replacement/repack paths, format-specific installation boundaries and
remaining untested/static-link source gaps. No release or legal certification
is implied.
"""


def archive_application_source(work, artifacts, info, source=pathlib.Path('/source')):
    """Retain application inputs with an explicit, honest corresponding-source scope."""
    sources = work / 'corresponding-source'
    shutil.rmtree(sources, ignore_errors=True)
    for name, expected in json.loads((work / 'source-files.json').read_text()):
        relative = pathlib.PurePosixPath(name)
        if (relative.is_absolute() or '..' in relative.parts or name in {'', '.'} or '\\' in name
                or relative.name == 'local.properties' or relative.parts[0] in {'.git', '.aws', '.codex', '.agents'}):
            raise ValueError('Unsafe source inventory path: ' + name)
        src = source / name
        if src.is_symlink() or not src.is_file() or not src.resolve().is_relative_to(source.resolve()):
            raise ValueError('Missing/nonregular source input: ' + name)
        if hashlib.sha256(src.read_bytes()).hexdigest() != expected:
            raise ValueError('Source changed during packaging: ' + name)
        dst = sources / 'application' / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    shutil.copy2(work / 'source-files.json', sources / 'source-files.json')
    shutil.copy2(work / 'source-info.json', sources / 'source-info.json')
    (sources / 'SOURCE-README.txt').write_text(SOURCE_README)
    shutil.make_archive(str(artifacts / ('Nuvio-source-' + info['git_commit'][:12])), 'gztar', sources)


def build_appimage(work, engine=None):
    # Reuse the canonical app/media image. Never remove existing DEB/Flatpak
    # artifacts when iterating on the AppImage-only engine.
    # Recheck tool bytes even when assembling independently of the native build.
    fetch(CONFIG['tools']['appimagetool'], work / 'inputs/appimagetool')
    # The type2 runtime is built from pinned sources (appimage_runtime.py),
    # never downloaded; only the manifest-pinned SHA-256 is accepted here.
    runtime = verify_output(work / 'appimage-runtime')
    image = work / 'canonical'
    info = json.loads((image / 'release-info.json').read_text())
    suffix = '' if info['channel'] == 'stable' else '~nightly'
    version = info['version'] + suffix + '+git.' + info['git_commit'][:12]
    if info['dirty']:
        version += '.dirty.' + info['source_sha256'][:12]
    artifacts = work / 'artifacts'
    artifacts.mkdir(exist_ok=True)
    appdir = work / 'Nuvio.AppDir'
    shutil.rmtree(appdir, ignore_errors=True)
    portable = appdir / 'usr/lib/nuvio-desktop'
    shutil.copytree(image, portable, symlinks=True)
    portable_support(portable, engine, work / 'webkit-resource-sources')
    shutil.copy2(image / 'share/applications/com.nuvio.media.desktop.desktop', appdir / 'com.nuvio.media.desktop.desktop')
    shutil.copy2(image / 'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png', appdir / 'com.nuvio.media.desktop.png')
    (appdir / '.DirIcon').symlink_to('com.nuvio.media.desktop.png')
    (appdir / 'AppRun').write_text('#!/bin/sh\nset -eu\nroot=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec "$root/usr/lib/nuvio-desktop/bin/nuvio-desktop" "$@"\n')
    (appdir / 'AppRun').chmod(0o755)
    shutil.copytree(runtime['licenses'], appdir / NOTICE_DIR)
    for stale in artifacts.glob(ARCHIVE_PREFIX + '*.tar.gz'):
        stale.unlink()
    shutil.copy2(runtime['source_archive'], artifacts / runtime['source_archive'].name)
    run('python3', HERE / 'verify.py', 'image', portable, '--native-evidence', work / 'native-image', '--require-smoke')
    tool = work / 'appimagetool'
    shutil.rmtree(tool, ignore_errors=True)
    tool.mkdir()
    executable = work / 'inputs/appimagetool'
    executable.chmod(0o755)
    run(executable, '--appimage-extract', cwd=tool, stdout=subprocess.DEVNULL)
    run(tool / 'squashfs-root/AppRun', '--runtime-file', runtime['runtime'],
        '--mksquashfs-opt', '-processors', '--mksquashfs-opt', '2', appdir,
        artifacts / f'Nuvio-Desktop-{version}-x86_64.AppImage',
        env=dict(os.environ, ARCH='x86_64', VERSION=version))


if __name__ == '__main__':
    build(pathlib.Path(sys.argv[1]), deb_only='--deb-only' in sys.argv[2:])
